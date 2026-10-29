package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.vertx.VertxAsync;
import dev.webfx.platform.util.vertx.VertxInstance;
import dev.webfx.stack.db.datasource.ConnectionDetails;
import dev.webfx.stack.db.datasource.LocalDataSource;
import dev.webfx.stack.db.datasource.LocalDataSourceService;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgException;
import io.vertx.pgclient.SslMode;
import io.vertx.sqlclient.DatabaseException;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Runs {@code erase_past_event()} (V0077) for one event, from the back office, as a job of its own.
 *
 * <h3>Why a job, on a connection of its own</h3>
 *
 * <p>The function has conditions no ordinary data path here can meet. It must run in an explicit REPEATABLE
 * READ transaction (its before/after proof compares one pinned snapshot), as a LATER statement of that
 * transaction than the one declaring intent ({@code kbs.erase_confirm}), and it never commits: whoever calls
 * it reads the grid, then commits or rolls back. A run takes 1.5 to 10 minutes. A submit batch commits on its
 * own and loses the grid; a query batch has no transaction and can hand an aborted connection back to the pool;
 * the pooled queue gives up after 5 minutes while the SQL carries on; and the React bus times out after at most
 * 180 seconds. So the run owns one dedicated {@link PgConnection}, opened with exactly the options the pooled
 * provider uses (VertxLocalPostgresQuerySubmitServiceProvider), and closed when the run ends whatever happened
 * — which also drops the function's {@code sn_*} temp tables. The caller gets the run's id back as soon as its
 * ledger row exists; the screen polls {@code event_erasure_run} (V0146) through {@link EventErasureLedger}.
 *
 * <h3>What happens, in order</h3>
 *
 * <ol>
 *   <li>The event is read here, as the server: whatever the client says about it counts for nothing.</li>
 *   <li>Eligibility, judged against today in Europe/London, for BOTH modes ({@link #refusalFor}): the event is not a
 *       continuous one (its type is not {@code ongoing}: those bookings follow the per-stay retention rule instead),
 *       it no longer takes bookings (its state is in the closed family, or it is a legacy event with none), it has
 *       ended and its retention
 *       line — GREATEST(end_date, audio_closing_date) plus {@link EventErasureConfig#RETENTION_MONTHS} months — is
 *       behind us (a dry run locks the event's bookings for its whole length, and before the line it could never
 *       serve as a commit's baseline anyway). For a commit, in addition: commit is enabled on this server, the phrase reads exactly
 *       {@code ERASE EVENT <id>}, and the commit names a dry run of the SAME event that succeeded less than 24
 *       hours ago with no successful commit since. That dry run's grid is the baseline, read here and held here:
 *       the client names it, it never supplies it.</li>
 *   <li>The dedicated connection opens, and in autocommit: stale runs are marked aborted, the run's id is
 *       reserved, the connection is NAMED after it ({@code application_name = kbs-erasure-<id>}), and the
 *       running row is inserted — which the partial unique index turns into "one run at a time, across servers"
 *       ({@code EventErasureBusyError} when another is running). The name comes BEFORE the row on purpose: the
 *       stale-run rule aborts any running row whose connection name is absent from pg_stat_activity, so a row
 *       inserted first and named second would be abortable by any concurrent start in between.</li>
 *   <li>The run id goes back to the caller; the rest continues in the background.</li>
 *   <li>{@code BEGIN ISOLATION LEVEL REPEATABLE READ}; one statement pinning the time zone, a 45-minute statement
 *       timeout, a 30-second lock timeout and the statement of intent — {@code 'ERASE EVENT ' || id}, built HERE
 *       from the validated id and bound as a parameter, never the text the client typed; for a commit, the lock
 *       and second look described next; then the call.</li>
 *   <li>A commit, before it erases anything, locks the event row {@code FOR SHARE} and reads its state again
 *       ({@link #EVENT_LOCK_SQL}): an event reopened while the removal was starting is refused
 *       ({@code EventErasureNotClosedError}), and a reopen committed after the transaction's snapshot fails the run
 *       with a serialization error (40001) the operator can retry. The lock is held until COMMIT, so every other
 *       write to the event row (a state change, a soft delete, any edit) waits for the run's end, 1.5 to 10
 *       minutes: acceptable for a past event whose bookings are closed, and exactly what keeps it closed while its
 *       data is being removed. Together with V0147, which refuses a reopen while a commit is running, the event cannot
 *       open between the start's check and the commit. A dry run takes no such lock.</li>
 *   <li>A preview rolls back and records the grid.</li>
 *   <li>A commit, still inside the transaction: the drift check against the baseline (every
 *       {@code preflight/*} row, {@code event/bookings} and {@code event/end_date}), the queued-payload refusal,
 *       one history marker per booking that has none, RECONCILED → FINALISED, and the ledger row — then COMMIT,
 *       so the data, the markers, the state and the record land together or not at all.</li>
 *   <li>Any SQL failure rolls back and is recorded (SQLSTATE and the database's message: V0077's messages carry
 *       ids and counts only).</li>
 *   <li>The connection is closed.</li>
 * </ol>
 *
 * <h3>What is and is not logged</h3>
 *
 * <p>One line per run: its id, the event, the mode, the outcome, the duration, the super administrator's person
 * id and a few counts. Never a text value — the grid's names stay in the ledger, the database's error text too.
 * The function's NOTICEs are dropped for the same reason.
 *
 * @author Claude Code
 */
final class EventErasureRunner {

    private EventErasureRunner() {
    }

    // ===== Error keys: "[Key] text" on the bus, the bare key in the ledger — both mapped by the React side ===========

    static final String NOT_FOUND_KEY = "EventErasureNotFoundError";
    static final String CONTINUOUS_EVENT_KEY = "EventErasureContinuousEventError";
    static final String NOT_CLOSED_KEY = "EventErasureNotClosedError";
    static final String NOT_ENDED_KEY = "EventErasureNotEndedError";
    static final String RETENTION_NOT_REACHED_KEY = "EventErasureRetentionNotReachedError";
    static final String BUSY_KEY = "EventErasureBusyError";
    static final String COMMIT_DISABLED_KEY = "EventErasureCommitDisabledError";
    static final String CONFIRM_MISMATCH_KEY = "EventErasureConfirmMismatchError";
    static final String PREVIEW_REQUIRED_KEY = "EventErasurePreviewRequiredError";
    static final String DRIFT_KEY = "EventErasureDriftError";
    static final String QUEUED_PAYLOADS_KEY = "EventErasureQueuedPayloadsError";
    static final String SQL_KEY = "EventErasureSqlError";

    static final String MODE_PREVIEW = "preview";
    static final String MODE_COMMIT = "commit";

    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_REFUSED = "refused";

    private static final String LOG_PREFIX = "[event-erasure] ";
    /** The business calendar: the date guards here and inside V0077 both follow it, not the server's clock zone. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/London");
    /** The states closed to bookings — V0145's trigger and V0147's guards use the same four. */
    private static final Set<String> CLOSED_FAMILY = Set.of("CLOSED", "RECONCILED", "FINALISED", "ARCHIVED");
    private static final int MAX_ERROR_LENGTH = 1000;
    /** Shared with the stale-run rule in {@link EventErasureLedger#STALE_RUNS_SQL}. */
    static final String APPLICATION_NAME_PREFIX = "kbs-erasure-";

    // ===== SQL — every statement a constant; ids, the phrase and the person are bound as parameters ===============

    /**
     * Dates as text so their Java type never depends on the driver; the state as text, not as the enum. Then whether
     * audio_closing_date is set at all: to_char() answers NULL for 'infinity', and a closing date that is set but
     * unreadable must not pass for one that is absent (see {@link #refusalFor}). Last, whether the event's type is
     * continuous (event_type.ongoing, NOT NULL since V0071); LEFT JOIN and coalesce because an event with no type is
     * simply not a continuous one.
     */
    private static final String EVENT_SQL =
        "select e.id, to_char(e.end_date, 'YYYY-MM-DD'), to_char(e.audio_closing_date, 'YYYY-MM-DD'), e.state::text,"
        + " e.audio_closing_date is not null, coalesce(t.ongoing, false)"
        + " from event e left join event_type t on t.id = e.type_id where e.id = $1";

    /** Everything that makes a run a valid baseline for a commit, judged by the database's clock. */
    private static final String PREVIEW_SQL =
        "select p.event_id, p.mode, p.status, p.finished_at > now() - interval '24 hours',"
        + " exists (select 1 from event_erasure_run c where c.event_id = p.event_id and c.mode = 'commit'"
        + " and c.status = 'succeeded' and c.id > p.id),"
        + " jsonb_typeof(p.grid) = 'array'"
        + " from event_erasure_run p where p.id = $1";

    /** The dry run's grid as rows, in the function's order. */
    private static final String BASELINE_SQL =
        "select g.e ->> 'step', g.e ->> 'metric', g.e ->> 'value'"
        + " from event_erasure_run p cross join lateral jsonb_array_elements(p.grid) with ordinality as g(e, ord)"
        + " where p.id = $1 order by g.ord";

    private static final String NEXT_RUN_ID_SQL = "select nextval(pg_get_serial_sequence('public.event_erasure_run', 'id'))";

    /** Session-level (false): it must outlive the transaction, since pg_stat_activity is what proves the run alive. */
    private static final String APPLICATION_NAME_SQL = "select set_config('application_name', $1, false)";

    private static final String INSERT_RUN_SQL =
        "insert into event_erasure_run (id, event_id, mode, status, preview_run_id, requested_by_person_id, state_before, app_name)"
        + " values ($1, $2, $3, 'running', $4, $5, $6, $7)";

    private static final String BEGIN_SQL = "BEGIN ISOLATION LEVEL REPEATABLE READ";

    /**
     * One statement, AFTER the BEGIN and BEFORE the call — V0077 refuses to run as the statement that opened the
     * transaction. is_local = true throughout: all four end with the transaction.
     */
    private static final String SETTINGS_SQL =
        "select set_config('TimeZone', 'Europe/London', true), set_config('statement_timeout', '45min', true),"
        + " set_config('lock_timeout', '30s', true), set_config('kbs.erase_confirm', $1, true)";

    /**
     * Commit only, right after SETTINGS_SQL and before the erase call: the event's state, re-read under a share lock
     * held until COMMIT. In REPEATABLE READ, a row updated by a transaction that committed after our snapshot makes
     * this raise 40001 — which the normal failure path records, so the operator can retry.
     */
    private static final String EVENT_LOCK_SQL = "select state::text from event where id = $1 for share";

    private static final String ERASE_SQL = "select step, metric, value from erase_past_event($1)";

    /**
     * One trail entry per booking of the event, where it has none yet. The target set is V0077's own
     * ({@code document.event_id = p_event_id}, its sn_target_docs), cancelled bookings included. username is
     * never 'online', so neither history INSERT trigger fires and no mail is sent.
     */
    private static final String MARKERS_SQL =
        "insert into history (document_id, comment, username, user_person_id)"
        + " select d.id, 'Personal data removed (GDPR retention policy)', 'gdpr-retention', $2"
        + " from document d where d.event_id = $1"
        + " and not exists (select 1 from history h where h.document_id = d.id and h.username = 'gdpr-retention')";

    /** FINALISED is written by a committed removal of a RECONCILED event, and by nothing else (decision D3). */
    private static final String FINALISE_SQL = "update event set state = 'FINALISED' where id = $1 and state = 'RECONCILED'";

    private static final String STATE_SQL = "select state::text from event where id = $1";

    /**
     * Every ending of a run. clock_timestamp(), not now(): inside the commit's transaction now() is the moment the
     * transaction BEGAN, which would record a ten-minute run as instantaneous. Only a row still running is
     * touched, so nothing overwrites an ending already recorded.
     */
    private static final String FINISH_SQL =
        "update event_erasure_run set status = $2, grid = $3::text::jsonb, error_key = $4, error = $5, state_after = $6,"
        + " finished_at = clock_timestamp() where id = $1 and status = 'running'";

    // ===== The shapes ==============================================================================================

    /** One row of the grid erase_past_event() returns. */
    record GridRow(String step, String metric, String value) {
    }

    /**
     * The event as the server read it ({@link #eventFacts}). {@code audioClosingDateSet} is true even when the date could
     * not be read; {@code ongoing} is the event type's continuous flag, false for an event with no type.
     */
    record EventFacts(int id, LocalDate endDate, LocalDate audioClosingDate, String state, boolean audioClosingDateSet,
                      boolean ongoing) {
    }

    /** A refusal the screen can explain: the key it maps, and the server's short text. */
    record Refusal(String key, String text) {
    }

    /** A run that has passed every check and is about to get its ledger row. */
    private record RunRequest(EventFacts event, boolean commit, Integer previewRunId, List<GridRow> baseline,
                              Integer personId) {
    }

    /** A run with its ledger row. The outcome fields are for the log line, written on the connection's thread. */
    private static final class Run {
        final int id;
        final RunRequest request;
        final long startNanos = System.nanoTime();
        List<GridRow> grid;
        String status;
        String errorKey;
        String sqlState;
        String stateAfter;
        int markers = -1;

        Run(int id, RunRequest request) {
            this.id = id;
            this.request = request;
        }

        int eventId() {
            return request.event().id();
        }
    }

    // ===== Starting ================================================================================================

    /**
     * Checks the request and, when it passes, starts the run.
     *
     * @param arguments {@code [eventId, mode, confirmPhrase, previewRunId]}, as the bus decoded them
     * @param caller    the super administrator {@link EventErasureAccess} allowed
     * @return the run's id, as soon as its ledger row exists
     */
    static Future<Integer> start(Object[] arguments, EventErasureAccess.Caller caller) {
        Integer eventId = EventErasureArguments.positiveId(EventErasureArguments.at(arguments, 0));
        String mode = EventErasureArguments.string(EventErasureArguments.at(arguments, 1));
        if (eventId == null || !(MODE_PREVIEW.equals(mode) || MODE_COMMIT.equals(mode)))
            return refusal(NOT_FOUND_KEY, "No such event, or the request is malformed", eventId, mode, caller.personId());
        boolean commit = MODE_COMMIT.equals(mode);
        String confirmPhrase = EventErasureArguments.string(EventErasureArguments.at(arguments, 2));
        Object previewRunIdArgument = EventErasureArguments.at(arguments, 3);

        return loadEvent(eventId).compose(event -> {
            if (event == null)
                return refusal(NOT_FOUND_KEY, "No such event, or the request is malformed", eventId, mode, caller.personId());
            Integer previewRunId = commit ? EventErasureArguments.positiveId(previewRunIdArgument) : null;
            Refusal refused = refusalFor(event, commit, LocalDate.now(BUSINESS_ZONE), EventErasureConfig.isCommitEnabled(),
                confirmPhrase, previewRunId);
            if (refused != null)
                return refusal(refused.key(), refused.text(), eventId, mode, caller.personId());
            if (!commit)
                return open(new RunRequest(event, false, null, List.of(), caller.personId()));
            return loadBaseline(eventId, previewRunId).compose(baseline -> baseline == null
                ? refusal(PREVIEW_REQUIRED_KEY, "Dry run " + previewRunId + " cannot vouch for this commit: it must be a"
                    + " successful dry run of event " + eventId + ", finished less than 24 hours ago, with no commit since",
                    eventId, mode, caller.personId())
                : open(new RunRequest(event, true, previewRunId, baseline, caller.personId())));
        }).recover(cause -> startFailure(cause, eventId, mode, caller.personId()));
    }

    /**
     * A start that failed: a refusal already framed for the screen passes through as it is; anything else (the
     * event or the dry run could not be read) is logged whole and answered narrowly, so a failure never becomes a
     * description of the schema.
     */
    private static Future<Integer> startFailure(Throwable cause, Integer eventId, String mode, Integer personId) {
        String message = cause == null ? null : cause.getMessage();
        if (message != null && message.startsWith("[EventErasure"))
            return Future.failedFuture(message);
        Console.log(LOG_PREFIX + "Could not start a " + mode + " of event " + eventId + " for person " + personId + ": " + message);
        return Future.failedFuture("[%s] The removal could not be started".formatted(SQL_KEY));
    }

    /**
     * Everything a start decides before it reads a dry run or opens a connection, in this order — the first rule
     * that fails is the answer, or null when the request may go on:
     *
     * <ol>
     *   <li>BOTH MODES. Not a continuous event ({@code EventErasureContinuousEventError}): a continuous event never
     *       ends in the way a per-event removal assumes — its bookings are stays, each kept for its own period after
     *       it ends (the per-stay rule), so erasing "the event" would erase current stays' data along with past ones.</li>
     *   <li>BOTH MODES. The event no longer takes bookings ({@code EventErasureNotClosedError}): its state is in the
     *       closed family, or it is a legacy event with no state (decision D3 lets those be erased by date). An event
     *       still taking bookings would collect new personal data after its removal, while the lifecycle page shows
     *       it as removed. (V0147 keeps it that way afterwards: once a commit succeeded, the database refuses any
     *       state change out of the closed family.)</li>
     *   <li>BOTH MODES. The event has ended ({@code EventErasureNotEndedError}).</li>
     *   <li>BOTH MODES. The retention line is behind us ({@code EventErasureRetentionNotReachedError}). The line gates
     *       the dry run too, not only the commit. A dry run IS a full run rolled back: it holds row locks on the
     *       event's bookings for its whole length (up to ten minutes), so on an event whose guests are still paying
     *       balances or buying recordings it would stall them. And it exists only to be a commit's baseline for 24
     *       hours, which it can never be before the line. A closing date that is set but unreadable ('infinity':
     *       recordings that never close) puts the line out of reach, as GREATEST(end_date, audio_closing_date) +
     *       3 months would: refused, never a fall back to end_date.</li>
     *   <li>COMMIT ONLY. Commit is enabled on this server, the phrase reads exactly {@code ERASE EVENT <id>}, and a
     *       dry run is named (whether that dry run can vouch is read afterwards, by {@link #loadBaseline}).</li>
     * </ol>
     *
     * <p>A pure function of what the server read and what the caller sent, so the rules and their order are checked
     * on their own.
     */
    static Refusal refusalFor(EventFacts event, boolean commit, LocalDate today, boolean commitEnabled,
                              String confirmPhrase, Integer previewRunId) {
        int eventId = event.id();
        if (event.ongoing())
            return new Refusal(CONTINUOUS_EVENT_KEY, "Event " + eventId + " is a continuous event: its bookings follow"
                + " the per-stay retention rule, not a per-event removal");
        if (event.state() != null && !CLOSED_FAMILY.contains(event.state()))
            return new Refusal(NOT_CLOSED_KEY, "Close the event's bookings first: an event still taking bookings would"
                + " collect new personal data after its removal");
        if (event.endDate() == null || !event.endDate().isBefore(today))
            return new Refusal(NOT_ENDED_KEY, "Event " + eventId + " has not ended yet");
        if (event.audioClosingDateSet() && event.audioClosingDate() == null)
            return new Refusal(RETENTION_NOT_REACHED_KEY, "Event " + eventId + "'s recordings have no closing date the"
                + " retention rule can reach");
        LocalDate retentionLine = retentionLine(event);
        if (!retentionLine.isBefore(today))
            return new Refusal(RETENTION_NOT_REACHED_KEY, "Event " + eventId + " keeps its data until " + retentionLine);
        if (!commit)
            return null;
        if (!commitEnabled)
            return new Refusal(COMMIT_DISABLED_KEY, "Removal can only be previewed on this server");
        if (!confirmPhrase(eventId).equals(confirmPhrase))
            return new Refusal(CONFIRM_MISMATCH_KEY, "The confirmation phrase does not match");
        if (previewRunId == null)
            return new Refusal(PREVIEW_REQUIRED_KEY, "A commit needs a recent dry run of the same event");
        return null;
    }

    /** EVENT_SQL's row as the rules read it, or null when there is no such event. */
    static EventFacts eventFacts(int eventId, QueryResult result) {
        if (result == null || result.getRowCount() == 0)
            return null;
        return new EventFacts(eventId, date(result.getValue(0, 1)), date(result.getValue(0, 2)),
            (String) result.getValue(0, 3), Boolean.TRUE.equals(result.getValue(0, 4)), Boolean.TRUE.equals(result.getValue(0, 5)));
    }

    /** The statement of intent V0077 demands, built from the validated id — for the phrase check and the database. */
    static String confirmPhrase(int eventId) {
        return "ERASE EVENT " + eventId;
    }

    /** GREATEST(end_date, audio_closing_date::date) + RETENTION_MONTHS: a run of either mode is allowed once this is past. */
    private static LocalDate retentionLine(EventFacts event) {
        LocalDate anchor = event.audioClosingDate() != null && event.audioClosingDate().isAfter(event.endDate())
            ? event.audioClosingDate() : event.endDate();
        return anchor.plusMonths(EventErasureConfig.RETENTION_MONTHS);
    }

    private static Future<EventFacts> loadEvent(int eventId) {
        return query(EVENT_SQL, eventId).map(result -> eventFacts(eventId, result));
    }

    /** The dry run's grid when that run may serve as this commit's baseline, else null. */
    private static Future<List<GridRow>> loadBaseline(int eventId, int previewRunId) {
        return query(PREVIEW_SQL, previewRunId).compose(preview -> {
            boolean usable = preview != null && preview.getRowCount() == 1
                && Objects.equals(asInteger(preview.getValue(0, 0)), eventId)
                && MODE_PREVIEW.equals(preview.getValue(0, 1))
                && STATUS_SUCCEEDED.equals(preview.getValue(0, 2))
                && Boolean.TRUE.equals(preview.getValue(0, 3))   // finished less than 24 hours ago
                && Boolean.FALSE.equals(preview.getValue(0, 4))  // no successful commit of the event since
                && Boolean.TRUE.equals(preview.getValue(0, 5));  // it has a grid, as an array
            if (!usable)
                return Future.succeededFuture(null);
            return query(BASELINE_SQL, previewRunId).map(rows -> {
                List<GridRow> baseline = new ArrayList<>();
                for (int i = 0; i < rows.getRowCount(); i++)
                    baseline.add(new GridRow((String) rows.getValue(i, 0), (String) rows.getValue(i, 1), (String) rows.getValue(i, 2)));
                return baseline.isEmpty() ? null : baseline;
            });
        });
    }

    /** Opens the run's connection and gets its ledger row; the connection is closed again if that fails. */
    private static Future<Integer> open(RunRequest request) {
        io.vertx.core.Future<Integer> started;
        try {
            started = connect().compose(connection -> insertRun(connection, request)
                .recover(cause -> connection.close().transform(ignored -> io.vertx.core.Future.<Integer>failedFuture(cause))));
        } catch (RuntimeException e) {
            started = io.vertx.core.Future.failedFuture(e);
        }
        return VertxAsync.toWebfxFuture(started).recover(cause -> {
            if (cause instanceof PgException pg && "23505".equals(pg.getSqlState())) // event_erasure_run_one_running_idx
                return refusal(BUSY_KEY, "Another removal is running; start this one once it has finished",
                    request.event().id(), request.commit() ? MODE_COMMIT : MODE_PREVIEW, request.personId());
            Console.log(LOG_PREFIX + "Could not start a " + (request.commit() ? MODE_COMMIT : MODE_PREVIEW) + " of event "
                        + request.event().id() + " for person " + request.personId() + ": " + sqlStateOf(cause));
            return Future.failedFuture("[%s] The removal could not be started".formatted(SQL_KEY));
        });
    }

    /**
     * A connection of the run's own, with the pooled provider's options exactly: TLS preferred and, as there,
     * trusted without verifying the chain — RDS's CA is not in the JVM's store (see that provider's comment).
     *
     * <p>KEEP IN SYNC with VertxLocalPostgresQuerySubmitServiceProvider (webfx-stack-db-querysubmit-vertx): that
     * comment plans VERIFY_FULL against the RDS CA, and when it lands this copy must follow, or the one connection
     * that erases data would stay on trust-all. A shared factory would need a webfx-stack change; not done here.
     */
    private static io.vertx.core.Future<PgConnection> connect() {
        // The default data source model's id (getDefaultDataSourceModel().getDataSourceId() is the same "MDS")
        LocalDataSource dataSource = LocalDataSourceService.getLocalDataSource(DataSourceModelService.getDefaultDataSourceId());
        if (dataSource == null)
            return io.vertx.core.Future.failedFuture("No local data source to open the removal's connection on");
        ConnectionDetails cd = dataSource.getLocalConnectionDetails();
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setPort(cd.getPort())
            .setHost(cd.getHost())
            .setDatabase(cd.getDatabaseName())
            .setUser(cd.getUsername())
            .setPassword(cd.getPassword());
        connectOptions.setSslMode(SslMode.PREFER);
        connectOptions.setSslOptions(new ClientSSLOptions().setTrustAll(true));
        return PgConnection.connect(VertxInstance.getVertx(), connectOptions);
    }

    /** Autocommit: stale runs, the run's id, the connection's name, the running row. Then the background part. */
    private static io.vertx.core.Future<Integer> insertRun(PgConnection connection, RunRequest request) {
        // V0077's NOTICEs name the event and the database; the run's one log line is the record instead
        connection.noticeHandler(notice -> { });
        return connection.query(EventErasureLedger.STALE_RUNS_SQL).execute()
            .compose(ignored -> connection.query(NEXT_RUN_ID_SQL).execute())
            .compose(ids -> {
                int runId = Math.toIntExact(ids.iterator().next().getLong(0));
                String applicationName = APPLICATION_NAME_PREFIX + runId;
                return connection.preparedQuery(APPLICATION_NAME_SQL).execute(Tuple.of(applicationName))
                    .compose(ignored -> connection.preparedQuery(INSERT_RUN_SQL).execute(Tuple.tuple()
                        .addValue(runId)
                        .addValue(request.event().id())
                        .addValue(request.commit() ? MODE_COMMIT : MODE_PREVIEW)
                        .addValue(request.previewRunId())
                        .addValue(request.personId())
                        .addValue(request.event().state())
                        .addValue(applicationName)))
                    .map(ignored -> {
                        runInBackground(connection, new Run(runId, request));
                        return runId;
                    });
            });
    }

    // ===== The run itself ==========================================================================================

    private static void runInBackground(PgConnection connection, Run run) {
        io.vertx.core.Future<Void> work;
        try {
            work = connection.query(BEGIN_SQL).execute()
                .compose(ignored -> connection.preparedQuery(SETTINGS_SQL).execute(Tuple.of(confirmPhrase(run.eventId()))))
                .compose(ignored -> run.request.commit() ? stillClosed(connection, run) : io.vertx.core.Future.succeededFuture(true))
                .compose(stillClosed -> !stillClosed
                    ? rollbackAndFinish(connection, run, STATUS_REFUSED, NOT_CLOSED_KEY,
                        "the event was reopened while the removal was starting")
                    : connection.preparedQuery(ERASE_SQL).execute(Tuple.of(run.eventId()))
                        .compose(rows -> {
                            run.grid = gridOf(rows);
                            return run.request.commit() ? commit(connection, run) : preview(connection, run);
                        }))
                .recover(cause -> failed(connection, run, cause));
        } catch (RuntimeException e) {
            work = failed(connection, run, e);
        }
        work.eventually(connection::close)
            .onComplete(result -> logRun(run, result.failed() ? result.cause() : null));
    }

    /**
     * A commit's second look at the event, under a share lock held until COMMIT ({@link #EVENT_LOCK_SQL}): true when
     * its state is still in the closed family or NULL (the start's rule). A row updated after the snapshot fails the
     * statement (40001) instead, and no row at all is left for erase_past_event() to report.
     */
    private static io.vertx.core.Future<Boolean> stillClosed(PgConnection connection, Run run) {
        return connection.preparedQuery(EVENT_LOCK_SQL).execute(Tuple.of(run.eventId())).map(rows -> {
            String state = firstString(rows);
            return state == null || CLOSED_FAMILY.contains(state);
        });
    }

    /** A dry run: the function has already proved its work; roll it back and keep the grid. */
    private static io.vertx.core.Future<Void> preview(PgConnection connection, Run run) {
        return connection.query("ROLLBACK").execute()
            .compose(ignored -> finish(connection, run, STATUS_SUCCEEDED, null, null, run.request.event().state()))
            .mapEmpty();
    }

    /** The real thing — everything below runs inside the transaction the function ran in, then commits once. */
    private static io.vertx.core.Future<Void> commit(PgConnection connection, Run run) {
        String drift = describeDrift(run.request.baseline(), run.grid);
        if (drift != null)
            return rollbackAndFinish(connection, run, STATUS_FAILED, DRIFT_KEY,
                "The event changed since dry run " + run.request.previewRunId() + ": " + drift);
        String queued = valueOf(run.grid, "preflight", "executed_payloads_to_tag");
        if (!"0".equals(queued))
            return rollbackAndFinish(connection, run, STATUS_REFUSED, QUEUED_PAYLOADS_KEY,
                "preflight/executed_payloads_to_tag = " + queued + ": executed KBS2 payloads of this event would keep"
                + " its text, and this run does not rewrite them");
        return connection.preparedQuery(MARKERS_SQL).execute(Tuple.of(run.eventId(), run.request.personId()))
            .compose(markers -> {
                run.markers = markers.rowCount();
                return connection.preparedQuery(FINALISE_SQL).execute(Tuple.of(run.eventId()));
            })
            .compose(ignored -> connection.preparedQuery(STATE_SQL).execute(Tuple.of(run.eventId())))
            .compose(state -> finish(connection, run, STATUS_SUCCEEDED, null, null, firstString(state)))
            .compose(updated -> updated == 1
                ? connection.query("COMMIT").execute()
                // The ledger row is no longer 'running' — a start judged this run dead: committing now would leave an
                // erasure its own record says did not happen. Fail instead; the recover rolls it back. (That is the
                // row ended BEFORE this transaction's snapshot; ended after it, the UPDATE above fails with 40001
                // instead, and the same recover rolls back.)
                : io.vertx.core.Future.failedFuture("The run's ledger row is no longer running"))
            .mapEmpty();
    }

    /** A commit the rules stopped after the function ran: roll back, then record why, in autocommit. */
    private static io.vertx.core.Future<Void> rollbackAndFinish(PgConnection connection, Run run, String status,
                                                               String errorKey, String error) {
        return connection.query("ROLLBACK").execute()
            .compose(ignored -> finish(connection, run, status, errorKey, truncate(error), run.request.event().state()))
            .mapEmpty();
    }

    /** Any SQL failure: roll back (a failure to do so is moot — closing the connection ends the transaction), record. */
    private static io.vertx.core.Future<Void> failed(PgConnection connection, Run run, Throwable cause) {
        run.sqlState = sqlStateOf(cause);
        String error = truncate(errorText(cause));
        return connection.query("ROLLBACK").execute()
            .transform(ignored -> finish(connection, run, STATUS_FAILED, SQL_KEY, error, run.request.event().state()))
            .mapEmpty();
    }

    /**
     * Writes the run's ending; answers how many ledger rows it touched (1, or 0 when the row had ended already). The
     * log line's outcome is taken from what landed, never from what was attempted, so the two cannot disagree.
     */
    private static io.vertx.core.Future<Integer> finish(PgConnection connection, Run run, String status, String errorKey,
                                                       String error, String stateAfter) {
        return connection.preparedQuery(FINISH_SQL).execute(Tuple.tuple()
                .addValue(run.id)
                .addValue(status)
                .addValue(gridJson(run.grid))
                .addValue(errorKey)
                .addValue(error)
                .addValue(stateAfter))
            .map(result -> {
                int updated = result.rowCount();
                run.status = updated == 1 ? status : status + ", unrecorded: the ledger row had already ended";
                run.errorKey = errorKey;
                run.stateAfter = stateAfter;
                return updated;
            });
    }

    // ===== The grid ================================================================================================

    private static List<GridRow> gridOf(RowSet<Row> rows) {
        List<GridRow> grid = new ArrayList<>(rows.size());
        for (Row row : rows)
            grid.add(new GridRow(row.getString(0), row.getString(1), row.getString(2)));
        return grid;
    }

    /** The grid as the JSON array the ledger stores and RunJson serves: [{step, metric, value}] in order. */
    static String gridJson(List<GridRow> grid) {
        if (grid == null)
            return null;
        List<String> rows = new ArrayList<>(grid.size());
        for (GridRow row : grid)
            rows.add(new EventErasureJson.ObjectWriter()
                .string("step", row.step())
                .string("metric", row.metric())
                .string("value", row.value())
                .toJson());
        return EventErasureJson.array(rows);
    }

    /** The rows a commit must find as its dry run found them: every preflight count, the bookings, the end date. */
    private static Map<String, String> driftChecked(List<GridRow> grid) {
        Map<String, String> checked = new LinkedHashMap<>();
        if (grid != null)
            for (GridRow row : grid)
                if ("preflight".equals(row.step())
                    || ("event".equals(row.step()) && ("bookings".equals(row.metric()) || "end_date".equals(row.metric()))))
                    checked.put(row.step() + "/" + row.metric(), row.value());
        return checked;
    }

    /**
     * Where the commit's grid differs from its dry run's, as {@code step/metric before -> now} (counts and a date —
     * never a text value), or null when nothing does. A row present on one side only counts as a difference, so a
     * baseline that is empty or of another shape can never pass.
     */
    static String describeDrift(List<GridRow> baseline, List<GridRow> now) {
        Map<String, String> before = driftChecked(baseline), after = driftChecked(now);
        if (before.isEmpty())
            return "the dry run recorded no preflight to compare with";
        TreeSet<String> metrics = new TreeSet<>(before.keySet());
        metrics.addAll(after.keySet());
        List<String> differences = new ArrayList<>();
        for (String metric : metrics)
            if (!Objects.equals(before.get(metric), after.get(metric)))
                differences.add(metric + " " + before.get(metric) + " -> " + after.get(metric));
        return differences.isEmpty() ? null : String.join(", ", differences);
    }

    static String valueOf(List<GridRow> grid, String step, String metric) {
        if (grid != null)
            for (GridRow row : grid)
                if (step.equals(row.step()) && metric.equals(row.metric()))
                    return row.value();
        return null;
    }

    // ===== Logging =================================================================================================

    /** The run's one line. Counts only, by person id; the ledger holds the rest. */
    private static void logRun(Run run, Throwable unrecorded) {
        long millis = (System.nanoTime() - run.startNanos) / 1_000_000;
        StringBuilder line = new StringBuilder(LOG_PREFIX).append("Run ").append(run.id)
            .append(" (").append(run.request.commit() ? MODE_COMMIT : MODE_PREVIEW).append(") of event ").append(run.eventId())
            .append(" by person ").append(run.request.personId()).append(": ")
            .append(run.status == null ? "ended unrecorded" : run.status)
            .append(" in ").append(millis / 1000).append('.').append(millis % 1000 / 100).append(" s");
        if (run.errorKey != null)
            line.append(" (").append(run.errorKey).append(run.sqlState != null ? ", SQLSTATE " + run.sqlState : "").append(')');
        if (run.grid != null) {
            line.append(" — bookings ").append(valueOf(run.grid, "event", "bookings"));
            long updated = 0;
            for (GridRow row : run.grid)
                if ("updated".equals(row.step()) && row.value() != null && row.value().matches("[0-9]+"))
                    updated += Long.parseLong(row.value());
            line.append(", values replaced ").append(updated)
                .append(", left unerased ").append(valueOf(run.grid, "verify", "doc_left"));
        }
        if (run.markers >= 0)
            line.append(", markers ").append(run.markers);
        if (run.request.commit() && STATUS_SUCCEEDED.equals(run.status))
            line.append(", state ").append(run.request.event().state()).append(" -> ").append(run.stateAfter);
        if (unrecorded != null)
            // The ledger write itself failed (the connection is gone): the row stays 'running' until the next
            // start or read marks it aborted, since its connection name is gone from pg_stat_activity
            line.append(" — NOT RECORDED in the ledger (").append(sqlStateOf(unrecorded)).append(')');
        Console.log(line.toString());
    }

    // ===== Helpers =================================================================================================

    /** A refusal the caller can act on, in the "[Key] text" framing the React side maps; logged by person id. */
    private static <T> Future<T> refusal(String key, String text, Integer eventId, String mode, Integer personId) {
        Console.log(LOG_PREFIX + "Start of a " + (MODE_COMMIT.equals(mode) ? MODE_COMMIT : MODE_PREVIEW) + " of event " + eventId
                    + " by person " + personId + " refused: " + key);
        return Future.failedFuture("[%s] %s".formatted(key, text));
    }

    /** A raw read as the server — the caller's state removed, so nothing downstream attributes it to them. */
    static Future<QueryResult> query(String sql, Object... parameters) {
        return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters)
            .build()));
    }

    /** As EventDuplication.asServer: the statement is the server's, not the caller's. Read on the calling thread. */
    static <T> T asServer(Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
    }

    private static LocalDate date(Object text) {
        try {
            return text instanceof String s ? LocalDate.parse(s) : null;
        } catch (DateTimeParseException e) {
            return null; // no date the rules can reason about: refusalFor() refuses on it (end_date) or treats it as unreachable (audio)
        }
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String firstString(RowSet<Row> rows) {
        return rows.size() == 0 ? null : rows.iterator().next().getString(0);
    }

    private static String sqlStateOf(Throwable cause) {
        return cause instanceof DatabaseException db ? db.getSqlState() : cause == null ? null : cause.getClass().getSimpleName();
    }

    /** SQLSTATE and the database's primary message — never its DETAIL, which can quote a row's values. */
    private static String errorText(Throwable cause) {
        if (cause instanceof PgException pg)
            return pg.getSqlState() + " " + pg.getErrorMessage();
        if (cause instanceof DatabaseException db)
            return db.getSqlState() + " " + db.getMessage();
        return cause == null ? null : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }
}

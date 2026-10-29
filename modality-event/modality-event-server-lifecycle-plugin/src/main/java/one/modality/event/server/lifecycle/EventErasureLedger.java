package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading {@code event_erasure_run} (V0146) for the back office: one run in full, or every event's latest run
 * and last successful commit.
 *
 * <p>The table is not in the domain model — no DQL reaches it — so these two reads, behind
 * {@link EventErasureAccess}, are the only way anyone outside the database sees it. Both answer JSON text, in
 * the shapes of the shared contract (RunJson and RunsJson), written by {@link EventErasureJson}. RunJson leaves
 * out two columns the screen has no use for: who started the run (a person id) and the error text (SQLSTATE and
 * the database's message). Both stay in the ledger, and the log carries the person id and the SQLSTATE; the screen
 * explains a failure from its key. What is not read cannot be served, so RUN_SQL does not select them. The grid is the
 * {@code jsonb} column's own text, which Postgres guarantees to be valid JSON, so it is passed through rather than
 * parsed and rebuilt — less the rows naming the database host and the database ({@link #SERVED_GRID}). Instants are formatted by the database as ISO-8601 UTC ({@code ...T...Z}), which the client
 * parses with {@code Temporal.Instant.from}; the duration is computed there too.
 *
 * <p><b>Stale runs are settled before every read.</b> A run whose server died mid-way would otherwise read as
 * running forever and keep the screen polling. The rule — {@link #STALE_RUNS_SQL}, the same one a start applies —
 * marks a running row aborted when no connection named after it ({@code kbs-erasure-<id>}) is left in
 * pg_stat_activity. A connection that still exists means the run is still going, on whichever server, so the
 * rule never aborts a live run. It is ASKED first, read-only ({@link #STALE_RUNS_EXIST_SQL}), and the UPDATE is
 * submitted only when there is something to settle, which is only ever after a crash: every successful submit
 * pulses the query-push service, and a raw statement names no data scope, so an UPDATE on every poll would have
 * every live push query on the server re-run every few seconds for as long as a run is open. A failure of either
 * is logged and does not stop the read.
 *
 * <p>Everything runs as the server, like the runner's reads, and every statement is a constant with the run id
 * bound as a parameter.
 *
 * @author Claude Code
 */
final class EventErasureLedger {

    private EventErasureLedger() {
    }

    private static final String LOG_PREFIX = "[event-erasure] ";

    /**
     * Marks aborted every running row whose connection is gone. {@code like 'kbs-erasure-%'} keeps NULL names out
     * of the list, so NOT IN is safe; an empty list aborts every running row, which is right — no removal
     * connection exists anywhere.
     */
    private static final String STALE_RUN_PREDICATE =
        "status = 'running' and coalesce(app_name, '') not in"
        + " (select application_name from pg_stat_activity where application_name like '"
        + EventErasureRunner.APPLICATION_NAME_PREFIX + "%')";

    static final String STALE_RUNS_SQL =
        "update event_erasure_run set status = 'aborted', finished_at = now(), error_key = 'EventErasureAbortedError'"
        + " where " + STALE_RUN_PREDICATE;

    /** The same rule, asked: is there anything to settle? (See the class comment for why reads ask first.) */
    static final String STALE_RUNS_EXIST_SQL =
        "select exists (select 1 from event_erasure_run where " + STALE_RUN_PREDICATE + ")";

    private static final String ISO_UTC = "'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'";

    private static String instant(String column) {
        return "to_char(" + column + " at time zone 'UTC', " + ISO_UTC + ")";
    }

    /**
     * The grid as served: the column's own jsonb text, less the two rows that name the database host
     * ({@code where/server}, its private address) and the database ({@code where/database}). The screen reads
     * neither, and the ledger keeps both for the record.
     */
    private static final String SERVED_GRID =
        "case when r.grid is null then null else coalesce((select jsonb_agg(g order by o)"
        + " from jsonb_array_elements(r.grid) with ordinality as a(g, o)"
        + " where not (g->>'step' = 'where' and g->>'metric' in ('server', 'database'))), '[]'::jsonb)::text end";

    private static final String RUN_SQL =
        "select r.id, r.event_id, r.mode, r.status, r.preview_run_id, "
        + instant("r.started_at") + ", " + instant("r.finished_at") + ","
        + " (extract(epoch from r.finished_at - r.started_at) * 1000)::bigint,"
        + " " + SERVED_GRID + ", r.error_key, r.state_before, r.state_after"
        + " from event_erasure_run r where r.id = $1";

    /**
     * Per event: the latest run of any kind, the latest SUCCESSFUL commit (or none), and whether the event's bookings
     * changed after that commit — any history row of its bookings dated after the commit STARTED, other than the
     * commit's own 'gdpr-retention' markers (IS DISTINCT FROM, so a history row with no username counts as a change).
     * A booking created or edited since the removal writes history, so the page can show the event as due again.
     * Started, not finished: the run's snapshot is taken just after its ledger row is written, and a history row is
     * dated at its writer's transaction start, so a booking written during the minutes the run takes is not in the
     * snapshot, was not erased, and is dated before finished_at. Comparing with finished_at would show such an
     * event as removed while it still holds personal data; started_at at worst re-flags a write the run did cover,
     * and running the removal again re-verifies it.
     *
     * <p>Cost: the probe is a CASE, so it runs only for an event that HAS a succeeded commit, once per event (outside
     * the LIMIT 1 subquery, never once per commit row), and it is an EXISTS: it stops at the first changed row. It
     * reaches history through document(event_id) and history(document_id), or through history(date) — all indexed —
     * so an unchanged event costs one pass over its own bookings' history, and the statement stays one round trip.
     */
    private static final String RUNS_SQL =
        "select l.event_id,"
        + " l.id, l.mode, l.status, " + instant("l.started_at") + ", " + instant("l.finished_at") + ", l.error_key,"
        + " c.id, c.mode, c.status, " + instant("c.started_at") + ", " + instant("c.finished_at") + ", c.error_key,"
        + " case when c.id is null then false else exists (select 1 from history h join document d on d.id = h.document_id"
        + "        where d.event_id = l.event_id and h.date > c.started_at"
        + "        and h.username is distinct from 'gdpr-retention') end"
        + " from (select distinct on (event_id) id, event_id, mode, status, started_at, finished_at, error_key"
        + "        from event_erasure_run order by event_id, id desc) l"
        + " left join lateral (select id, mode, status, started_at, finished_at, error_key from event_erasure_run c"
        + "        where c.event_id = l.event_id and c.mode = 'commit' and c.status = 'succeeded'"
        + "        order by c.id desc limit 1) c on true"
        + " order by l.event_id";

    /**
     * {@link #RUNS_SQL}'s {@code changedSince} probe, for an event and a commit's start named by other columns: any
     * history row of the event's bookings dated after the commit STARTED, other than the commit's own
     * 'gdpr-retention' markers. Written out character for character as RUNS_SQL has it — which RUNS_SQL is left
     * untouched by — so the two reads answer the same question; EventRemovalDatesCheck fails the day they differ.
     *
     * @param eventId          the column holding the event id (a constant, never input)
     * @param commitStartedAt  the column holding the commit's {@code started_at} (a constant, never input)
     */
    static String changedSinceProbe(String eventId, String commitStartedAt) {
        return "exists (select 1 from history h join document d on d.id = h.document_id"
               + "        where d.event_id = " + eventId + " and h.date > " + commitStartedAt
               + "        and h.username is distinct from 'gdpr-retention')";
    }

    /**
     * For Registration › Event status ({@link EventRemovalDatesEndpoint}): per event of one organisation ({@code $1})
     * that has a SUCCEEDED commit, its last one (the highest id, as in {@link #RUNS_SQL}'s lateral):
     * <ol>
     *   <li>the event id;</li>
     *   <li>the date that commit finished, as a Europe/London calendar date — the zone the runner judges the retention
     *       line in (EventErasureRunner.BUSINESS_ZONE), and the one the lifecycle page's {@code removedOnOf} uses. A
     *       succeeded commit always has a {@code finished_at} (V0146's {@code event_erasure_run_finished_chk});</li>
     *   <li>{@code changedSince}: {@link #changedSinceProbe}, the test RUNS_SQL serves as {@code lastCommit.changedSince}
     *       — so a page outside the super administrators' can still tell an event whose bookings changed after the
     *       removal (due again) from one that is done.</li>
     * </ol>
     *
     * <p>Nothing else: no person id, no error text, no grid — the audience is wider than the super administrators the
     * other reads serve. Joined to {@code event} for the organisation, so an event deleted outright (the ledger has no
     * foreign key, on purpose) drops out: it is not on the page either.
     *
     * <p>Cost, as RUNS_SQL's: the last commit is picked first (DISTINCT ON over the organisation's commits), and the
     * probe then runs once per event, outside it, stopping at the first changed row.
     */
    static final String REMOVAL_DATES_SQL =
        "select c.event_id, to_char(c.finished_at at time zone 'Europe/London', 'YYYY-MM-DD'), "
        + changedSinceProbe("c.event_id", "c.started_at")
        + " from (select distinct on (r.event_id) r.id, r.event_id, r.started_at, r.finished_at"
        + "        from event_erasure_run r join event e on e.id = r.event_id"
        + "        where e.organization_id = $1 and r.mode = 'commit' and r.status = 'succeeded'"
        + "        order by r.event_id, r.id desc) c"
        + " order by c.event_id";

    /** What the caller reads when the removal dates cannot be served: the database's words stay in the log. */
    static final String REMOVAL_DATES_FAILED = "[EventRemovalDatesError] Removal dates could not be read";

    /** What a removal date must look like to be served; anything else is a row skipped, never text passed on. */
    private static final java.util.regex.Pattern ISO_DATE = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** RemovalDatesJson for one organisation — see {@link EventRemovalDatesEndpoint}. Called once the caller is allowed. */
    static Future<String> removalDatesJson(Integer organizationId) {
        Future<QueryResult> rows;
        try {
            rows = EventErasureRunner.query(REMOVAL_DATES_SQL, organizationId);
        } catch (RuntimeException e) {
            rows = Future.failedFuture(e);
        }
        return removalDatesReply(rows);
    }

    /** The reply for the rows a removal-dates read answered — or, when it failed, {@link #REMOVAL_DATES_FAILED}. */
    static Future<String> removalDatesReply(Future<QueryResult> rows) {
        return rows.map(EventErasureLedger::removalDatesJson)
            .recover(cause -> {
                Console.log(LOG_PREFIX + "Reading removal dates failed: " + (cause == null ? null : cause.getMessage()));
                return Future.failedFuture(REMOVAL_DATES_FAILED);
            });
    }

    /** {@code {"retentionMonths":n,"removals":[{"eventId":n,"removedOn":"YYYY-MM-DD","changedSince":bool}]}}. */
    static String removalDatesJson(QueryResult r) {
        List<String> removals = new ArrayList<>();
        for (int row = 0; r != null && row < r.getRowCount(); row++) {
            Number eventId = number(r.getValue(row, 0));
            String removedOn = text(r.getValue(row, 1));
            Object changedSince = r.getValue(row, 2);
            if (eventId == null || removedOn == null || !ISO_DATE.matcher(removedOn).matches())
                continue;
            removals.add(new EventErasureJson.ObjectWriter()
                .number("eventId", eventId)
                .string("removedOn", removedOn)
                .bool("changedSince", Boolean.TRUE.equals(changedSince))
                .toJson());
        }
        return new EventErasureJson.ObjectWriter()
            .number("retentionMonths", EventErasureConfig.RETENTION_MONTHS)
            .raw("removals", EventErasureJson.array(removals))
            .toJson();
    }

    /** RunJson for one run, or {@code EventErasureNotFoundError} when there is no such run. */
    static Future<String> runJson(Integer runId) {
        if (runId == null)
            return Future.failedFuture("[%s] No such removal run".formatted(EventErasureRunner.NOT_FOUND_KEY));
        return settleStaleRuns()
            .compose(ignored -> EventErasureRunner.query(RUN_SQL, runId))
            .compose(result -> result == null || result.getRowCount() == 0
                ? Future.failedFuture("[%s] No such removal run".formatted(EventErasureRunner.NOT_FOUND_KEY))
                : Future.succeededFuture(runJson(result)))
            .recover(cause -> readFailure("run " + runId, cause));
    }

    /**
     * RunsJson: the commit switch, the retention period, and a summary per event that has any run — its last
     * successful commit carrying {@code changedSince}, true once its bookings changed after that commit.
     */
    static Future<String> runsJson() {
        return settleStaleRuns()
            .compose(ignored -> EventErasureRunner.query(RUNS_SQL))
            .map(EventErasureLedger::runsJson)
            .recover(cause -> readFailure("runs", cause));
    }

    /**
     * {id, eventId, mode, status, previewRunId, startedAt, finishedAt, durationMs, grid, errorKey, stateBefore,
     * stateAfter} — never the requesting person's id nor the error text (see the class comment).
     */
    private static String runJson(QueryResult r) {
        return new EventErasureJson.ObjectWriter()
            .number("id", number(r.getValue(0, 0)))
            .number("eventId", number(r.getValue(0, 1)))
            .string("mode", text(r.getValue(0, 2)))
            .string("status", text(r.getValue(0, 3)))
            .number("previewRunId", number(r.getValue(0, 4)))
            .string("startedAt", text(r.getValue(0, 5)))
            .string("finishedAt", text(r.getValue(0, 6)))
            .number("durationMs", number(r.getValue(0, 7)))
            .raw("grid", text(r.getValue(0, 8))) // jsonb text: valid JSON by construction
            .string("errorKey", text(r.getValue(0, 9)))
            .string("stateBefore", text(r.getValue(0, 10)))
            .string("stateAfter", text(r.getValue(0, 11)))
            .toJson();
    }

    private static String runsJson(QueryResult r) {
        List<String> runs = new ArrayList<>();
        for (int row = 0; row < r.getRowCount(); row++)
            runs.add(new EventErasureJson.ObjectWriter()
                .number("eventId", number(r.getValue(row, 0)))
                .raw("latest", summaryJson(r, row, 1))
                .raw("lastCommit", lastCommitJson(r, row))
                .toJson());
        return new EventErasureJson.ObjectWriter()
            .bool("commitEnabled", EventErasureConfig.isCommitEnabled())
            .number("retentionMonths", EventErasureConfig.RETENTION_MONTHS)
            .raw("runs", EventErasureJson.array(runs))
            .toJson();
    }

    /** {id, mode, status, startedAt, finishedAt, errorKey} from six columns starting at {@code first}, or null. */
    private static String summaryJson(QueryResult r, int row, int first) {
        EventErasureJson.ObjectWriter summary = summaryWriter(r, row, first);
        return summary == null ? null : summary.toJson();
    }

    /** The last successful commit's summary plus {@code changedSince} (RUNS_SQL's last column), or null. */
    private static String lastCommitJson(QueryResult r, int row) {
        EventErasureJson.ObjectWriter summary = summaryWriter(r, row, 7);
        return summary == null ? null : summary.bool("changedSince", Boolean.TRUE.equals(r.getValue(row, 13))).toJson();
    }

    private static EventErasureJson.ObjectWriter summaryWriter(QueryResult r, int row, int first) {
        if (r.getValue(row, first) == null) // no successful commit (the left join found nothing)
            return null;
        return new EventErasureJson.ObjectWriter()
            .number("id", number(r.getValue(row, first)))
            .string("mode", text(r.getValue(row, first + 1)))
            .string("status", text(r.getValue(row, first + 2)))
            .string("startedAt", text(r.getValue(row, first + 3)))
            .string("finishedAt", text(r.getValue(row, first + 4)))
            .string("errorKey", text(r.getValue(row, first + 5)));
    }

    /** The stale-run rule, as the server, in autocommit — submitted only when there is a stale run. Never fails the read. */
    private static Future<Void> settleStaleRuns() {
        return EventErasureRunner.query(STALE_RUNS_EXIST_SQL)
            .compose(stale -> stale == null || stale.getRowCount() == 0 || !Boolean.TRUE.equals(stale.getValue(0, 0))
                ? Future.<Void>succeededFuture()
                : EventErasureRunner.asServer(() -> SubmitService.executeSubmit(new SubmitArgumentBuilder()
                        .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                        .setStatement(STALE_RUNS_SQL)
                        .build()))
                    .<Void>map(ignored -> null))
            .recover(cause -> {
                Console.log(LOG_PREFIX + "Could not settle stale removal runs: " + (cause == null ? null : cause.getMessage()));
                return Future.succeededFuture();
            });
    }

    /**
     * A read that failed: a refusal already framed passes through as it is; anything else is logged whole and
     * answered narrowly, so a failure never becomes a description of the schema.
     */
    private static Future<String> readFailure(String what, Throwable cause) {
        String message = cause == null ? null : cause.getMessage();
        if (message != null && message.startsWith("[" + EventErasureRunner.NOT_FOUND_KEY + "]"))
            return Future.failedFuture(message);
        Console.log(LOG_PREFIX + "Reading removal " + what + " failed: " + message);
        return Future.failedFuture("[%s] The removal runs could not be read".formatted(EventErasureRunner.SQL_KEY));
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}

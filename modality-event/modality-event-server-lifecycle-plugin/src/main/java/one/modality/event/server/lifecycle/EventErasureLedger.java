package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
        + " " + SERVED_GRID + ", r.error_key, r.state_before, r.state_after,"
        + " r.kind, to_char(r.departed_before, 'YYYY-MM-DD'), r.stage"
        + " from event_erasure_run r where r.id = $1";

    /**
     * Per event: the latest run of any kind (with its kind last), the latest SUCCESSFUL commit of the WHOLE event (or
     * none — a continuous event's departed-stays commits never make the event "removed"), and whether the event's bookings
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
        + "        and h.username is distinct from 'gdpr-retention') end,"
        + " l.kind, l.stage"
        + " from (select distinct on (event_id) id, event_id, mode, status, started_at, finished_at, error_key, kind, stage"
        + "        from event_erasure_run order by event_id, id desc) l"
        + " left join lateral (select id, mode, status, started_at, finished_at, error_key from event_erasure_run c"
        + "        where c.event_id = l.event_id and c.mode = 'commit' and c.status = 'succeeded' and c.kind = 'event'"
        + "        and c.stage = 'finalise'"
        + "        order by c.id desc limit 1) c on true"
        + " order by l.event_id";

    /**
     * Retention stage 2 (V0170), per event that may be anonymised now: a whole event finalised by stage 1 (FINALISED or
     * ARCHIVED, or a KBS2 event with a succeeded whole-event removal) whose archive line — the later of its end and the
     * recordings closing, plus its archive period — has passed; or a continuous event with its own archive period. Then
     * how many of its bookings are DUE: not archived yet and owing nothing either way (the routine leaves those out), for
     * a continuous event among the stays departed before today minus the period. Counts only. Cached with
     * {@link #DEPARTED_SQL} ({@link #departedCounts}).
     */
    static final String ARCHIVE_DUE_SQL =
        "select y.id, y.months, case when y.ongoing then to_char(y.cutoff, 'YYYY-MM-DD') end,"
        + " case when y.ongoing then (select count(*) from event_departed_bookings(y.id, y.cutoff) b join document d on d.id = b.document_id"
        + "        where d.archived_date is null and d.price_net = d.price_deposit)"
        + " else (select count(*) from document d where d.event_id = y.id and d.archived_date is null and d.price_net = d.price_deposit) end"
        + " from (select x.id, x.ongoing, x.months, (x.today - make_interval(months => x.months))::date as cutoff"
        + "        from (select e.id, coalesce(t.ongoing, false) as ongoing, e.state::text as state, e.end_date, e.audio_closing_date,"
        + "               " + RetentionPolicy.monthsSql(RetentionPolicy.STAGE_ARCHIVE) + " as months,"
        + "               (now() at time zone 'Europe/London')::date as today"
        + "               from event e left join event_type t on t.id = e.type_id) x"
        + "        where x.months is not null"
        + "        and (x.ongoing or ((x.state in ('FINALISED', 'ARCHIVED') or (x.state is null and exists (select 1 from event_erasure_run r"
        + "             where r.event_id = x.id and r.stage = 'finalise' and r.kind = 'event' and r.mode = 'commit' and r.status = 'succeeded')))"
        // an end date is needed, and passed: greatest() skips a NULL, and the runner refuses an event with none
        + "             and x.end_date < x.today"
        + "             and (greatest(x.end_date, x.audio_closing_date::date) + make_interval(months => x.months))::date < x.today))) y"
        + " order by y.id";

    /**
     * Per continuous event whose own finalise period is set (V0169): the period, today's departure cutoff (today in
     * Europe/London minus the period — what {@link EventErasureRunner#departedBefore} computes) and how many bookings
     * departed before it still have no removal marker, i.e. are DUE. The departure rule is the database's
     * ({@code event_departed_bookings}); a departed commit writes the marker on exactly the bookings it reached, so a
     * due count falls to 0 once they are done. Counts only — no booking id, no name. One pass per continuous event over
     * its own bookings (document(event_id), document_line(document_id), attendance(document_line_id) and
     * history(document_id) are all indexed); continuous events are few — but they hold the largest events (thousands of
     * bookings), and the runs read is polled every minute by every open DPO page, so the answer is cached
     * ({@link #departedCounts}).
     */
    static final String DEPARTED_SQL =
        "select y.id, y.months, to_char(y.cutoff, 'YYYY-MM-DD'),"
        + " (select count(*) from event_departed_bookings(y.id, y.cutoff) b"
        + "   where not exists (select 1 from history h where h.document_id = b.document_id and h.username = 'gdpr-retention'))"
        + " from (select x.id, x.months, ((now() at time zone 'Europe/London')::date - make_interval(months => x.months))::date as cutoff"
        + "        from (select e.id, " + RetentionPolicy.monthsSql(RetentionPolicy.STAGE_FINALISE) + " as months"
        + "               from event e join event_type t on t.id = e.type_id where t.ongoing) x"
        + "        where x.months is not null) y"
        + " order by y.id";

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
     * <p>Nothing else: no person id, no error text, no grid — the audience is wider than the Data Protection Officers the
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
        + "        where e.organization_id = $1 and r.mode = 'commit' and r.status = 'succeeded' and r.kind = 'event' and r.stage = 'finalise'"
        + "        order by r.event_id, r.id desc) c"
        + " order by c.event_id";

    /** How long a read of {@link #DEPARTED_SQL} is served again: the counts move once a day, or when the DPO acts. */
    static final long DEPARTED_CACHE_MS = 10 * 60_000L;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/London");

    /** The last departed and archive-due reads, the London day they were made for, and when. */
    private record DepartedRead(String day, long readAtMillis, QueryResult rows, QueryResult archive) {
    }

    private static final AtomicReference<DepartedRead> DEPARTED_CACHE = new AtomicReference<>();

    /**
     * {@link #DEPARTED_SQL}'s rows, read again at most every {@link #DEPARTED_CACHE_MS}, and at once on a new London
     * day (the cutoff moves) or after {@link #forgetDepartedCounts}. Per server: another server may lag by that much.
     */
    static Future<QueryResult[]> departedCounts() {
        String today = LocalDate.now(BUSINESS_ZONE).toString();
        DepartedRead read = DEPARTED_CACHE.get();
        if (read != null && read.day().equals(today) && System.currentTimeMillis() - read.readAtMillis() < DEPARTED_CACHE_MS)
            return Future.succeededFuture(new QueryResult[] { read.rows(), read.archive() });
        // Independent reads: both are sent before either is awaited
        Future<QueryResult> departedRead = EventErasureRunner.query(DEPARTED_SQL);
        Future<QueryResult> archiveRead = EventErasureRunner.query(ARCHIVE_DUE_SQL);
        return departedRead.compose(rows -> archiveRead.map(archive -> {
            DEPARTED_CACHE.set(new DepartedRead(today, System.currentTimeMillis(), rows, archive));
            return new QueryResult[] { rows, archive };
        }));
    }

    /** Drops the cached departed counts: a period changed, or a departed removal ended. */
    static void forgetDepartedCounts() {
        DEPARTED_CACHE.set(null);
    }

    /** What the caller reads when the removal dates cannot be served: the database's words stay in the log. */
    static final String REMOVAL_DATES_FAILED = "[EventRemovalDatesError] Removal dates could not be read";

    /** What a removal date must look like to be served; anything else is a row skipped, never text passed on. */
    private static final java.util.regex.Pattern ISO_DATE = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** RemovalDatesJson for one organisation — see {@link EventRemovalDatesEndpoint}. Called once the caller is allowed. */
    static Future<String> removalDatesJson(Integer organizationId) {
        Future<QueryResult> rows;
        Future<QueryResult> policy;
        try {
            rows = EventErasureRunner.query(REMOVAL_DATES_SQL, organizationId);
            // Only what this organisation's events resolve against: the caller reaches this organisation alone
            policy = EventErasureRunner.query(RetentionPolicy.ORGANIZATION_SQL, organizationId);
        } catch (RuntimeException e) {
            rows = policy = Future.failedFuture(e);
        }
        Future<QueryResult> policyRows = policy;
        return removalDatesReply(rows.compose(dates -> policyRows.map(p -> new QueryResult[] { dates, p })));
    }

    /** The reply for the rows a removal-dates read answered — or, when it failed, {@link #REMOVAL_DATES_FAILED}. */
    static Future<String> removalDatesReply(Future<QueryResult[]> rows) {
        return rows.map(both -> removalDatesJson(both[0], both[1]))
            .recover(cause -> {
                Console.log(LOG_PREFIX + "Reading removal dates failed: " + (cause == null ? null : cause.getMessage()));
                return Future.failedFuture(REMOVAL_DATES_FAILED);
            });
    }

    /**
     * {@code {"retentionMonths":n|null,"policy":PolicyJson,"removals":[{"eventId":n,"removedOn":"YYYY-MM-DD","changedSince":bool}]}}:
     * retentionMonths is the confirmed default of the finalise stage (null while the DPO has not confirmed one), kept
     * for a page that has not learnt the per-organisation policy yet; {@code policy} is {@link RetentionPolicy#policyJson}.
     */
    static String removalDatesJson(QueryResult r, QueryResult policy) {
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
            .number("retentionMonths", defaultMonths(policy, RetentionPolicy.STAGE_FINALISE))
            .raw("policy", RetentionPolicy.policyJson(policy))
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
     * RunsJson: the commit switch, the retention policy (per stage, organisation and event), and a summary per event that has any run — its last
     * successful commit carrying {@code changedSince}, true once its bookings changed after that commit.
     */
    static Future<String> runsJson(EventErasureAccess.Caller caller) {
        return settleStaleRuns()
            .compose(ignored -> EventErasureRunner.query(RUNS_SQL))
            .compose(runs -> EventErasureRunner.query(RetentionPolicy.CURRENT_SQL)
                .compose(policy -> departedCounts().compose(counts -> ownEvents(caller, runs, counts[0], counts[1])
                    .map(own -> runsJson(runs, policy, counts[0], counts[1], own)))))
            .recover(cause -> readFailure("runs", cause));
    }

    /**
     * The events of these rows (each read's first column is the event id) whose organization the caller holds
     * DataProtection in: the runs, departed stays and archive rows of other organizations' events are left out of the
     * caller's list (admin-tiers plan, 2026-11-18). RUNS_SQL itself is untouched — EventRemovalDatesCheck pins it —
     * so the filter is applied to its rows. Anything that cannot be placed is left out.
     */
    static Future<java.util.function.Predicate<Number>> ownEvents(EventErasureAccess.Caller caller, QueryResult... reads) {
        List<Integer> eventIds = new ArrayList<>();
        for (QueryResult r : reads)
            for (int row = 0; r != null && row < r.getRowCount(); row++)
                eventIds.add(dev.webfx.platform.util.Numbers.toInteger(r.getValue(row, 0)));
        return EventOrganizations.ofEvents(eventIds)
            .compose(organizationByEvent -> EventErasureAccess.organizationsHeld(caller, organizationByEvent.values())
                .map(held -> EventOrganizations.eventsIn(organizationByEvent, held)));
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
            .string("kind", text(r.getValue(0, 12)))
            .string("departedBefore", text(r.getValue(0, 13)))
            .string("stage", text(r.getValue(0, 14)))
            .toJson();
    }

    static String runsJson(QueryResult r, QueryResult policy, QueryResult departed) {
        return runsJson(r, policy, departed, null);
    }

    static String runsJson(QueryResult r, QueryResult policy, QueryResult departed, QueryResult archive) {
        return runsJson(r, policy, departed, archive, eventId -> true);
    }

    /** {@link #runsJson(QueryResult, QueryResult, QueryResult, QueryResult)}, keeping only the rows of {@code own} events. */
    static String runsJson(QueryResult r, QueryResult policy, QueryResult departed, QueryResult archive,
                           java.util.function.Predicate<Number> own) {
        List<String> runs = new ArrayList<>();
        for (int row = 0; r != null && row < r.getRowCount(); row++) {
            if (!own.test(number(r.getValue(row, 0))))
                continue;
            EventErasureJson.ObjectWriter latest = summaryWriter(r, row, 1);
            runs.add(new EventErasureJson.ObjectWriter()
                .number("eventId", number(r.getValue(row, 0)))
                .raw("latest", latest == null ? null : latest.string("kind", text(r.getValue(row, 14)))
                    .string("stage", text(r.getValue(row, 15))).toJson())
                .raw("lastCommit", lastCommitJson(r, row))
                .toJson());
        }
        return new EventErasureJson.ObjectWriter()
            .bool("commitEnabled", EventErasureConfig.isCommitEnabled())
            .bool("archiveCommitEnabled", EventErasureConfig.isArchiveCommitEnabled())
            .number("retentionMonths", defaultMonths(policy, RetentionPolicy.STAGE_FINALISE))
            .raw("policy", RetentionPolicy.policyJson(policy))
            .raw("runs", EventErasureJson.array(runs))
            .raw("departed", departedJson(departed, own))
            .raw("archive", archiveJson(archive, own))
            .toJson();
    }

    /**
     * {@code [{eventId, months, departedBefore|null, due}]} from {@link #ARCHIVE_DUE_SQL}'s rows: departedBefore only for
     * a continuous event; a row with an unreadable cutoff is skipped.
     */
    static String archiveJson(QueryResult r) {
        return archiveJson(r, eventId -> true);
    }

    /** {@link #archiveJson(QueryResult)}, keeping only the rows of {@code own} events. */
    static String archiveJson(QueryResult r, java.util.function.Predicate<Number> own) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; r != null && row < r.getRowCount(); row++) {
            String cutoff = text(r.getValue(row, 2));
            if (number(r.getValue(row, 0)) == null || (cutoff != null && !ISO_DATE.matcher(cutoff).matches())
                || !own.test(number(r.getValue(row, 0))))
                continue;
            rows.add(new EventErasureJson.ObjectWriter()
                .number("eventId", number(r.getValue(row, 0)))
                .number("months", number(r.getValue(row, 1)))
                .string("departedBefore", cutoff)
                .number("due", number(r.getValue(row, 3)))
                .toJson());
        }
        return EventErasureJson.array(rows);
    }

    /** {@code [{eventId, months, departedBefore, due}]} from {@link #DEPARTED_SQL}'s rows; a row with an unreadable cutoff is skipped. */
    static String departedJson(QueryResult r) {
        return departedJson(r, eventId -> true);
    }

    /** {@link #departedJson(QueryResult)}, keeping only the rows of {@code own} events. */
    static String departedJson(QueryResult r, java.util.function.Predicate<Number> own) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; r != null && row < r.getRowCount(); row++) {
            String cutoff = text(r.getValue(row, 2));
            if (number(r.getValue(row, 0)) == null || cutoff == null || !ISO_DATE.matcher(cutoff).matches()
                || !own.test(number(r.getValue(row, 0))))
                continue;
            rows.add(new EventErasureJson.ObjectWriter()
                .number("eventId", number(r.getValue(row, 0)))
                .number("months", number(r.getValue(row, 1)))
                .string("departedBefore", cutoff)
                .number("due", number(r.getValue(row, 3)))
                .toJson());
        }
        return EventErasureJson.array(rows);
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

    /** The confirmed default of one stage in {@link RetentionPolicy#CURRENT_SQL}'s rows, or null when there is none. */
    static Number defaultMonths(QueryResult policy, String stage) {
        for (int row = 0; policy != null && row < policy.getRowCount(); row++)
            if (stage.equals(policy.getValue(row, 0)) && RetentionPolicy.SCOPE_DEFAULT.equals(policy.getValue(row, 1)))
                return number(policy.getValue(row, 4));
        return null;
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}

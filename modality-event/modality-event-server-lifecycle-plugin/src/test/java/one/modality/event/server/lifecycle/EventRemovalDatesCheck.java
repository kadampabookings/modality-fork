package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Check for {@link EventRemovalDatesEndpoint} and {@link EventErasureLedger#REMOVAL_DATES_SQL} — the removal dates
 * Registration › Event status reads.
 *
 * <p>No test framework (see MoneyTransferVerificationCheck); main() exits non-zero on failure. The guard's database
 * half (RouteGrantMembership) is not covered here — it needs a database, and it is the same guard every route-scoped
 * endpoint uses; a guard that fails, throws or refuses is (through the endpoint's RouteGuard seam). What is covered:
 * nobody gets a read without passing the fence, the argument, the statement's shape — including that changedSince is
 * the very test the super administrators' runs read serves — the reply's keys, and a failure's text never reaching
 * the caller. The statement itself is rehearsed on a disposable postgres:17 (docs/design/event-booking-state-endpoint.md
 * in the aggregate repo).
 */
public class EventRemovalDatesCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    private static final String REFUSED = "This operation is not available to you";

    private static boolean refusedWithoutReading(Object state, Object argument) {
        AtomicInteger reads = new AtomicInteger();
        Function<Integer, Future<String>> reader = organizationId -> {
            reads.incrementAndGet();
            return Future.succeededFuture("{}");
        };
        // The guard reads the caller from the thread-local, as it does on the bus thread
        Future<String> answer = ThreadLocalStateHolder.runWithState(state,
            () -> EventRemovalDatesEndpoint.removalDates(argument, reader));
        return answer.failed() && REFUSED.equals(answer.cause().getMessage()) && reads.get() == 0;
    }

    public static void main(String[] args) {
        System.out.println("EventRemovalDatesCheck");

        // ── Refused before anything is read ──
        ModalityUserPrincipal principal = new ModalityUserPrincipal(456, 7);
        Object anonymous = StateAccessor.createEmptyState();
        Object claimedOnly = StateAccessor.setBackoffice(StateAccessor.createEmptyState(), true);
        Object noFamily = StateAccessor.setVerifiedBackoffice(StateAccessor.setUserId(StateAccessor.createEmptyState(), principal), true);
        Object frontOffice = StateAccessor.setVerifiedBackoffice(StateAccessor.setSessionFamilyId(
            StateAccessor.setUserId(StateAccessor.createEmptyState(), principal), "family-1"), false);
        Object supportView = StateAccessor.setVerifiedBackoffice(StateAccessor.setSessionFamilyId(StateAccessor.setUserId(
            StateAccessor.createEmptyState(), new ModalityUserPrincipal(99, 8, 456)), "family-1"), true);
        Object allowed = StateAccessor.setVerifiedBackoffice(StateAccessor.setSessionFamilyId(
            StateAccessor.setUserId(StateAccessor.createEmptyState(), principal), "family-1"), true);
        check("no state at all is refused, and nothing is read", refusedWithoutReading(null, 5));
        check("an anonymous caller is refused, and nothing is read", refusedWithoutReading(anonymous, 5));
        check("a back office the client only claimed is refused, and nothing is read", refusedWithoutReading(claimedOnly, 5));
        check("a caller on no established session is refused, and nothing is read", refusedWithoutReading(noFamily, 5));
        check("a front-office session is refused, and nothing is read", refusedWithoutReading(frontOffice, 5));
        check("a support view is refused, and nothing is read", refusedWithoutReading(supportView, 5));
        // A malformed organisation has nothing to scope a grant to: refused like a forbidden one, BEFORE the guard
        // looks anything up — even for a caller who would pass the fence.
        for (Object bad : new Object[] { null, 0, -3, 2.5, "x", true, new Object[0], new Object[] { 5, 6 }, new Object[] { "x" } })
            check("an allowed caller naming " + describe(bad) + " is refused, and nothing is read", refusedWithoutReading(allowed, bad));

        // ── The argument ──
        check("a bare id", Integer.valueOf(5).equals(EventRemovalDatesEndpoint.organizationIdOf(5)));
        check("an id in a one-element array", Integer.valueOf(5).equals(EventRemovalDatesEndpoint.organizationIdOf(new Object[] { 5 })));
        check("a Long or a whole Double", Integer.valueOf(5).equals(EventRemovalDatesEndpoint.organizationIdOf(5L))
                                          && Integer.valueOf(5).equals(EventRemovalDatesEndpoint.organizationIdOf(5.0)));
        check("a digit string, bare or in an array", Integer.valueOf(5).equals(EventRemovalDatesEndpoint.organizationIdOf("5"))
                                                     && Integer.valueOf(5).equals(EventRemovalDatesEndpoint.organizationIdOf(new Object[] { "5" })));
        check("an id past int is no id", EventRemovalDatesEndpoint.organizationIdOf(3_000_000_000L) == null);
        check("a padded or signed string is no id", EventRemovalDatesEndpoint.organizationIdOf(" 5") == null
                                                    && EventRemovalDatesEndpoint.organizationIdOf("+5") == null);

        // ── The statement ──
        String sql = EventErasureLedger.REMOVAL_DATES_SQL;
        check("one row per event: its last succeeded commit (highest id), picked before the probe runs",
            sql.contains("from (select distinct on (r.event_id) r.id, r.event_id, r.started_at, r.finished_at")
            && sql.contains("order by r.event_id, r.id desc) c") && sql.endsWith(" order by c.event_id"));
        check("succeeded commits only", sql.contains("r.mode = 'commit' and r.status = 'succeeded'"));
        check("the organisation is bound, and is the event's own", sql.contains("join event e on e.id = r.event_id")
                                                                   && sql.contains("e.organization_id = $1") && !sql.contains("$2"));
        check("the date is the London date the commit finished",
            sql.startsWith("select c.event_id, to_char(c.finished_at at time zone 'Europe/London', 'YYYY-MM-DD'), "));
        String probe = EventErasureLedger.changedSinceProbe("c.event_id", "c.started_at");
        check("changedSince is the probe, asked of the last commit's own start, once per event (outside the subquery)",
            sql.contains(", " + probe + " from (select distinct on"));
        String runsSql = privateStatic(EventErasureLedger.class, "RUNS_SQL");
        check("changedSince is the SAME test the runs read serves as lastCommit.changedSince",
            runsSql != null && runsSql.contains(" case when c.id is null then false else "
                                                + EventErasureLedger.changedSinceProbe("l.event_id", "c.started_at") + " end"));
        check("the probe skips the commit's own markers and counts a history row with no username",
            probe.contains("h.username is distinct from 'gdpr-retention'") && probe.contains("h.date > c.started_at"));
        // The probe compares history.username with the commit's marker and selects nothing but a constant
        check("the probe selects nothing from history: it is an EXISTS of a constant",
            probe.startsWith("exists (select 1 from history h "));
        check("it selects ids, a date and a boolean - nothing about a person, an error or the grid",
            !sql.replace(probe, "").matches("(?s).*(requested_by|person|error|grid|state_|name|app_name|first_|last_|email).*"));

        // ── The reply ──
        QueryResult rows = new QueryResult(3, 3,
            new Object[] { 12, 40, 41, "2026-01-31", "2026-07-02", "not a date", false, true, false }, null);
        String json = EventErasureLedger.removalDatesJson(rows);
        check("the reply is the retention period and one removal per event, in the contract's spelling",
            ("{\"retentionMonths\":3,\"removals\":[{\"eventId\":12,\"removedOn\":\"2026-01-31\",\"changedSince\":false},"
             + "{\"eventId\":40,\"removedOn\":\"2026-07-02\",\"changedSince\":true}]}").equals(json));
        java.util.Set<String> keys = new java.util.TreeSet<>();
        java.util.regex.Matcher key = java.util.regex.Pattern.compile("\"([A-Za-z]+)\":").matcher(json);
        while (key.find())
            keys.add(key.group(1));
        check("the reply carries exactly the contract's keys - no personal data field: " + keys,
            keys.equals(new java.util.TreeSet<>(java.util.List.of("retentionMonths", "removals", "eventId", "removedOn", "changedSince"))));
        check("a null changedSince reads as false, never as text",
            EventErasureLedger.removalDatesJson(new QueryResult(1, 3, new Object[] { 7, "2026-02-01", null }, null))
                .contains("\"changedSince\":false"));
        check("an organisation with no removal answers an empty list",
            "{\"retentionMonths\":3,\"removals\":[]}".equals(EventErasureLedger.removalDatesJson(new QueryResult(0, 3, new Object[0], null))));
        check("retentionMonths is the erasure configuration's", json.startsWith("{\"retentionMonths\":" + EventErasureConfig.RETENTION_MONTHS + ","));
        String dbText = "ERROR: permission denied for table event_erasure_run (host 10.0.3.17)";
        Future<String> failed = EventErasureLedger.removalDatesReply(Future.failedFuture(dbText));
        check("a read that fails answers the contract's failure, its database text not echoed",
            failed.failed() && "[EventRemovalDatesError] Removal dates could not be read".equals(failed.cause().getMessage()));

        // ── A guard that cannot answer is not a yes ──
        // The guard's lookup is the database's; when it fails (or throws) rather than answering, the caller reads the
        // contract's failure — never the database's words — and the read is never started.
        String guardText = "ERROR: relation \"authorization_organization_user_access\" does not exist (host 10.0.3.17)";
        AtomicInteger guardedReads = new AtomicInteger();
        Function<Integer, Future<String>> countingReader = organizationId -> {
            guardedReads.incrementAndGet();
            return Future.succeededFuture("{}");
        };
        Future<String> guardFailed = EventRemovalDatesEndpoint.removalDates(new Object[] { "5" },
            (route, organizationId, work) -> Future.failedFuture(guardText), countingReader);
        check("a grant lookup that fails answers the contract's failure, its text not echoed, and nothing is read",
            guardFailed.failed() && EventErasureLedger.REMOVAL_DATES_FAILED.equals(guardFailed.cause().getMessage())
            && guardedReads.get() == 0);
        Future<String> guardThrew = EventRemovalDatesEndpoint.removalDates(new Object[] { 5 },
            (route, organizationId, work) -> { throw new IllegalStateException(guardText); }, countingReader);
        check("a grant lookup that throws answers the contract's failure too, and nothing is read",
            guardThrew.failed() && EventErasureLedger.REMOVAL_DATES_FAILED.equals(guardThrew.cause().getMessage())
            && guardedReads.get() == 0);
        Future<String> guardRefused = EventRemovalDatesEndpoint.removalDates(new Object[] { 5 },
            (route, organizationId, work) -> RouteAccessGuard.refused(), countingReader);
        check("a guard's refusal passes through as the refusal text, and nothing is read",
            guardRefused.failed() && REFUSED.equals(guardRefused.cause().getMessage()) && guardedReads.get() == 0);
        Object[] asked = new Object[2];
        Future<String> guardAllowed = EventRemovalDatesEndpoint.removalDates(new Object[] { "5" },
            (route, organizationId, work) -> { asked[0] = route; asked[1] = organizationId; return work.get(); },
            organizationId -> Future.succeededFuture("{\"org\":" + organizationId + "}"));
        check("an allowed caller is asked about /registration in the organisation named, and gets the read's answer",
            guardAllowed.succeeded() && "{\"org\":5}".equals(guardAllowed.result())
            && "/registration".equals(asked[0]) && Integer.valueOf(5).equals(asked[1]));
        Future<String> readFailedBehindGuard = EventRemovalDatesEndpoint.removalDates(new Object[] { 5 },
            (route, organizationId, work) -> work.get(),
            organizationId -> EventErasureLedger.removalDatesReply(Future.failedFuture(guardText)));
        check("a read that fails behind an allowed guard still answers only the contract's failure",
            readFailedBehindGuard.failed()
            && EventErasureLedger.REMOVAL_DATES_FAILED.equals(readFailedBehindGuard.cause().getMessage()));

        check("the address is the contract's",
            "modality/service/event/erasure/removalDates".equals(EventRemovalDatesEndpoint.EVENT_REMOVAL_DATES_ADDRESS));
        check("the route is Registration's", "/registration".equals(EventRemovalDatesEndpoint.REGISTRATION_ROUTE));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    /** A private static String of {@code type}, for a check that must compare against the text that ships. */
    private static String privateStatic(Class<?> type, String name) {
        try {
            java.lang.reflect.Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String describe(Object value) {
        return value instanceof Object[] array ? java.util.Arrays.toString(array) : String.valueOf(value);
    }
}

package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Data Protection Officer's event powers scoped to the event's organization (admin-tiers plan, 2026-11-18): which
 * rows of the runs list and the activity log a DPO keeps, how an event's organization is read, and that a caller with
 * no captured state is refused every per-organization question.
 *
 * <p>The per-organization ANSWER comes from the authorization framework, through OperationAuthority — judged in the
 * organization's context, which ExplicitOnlyGrantsCheck (authz plugin) covers; this check covers what is built on it.
 * No database, no test framework: main() exits non-zero on failure.
 *
 * @author Claude Code
 */
public class EventOrganizationScopeCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("EventOrganizationScopeCheck");

        // ── Reading an event's organization ──
        check("no event id, no read", EventOrganizations.eventsSql(Arrays.asList(null, null)) == null);
        check("event ids are de-duplicated and written as integers",
            EventOrganizations.eventsSql(List.of(20, 7, 20)).endsWith("where id in (20,7)"));
        check("the read selects only the id and the organization",
            EventOrganizations.eventsSql(List.of(1)).startsWith("select id, organization_id from event "));
        List<Integer> many = new java.util.ArrayList<>();
        for (int i = 1; i <= EventOrganizations.MAX_IDS * 2 + 3; i++)
            many.add(i);
        List<String> batches = EventOrganizations.eventsSqls(many);
        check("a long list is read in batches", batches.size() == 3);
        check("...every id read, none dropped",
            batches.get(0).endsWith("," + EventOrganizations.MAX_IDS + ")")
            && batches.get(1).contains("(" + (EventOrganizations.MAX_IDS + 1) + ",")
            && batches.get(2).endsWith("(" + (EventOrganizations.MAX_IDS * 2 + 1) + "," + (EventOrganizations.MAX_IDS * 2 + 2) + "," + (EventOrganizations.MAX_IDS * 2 + 3) + ")"));
        check("no ids, no batch", EventOrganizations.eventsSqls(Arrays.asList(null, null)).isEmpty());
        check("an id that is not positive is not an event", EventOrganizations.ofEvent("-3").result() == null
            && EventOrganizations.ofEvent("12abc").result() == null);

        // ── The activity read, limited before the 200 newest are kept ──
        check("the unfiltered read is the one the other checks pin", DataProtectionActivity.ACTIVITY_SQL.equals(DataProtectionActivity.activitySql(null))
            && !DataProtectionActivity.ACTIVITY_SQL.contains("e.organization_id in"));
        String own = DataProtectionActivity.activitySql(Set.of(187, 151));
        check("a DPO's read limits event rows to their organizations, in the query",
            own.contains("and e.organization_id in (151,187) union all"));
        check("...before the limit", own.indexOf("e.organization_id in") < own.indexOf("limit 200"));
        check("...once, in the event branch: person rows are left alone",
            own.split("e.organization_id in", -1).length == 2 && own.indexOf("e.organization_id in") < own.indexOf("union all"));
        check("a DPO of no organization reads no event row",
            DataProtectionActivity.activitySql(Set.of()).contains("and false union all"));
        Map<Integer, Integer> organizationByEvent = EventOrganizations.map(
            new QueryResult(3, 2, new Object[] { 20, 21, 22, 151L, 187, null }, null));
        check("rows map event -> organization; an event with no organization is left out",
            organizationByEvent.equals(Map.of(20, 151, 21, 187)));

        // ── Which events a DPO of 151 keeps ──
        Predicate<Number> ownOf151 = EventOrganizations.eventsIn(organizationByEvent, Set.of(151));
        check("an event of their organization is kept", ownOf151.test(20));
        check("...given as a Long too", ownOf151.test(20L));
        check("another organization's event is not", !ownOf151.test(21));
        check("an event with no known organization is not", !ownOf151.test(22) && !ownOf151.test(99));
        check("no event id is not", !ownOf151.test(null));

        // ── The runs list's departed and archive rows (event id first) ──
        QueryResult departed = new QueryResult(2, 4, new Object[] { 20, 21, 3, 3, "2026-08-18", "2026-08-18", 4L, 1L }, null);
        String departedOwn = EventErasureLedger.departedJson(departed, ownOf151);
        check("departed stays: only their organization's event", departedOwn.contains("\"eventId\":20") && !departedOwn.contains("\"eventId\":21"));
        check("...and the unfiltered builder keeps both, as before",
            EventErasureLedger.departedJson(departed).contains("\"eventId\":21"));
        QueryResult archive = new QueryResult(2, 4, new Object[] { 21, 20, 6, 6, null, null, 2L, 5L }, null);
        String archiveOwn = EventErasureLedger.archiveJson(archive, ownOf151);
        check("archive rows: only their organization's event", archiveOwn.contains("\"eventId\":20") && !archiveOwn.contains("\"eventId\":21"));
        check("a DPO of no organization keeps nothing",
            "[]".equals(EventErasureLedger.departedJson(departed, EventOrganizations.eventsIn(organizationByEvent, Set.of()))));

        // ── The activity log ──
        // Columns: kind, stage, scope, mode, status, eventId, organizationId, byPersonId, startedAt, finishedAt
        QueryResult activity = new QueryResult(3, 10, new Object[] {
            "event", "event", "customer",          // kind
            "finalise", "finalise", null,          // stage
            "event", "event", null,                // scope
            "commit", "commit", "commit",          // mode
            "succeeded", "succeeded", "succeeded", // status
            20, 21, null,                          // eventId
            151, 187, null,                        // organizationId
            7, 8, 9,                               // byPersonId
            "2026-11-01T10:00:00Z", "2026-11-02T10:00:00Z", "2026-11-03T10:00:00Z",
            null, null, null }, null);
        check("the organizations asked about are the event rows' only",
            DataProtectionActivity.eventOrganizations(activity).equals(List.of(151, 187)));
        check("an event removal of their organization is kept", DataProtectionActivity.keeps(activity, 0, Set.of(151)));
        check("another organization's event removal is not", !DataProtectionActivity.keeps(activity, 1, Set.of(151)));
        check("a customer's anonymisation is kept whatever the organization (any DPO's until the controller question)",
            DataProtectionActivity.keeps(activity, 2, Set.of()));
        java.util.function.IntPredicate kept = row -> DataProtectionActivity.keeps(activity, row, Set.of(151));
        check("no staff name is looked up for a row the DPO does not see",
            DataProtectionActivity.staffIds(activity, kept).equals(List.of(7, 9)));
        String activityJson = DataProtectionActivity.json(activity, kept);
        check("the reply carries the kept rows only",
            activityJson.contains("\"eventId\":20") && !activityJson.contains("\"eventId\":21") && activityJson.contains("\"kind\":\"customer\""));

        // ── A caller with no captured state is refused every per-organization question ──
        EventErasureAccess.Caller stateless = new EventErasureAccess.Caller(1);
        check("no state: not held in an organization", refused(EventErasureAccess.holdsIn(stateless, 151, false)));
        check("no organization: not held", refused(EventErasureAccess.holdsIn(new EventErasureAccess.Caller(1, new Object()), null, false)));
        Future<Set<Integer>> held = EventErasureAccess.organizationsHeld(stateless, List.of(151, 187));
        check("no state: no organization held", held.succeeded() && held.result().isEmpty());
        boolean[] ran = { false };
        Future<String> gated = EventErasureAccess.whenHoldsInOrganization("check", stateless, Future.succeededFuture(151), true,
            () -> { ran[0] = true; return Future.succeededFuture("ran"); });
        check("the gate refuses it, and the work does not run", gated.failed() && !ran[0]);
        check("...saying it is another organization's (the caller is a DPO; the screens list it by organization)",
            gated.cause().getMessage().startsWith("[" + EventErasureAccess.NOT_YOUR_ORGANIZATION_KEY + "] "));
        Future<String> noTarget = EventErasureAccess.whenHoldsInOrganization("check", stateless, Future.succeededFuture(null), true,
            () -> { ran[0] = true; return Future.succeededFuture("ran"); });
        check("a target with no organization is refused", noTarget.failed() && !ran[0]);
        check("...with the refusal that says nothing (nothing to name: it may not exist)",
            !noTarget.cause().getMessage().contains(EventErasureAccess.NOT_YOUR_ORGANIZATION_KEY));
        Future<String> failedLookup = EventErasureAccess.whenHoldsInOrganization("check", stateless, Future.failedFuture("db down"), true,
            () -> { ran[0] = true; return Future.succeededFuture("ran"); });
        check("a lookup that fails is refused", failedLookup.failed() && !ran[0]);
        check("...also without naming an organization", !failedLookup.cause().getMessage().contains(EventErasureAccess.NOT_YOUR_ORGANIZATION_KEY));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    static boolean refused(Future<Boolean> answer) {
        return answer.isComplete() && answer.succeeded() && Boolean.FALSE.equals(answer.result());
    }
}

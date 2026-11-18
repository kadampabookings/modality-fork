package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Check for the Data Protection Officer's server side (gate V0163, retention V0168): the strictly-DPO gate's database-free fences, the
 * retention policy's arguments, resolution SQL and JSON, the runner's period rules, and the activity reply.
 *
 * <p>No test framework (see MoneyTransferVerificationCheck); main() exits non-zero on failure. The operation itself is
 * asked of the authorization framework through {@code OperationAuthority} (OperationAuthorityCheck in gateway-shared
 * covers the seam; the framework's "held for a whole organization" answer is CoreSemanticsCheck's), and the flag guard
 * needs a database: that a super administrator without the role is refused is checked on staging.
 */
public class DataProtectionCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    private static final String REFUSED = "This operation is not available to you";

    private static boolean refusedWithoutWork(Object state) {
        AtomicInteger runs = new AtomicInteger();
        Future<String> answer = ThreadLocalStateHolder.runWithState(state,
            () -> EventErasureAccess.whenDataProtectionOfficer("check", caller -> {
                runs.incrementAndGet();
                return Future.succeededFuture("ran");
            }));
        return answer.failed() && REFUSED.equals(answer.cause().getMessage()) && runs.get() == 0;
    }

    private static boolean toldNo(Object state) {
        Future<Boolean> answer = ThreadLocalStateHolder.runWithState(state, EventErasureAccess::isDataProtectionOfficer);
        return answer.succeeded() && Boolean.FALSE.equals(answer.result());
    }

    public static void main(String[] args) {
        System.out.println("DataProtectionCheck");

        // ── The gate's fences, before any database lookup ──
        ModalityUserPrincipal principal = new ModalityUserPrincipal(456, 7);
        Object anonymous = StateAccessor.createEmptyState();
        Object noFamily = StateAccessor.setVerifiedBackoffice(StateAccessor.setUserId(StateAccessor.createEmptyState(), principal), true);
        Object frontOffice = StateAccessor.setVerifiedBackoffice(StateAccessor.setSessionFamilyId(
            StateAccessor.setUserId(StateAccessor.createEmptyState(), principal), "family-1"), false);
        Object supportView = StateAccessor.setVerifiedBackoffice(StateAccessor.setSessionFamilyId(StateAccessor.setUserId(
            StateAccessor.createEmptyState(), new ModalityUserPrincipal(99, 8, 456)), "family-1"), true);
        check("an anonymous caller is refused, and nothing runs", refusedWithoutWork(anonymous));
        check("a session the server did not establish is refused", refusedWithoutWork(noFamily));
        check("a front-office session is refused", refusedWithoutWork(frontOffice));
        check("a support view is refused", refusedWithoutWork(supportView));
        check("dpo/access tells an anonymous caller no, without refusing", toldNo(anonymous));
        check("dpo/access tells a front-office session no", toldNo(frontOffice));
        check("dpo/access tells a support view no", toldNo(supportView));
        check("the operation is V0163's", "DataProtection".equals(EventErasureAccess.DATA_PROTECTION_OPERATION));

        // ── Policy arguments ──
        check("a default with months and a reason is a change",
            new RetentionPolicy.Change("finalise", "default", null, 3, "Agreed with the trustees")
                .equals(RetentionPolicy.changeOf(new Object[] { "finalise", "default", null, 3, "  Agreed with the trustees " })));
        check("an organisation override needs an id",
            RetentionPolicy.changeOf(new Object[] { "finalise", "organization", null, 6, null }) == null);
        check("a default must not name a target",
            RetentionPolicy.changeOf(new Object[] { "finalise", "default", 5, 6, null }) == null);
        check("months 0 is refused", RetentionPolicy.changeOf(new Object[] { "archive", "event", 9, 0, null }) == null);
        check("months 241 is refused", RetentionPolicy.changeOf(new Object[] { "archive", "event", 9, 241, null }) == null);
        check("a fractional month is refused", RetentionPolicy.changeOf(new Object[] { "archive", "event", 9, 2.5, null }) == null);
        check("null months removes an override",
            new RetentionPolicy.Change("finalise", "event", 9, null, null)
                .equals(RetentionPolicy.changeOf(new Object[] { "finalise", "event", 9, null, "   " })));
        check("an unknown stage is refused", RetentionPolicy.changeOf(new Object[] { "erase", "default", null, 3, null }) == null);
        check("an unknown scope is refused", RetentionPolicy.changeOf(new Object[] { "finalise", "person", 1, 3, null }) == null);
        check("a reason over 500 characters is refused",
            RetentionPolicy.changeOf(new Object[] { "finalise", "default", null, 3, "x".repeat(501) }) == null);
        check("a non-text reason is refused", RetentionPolicy.changeOf(new Object[] { "finalise", "default", null, 3, 42 }) == null);
        Future<String> removeDefault = RetentionPolicy.set(new Object[] { "finalise", "default", null, null, null },
            new EventErasureAccess.Caller(1));
        check("a confirmed default can be changed, not removed - refused before any database work",
            removeDefault.failed() && removeDefault.cause().getMessage().startsWith("[RetentionPolicyDefaultRequiredError]"));

        // ── Resolution SQL ──
        String months = RetentionPolicy.monthsSql("finalise");
        check("the event's own row comes first, for every event",
            months.startsWith("coalesce((select rp.months from retention_policy rp where rp.superseded_at is null and rp.stage = 'finalise' and rp.scope = 'event' and rp.event_id = e.id)"));
        check("an ongoing event inherits nothing", months.contains("case when coalesce(t.ongoing, false) then null else"));
        check("a finished event falls back to its organisation, then the default",
            months.indexOf("rp.scope = 'organization' and rp.organization_id = e.organization_id") < months.indexOf("rp.scope = 'default'"));
        check("only current rows count", months.split("superseded_at is null", -1).length == 4);
        boolean threw = false;
        try { RetentionPolicy.monthsSql("x' or 1=1 --"); } catch (IllegalArgumentException e) { threw = true; }
        check("the stage is never input: anything else throws", threw);
        check("the runner's event read carries the resolved finalise months", EventErasureRunner.EVENT_SQL.contains(months));

        // ── The runner's period rules ──
        LocalDate today = LocalDate.of(2026, 11, 6);
        EventErasureRunner.EventFacts undefined = new EventErasureRunner.EventFacts(10, LocalDate.of(2025, 1, 10), null, "CLOSED", false, false, null);
        check("no confirmed period: refused, dry run included",
            EventErasureRunner.RETENTION_UNDEFINED_KEY.equals(key(EventErasureRunner.refusalFor(undefined, false, today, true, null, null))));
        EventErasureRunner.EventFacts sixMonths = new EventErasureRunner.EventFacts(10, LocalDate.of(2026, 6, 1), null, "CLOSED", false, false, 6);
        check("six months after 2026-06-01 is not reached on 2026-11-06",
            EventErasureRunner.RETENTION_NOT_REACHED_KEY.equals(key(EventErasureRunner.refusalFor(sixMonths, false, today, true, null, null))));
        EventErasureRunner.EventFacts threeMonths = new EventErasureRunner.EventFacts(10, LocalDate.of(2026, 6, 1), null, "CLOSED", false, false, 3);
        check("three months after 2026-06-01 is reached on 2026-11-06: a dry run may start",
            EventErasureRunner.refusalFor(threeMonths, false, today, true, null, null) == null);
        EventErasureRunner.EventFacts recordings = new EventErasureRunner.EventFacts(10, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1), "CLOSED", true, false, 3);
        check("the recordings closing date still extends the line",
            EventErasureRunner.RETENTION_NOT_REACHED_KEY.equals(key(EventErasureRunner.refusalFor(recordings, false, today, true, null, null))));
        EventErasureRunner.EventFacts legacy = new EventErasureRunner.EventFacts(10, LocalDate.of(2024, 6, 1), null, null, false, false, 3);
        check("a KBS2 event (no state) is judged by date", EventErasureRunner.refusalFor(legacy, false, today, true, null, null) == null);

        // ── Departed stays of a continuous event (V0169) ──
        EventErasureRunner.EventFacts stays = new EventErasureRunner.EventFacts(20, LocalDate.of(2027, 6, 1), null, "OPEN", false, true, 3);
        EventErasureRunner.EventFacts staysNoPeriod = new EventErasureRunner.EventFacts(21, null, null, "OPEN", false, true, null);
        check("a continuous event's departed stays may be dry-run while it is open and running",
            EventErasureRunner.departedRefusalFor(stays, false, true, null, null) == null);
        check("a departed run of an event that is not continuous is refused",
            EventErasureRunner.NOT_CONTINUOUS_KEY.equals(key(EventErasureRunner.departedRefusalFor(threeMonths, false, true, null, null))));
        check("a continuous event without its own period is refused (it never inherits one)",
            EventErasureRunner.RETENTION_UNDEFINED_KEY.equals(key(EventErasureRunner.departedRefusalFor(staysNoPeriod, false, true, null, null))));
        check("a departed commit needs the switch",
            EventErasureRunner.COMMIT_DISABLED_KEY.equals(key(EventErasureRunner.departedRefusalFor(stays, true, false, "ERASE DEPARTED 20", 5))));
        check("a departed commit needs its own phrase, not the whole event's",
            EventErasureRunner.CONFIRM_MISMATCH_KEY.equals(key(EventErasureRunner.departedRefusalFor(stays, true, true, "ERASE EVENT 20", 5))));
        check("a departed commit needs a dry run",
            EventErasureRunner.PREVIEW_REQUIRED_KEY.equals(key(EventErasureRunner.departedRefusalFor(stays, true, true, "ERASE DEPARTED 20", null))));
        check("a departed commit with the switch, the phrase and a dry run may go on",
            EventErasureRunner.departedRefusalFor(stays, true, true, "ERASE DEPARTED 20", 5) == null);
        check("the cutoff is today minus the event's period (end of month clamped)",
            LocalDate.of(2026, 2, 28).equals(EventErasureRunner.departedBefore(stays, LocalDate.of(2026, 5, 31))));
        check("the whole event still refuses a continuous event",
            EventErasureRunner.CONTINUOUS_EVENT_KEY.equals(key(EventErasureRunner.refusalFor(stays, false, today, true, null, null))));
        check("the database's phrase carries the cutoff; the whole event's is V0077's",
            "ERASE DEPARTED 20 2026-08-18".equals(EventErasureRunner.databasePhrase(20, LocalDate.of(2026, 8, 18)))
            && "ERASE EVENT 20".equals(EventErasureRunner.databasePhrase(20, null)));
        java.util.List<EventErasureRunner.GridRow> dry = java.util.List.of(
            new EventErasureRunner.GridRow("event", "bookings", "8"),
            new EventErasureRunner.GridRow("scope", "departed_bookings", "3"),
            new EventErasureRunner.GridRow("preflight", "special_needs_to_tag", "3"));
        java.util.List<EventErasureRunner.GridRow> grown = java.util.List.of(
            new EventErasureRunner.GridRow("event", "bookings", "8"),
            new EventErasureRunner.GridRow("scope", "departed_bookings", "4"),
            new EventErasureRunner.GridRow("preflight", "special_needs_to_tag", "3"));
        check("a commit whose departed set grew since its dry run drifts",
            EventErasureRunner.describeDrift(dry, grown) != null && EventErasureRunner.describeDrift(dry, dry) == null);
        check("the departed list: one row per continuous event with its cutoff and due count; a bad cutoff is skipped",
            ("[{\"eventId\":20,\"months\":3,\"departedBefore\":\"2026-08-18\",\"due\":4}]").equals(
                EventErasureLedger.departedJson(new QueryResult(2, 4, new Object[] { 20, 21, 3, 3, "2026-08-18", "soon", 4L, 1L }, null))));
        check("the due count reads the departure rule and the removal markers",
            EventErasureLedger.DEPARTED_SQL.contains("event_departed_bookings(y.id, y.cutoff)")
            && EventErasureLedger.DEPARTED_SQL.contains("h.username = 'gdpr-retention'"));

        // ── Activity ──
        String activity = DataProtectionActivity.json(new QueryResult(1, 10, new Object[] {
            "booker", null, null, "commit", "succeeded", null, 2, 31, "2026-11-01T10:00:00Z", "2026-11-01T10:00:02Z" }, null));
        check("an anonymisation row carries no subject: kind, outcome, organisation, staff id, times",
            ("{\"activity\":[{\"kind\":\"booker\",\"stage\":null,\"scope\":null,\"mode\":\"commit\",\"status\":\"succeeded\","
             + "\"eventId\":null,\"organizationId\":2,\"byPersonId\":31,\"startedAt\":\"2026-11-01T10:00:00Z\","
             + "\"finishedAt\":\"2026-11-01T10:00:02Z\"}]}").equals(activity));
        String activitySql = DataProtectionActivity.ACTIVITY_SQL;
        check("the activity read selects no subject: no person_id, document_id, grid, error or name",
            !activitySql.replaceAll("p\\.document_id is null|d\\.id = p\\.document_id|requested_by_person_id", "")
                .matches("(?s).*(person_id|document_id|grid|error|name|email|candidate|member_ids).*"));
        check("anonymisation previews are left out", activitySql.contains("p.mode = 'commit'"));

        // ── Staff names (who acted, read apart from the ledgers) ──
        check("no staff id, no lookup", StaffNames.sql(java.util.Arrays.asList(null, null)) == null);
        check("staff ids are de-duplicated, sorted and inlined as integers",
            StaffNames.sql(java.util.List.of(31, 7, 31)).endsWith("where id in (7,31)"));
        check("a staff lookup reads first and last name only",
            !StaffNames.sql(java.util.List.of(1)).matches("(?s).*(email|phone|street|birth|lay_name|abc).*"));
        check("names are keyed by id; a blank name is left out",
            "{\"7\":\"Ann Lee\"}".equals(StaffNames.json(new QueryResult(2, 2, new Object[] { 7, 8, "Ann Lee", " " }, null))));
        check("the activity reply carries the staff names beside the rows",
            DataProtectionActivity.json(new QueryResult(0, 10, new Object[0], null), "{\"7\":\"Ann Lee\"}")
                .equals("{\"activity\":[],\"staff\":{\"7\":\"Ann Lee\"}}"));
        check("the policy page carries the staff names beside the history",
            RetentionPolicy.pageJson("{}", null, null).equals("{\"policy\":{},\"history\":[],\"staff\":{}}"));

        // ── Retention stage 2: anonymising registrations (V0170) ──
        LocalDate dayNov18 = LocalDate.of(2026, 11, 18);
        EventErasureRunner.EventFacts finalised = new EventErasureRunner.EventFacts(30, LocalDate.of(2019, 3, 5), null, "FINALISED", false, false, 3, 72);
        EventErasureRunner.EventFacts kbs2 = new EventErasureRunner.EventFacts(31, LocalDate.of(2019, 3, 5), null, null, false, false, 3, 24);
        EventErasureRunner.EventFacts closedOnly = new EventErasureRunner.EventFacts(32, LocalDate.of(2019, 3, 5), null, "CLOSED", false, false, 3, 24);
        EventErasureRunner.EventFacts recent = new EventErasureRunner.EventFacts(33, LocalDate.of(2025, 6, 1), null, "FINALISED", false, false, 3, 24);
        EventErasureRunner.EventFacts noArchivePeriod = new EventErasureRunner.EventFacts(34, LocalDate.of(2019, 3, 5), null, "FINALISED", false, false, 3, null);
        EventErasureRunner.EventFacts staysArchive = new EventErasureRunner.EventFacts(35, null, null, "OPEN", false, true, 3, 24);
        check("a finalised event past its archive line may be dry-run",
            EventErasureRunner.archiveRefusalFor(finalised, false, false, dayNov18, true, null, null) == null);
        check("a KBS2 event (no state) may be dry-run too: the database checks stage 1 was done",
            EventErasureRunner.archiveRefusalFor(kbs2, false, false, dayNov18, true, null, null) == null);
        check("an event not finalised by stage 1 is refused",
            EventErasureRunner.NOT_FINALISED_KEY.equals(key(EventErasureRunner.archiveRefusalFor(closedOnly, false, false, dayNov18, true, null, null))));
        check("an event within its archive period is refused, naming the line",
            EventErasureRunner.RETENTION_NOT_REACHED_KEY.equals(key(EventErasureRunner.archiveRefusalFor(recent, false, false, dayNov18, true, null, null))));
        check("no archive period, no run",
            EventErasureRunner.RETENTION_UNDEFINED_KEY.equals(key(EventErasureRunner.archiveRefusalFor(noArchivePeriod, false, false, dayNov18, true, null, null))));
        check("a continuous event is archived per stay, not whole",
            EventErasureRunner.CONTINUOUS_EVENT_KEY.equals(key(EventErasureRunner.archiveRefusalFor(staysArchive, false, false, dayNov18, true, null, null)))
            && EventErasureRunner.archiveRefusalFor(staysArchive, true, false, dayNov18, true, null, null) == null
            && EventErasureRunner.NOT_CONTINUOUS_KEY.equals(key(EventErasureRunner.archiveRefusalFor(finalised, true, false, dayNov18, true, null, null))));
        check("an archive commit needs its own switch, its own phrase and a dry run",
            EventErasureRunner.COMMIT_DISABLED_KEY.equals(key(EventErasureRunner.archiveRefusalFor(finalised, false, true, dayNov18, false, "ARCHIVE EVENT 30", 5)))
            && EventErasureRunner.CONFIRM_MISMATCH_KEY.equals(key(EventErasureRunner.archiveRefusalFor(finalised, false, true, dayNov18, true, "ERASE EVENT 30", 5)))
            && EventErasureRunner.PREVIEW_REQUIRED_KEY.equals(key(EventErasureRunner.archiveRefusalFor(finalised, false, true, dayNov18, true, "ARCHIVE EVENT 30", null)))
            && EventErasureRunner.archiveRefusalFor(finalised, false, true, dayNov18, true, "ARCHIVE EVENT 30", 5) == null
            && EventErasureRunner.archiveRefusalFor(staysArchive, true, true, dayNov18, true, "ARCHIVE DEPARTED 35", 5) == null);
        check("the archive line is the end plus the archive period",
            LocalDate.of(2025, 3, 5).equals(EventErasureRunner.archiveLine(finalised)));
        check("the database's archive phrases carry the cutoff for departed stays",
            "ARCHIVE EVENT 30".equals(EventErasureRunner.archiveDatabasePhrase(30, null))
            && "ARCHIVE DEPARTED 35 2024-11-18".equals(EventErasureRunner.archiveDatabasePhrase(35, LocalDate.of(2024, 11, 18))));
        check("the archive list: whole events without a cutoff, continuous ones with it; a bad cutoff is skipped",
            ("[{\"eventId\":382,\"months\":72,\"departedBefore\":null,\"due\":435},{\"eventId\":1858,\"months\":6,\"departedBefore\":\"2026-05-18\",\"due\":2}]").equals(
                EventErasureLedger.archiveJson(new QueryResult(3, 4, new Object[] { 382, 1858, 9, 72, 6, 6, null, "2026-05-18", "soon", 435L, 2L, 1L }, null))));
        check("removal reads ignore stage-2 commits",
            EventErasureLedger.REMOVAL_DATES_SQL.contains("r.stage = 'finalise'"));

        // ── V0170's own refusals become the run's key; anything else stays a SQL error ──
        check("a V0170 refusal is read from the database's message",
            "EventArchiveFinaliseFirstError".equals(EventErasureRunner.archiveRefusalKey(
                "P0001 [EventArchiveFinaliseFirstError] 3 bookings of event 382 still hold free text: remove their sensitive details first")));
        check("queued mail is its own refusal",
            "EventArchiveQueuedMailError".equals(EventErasureRunner.archiveRefusalKey("P0001 [EventArchiveQueuedMailError] 2 mails of event 382")));
        check("an unknown bracketed key is not taken for a refusal",
            EventErasureRunner.archiveRefusalKey("P0001 [EventArchiveSomethingElseError] x") == null);
        check("a plain SQL failure has no refusal key",
            EventErasureRunner.archiveRefusalKey("40001 could not serialize access") == null && EventErasureRunner.archiveRefusalKey(null) == null);

        // ── Saving a period: one statement, conflicts recognised however they arrive ──
        check("a change supersedes then inserts in one statement, the insert reading the update first",
            RetentionPolicy.CHANGE_SQL.startsWith("with superseded as (update retention_policy")
            && RetentionPolicy.CHANGE_SQL.contains("from (select count(*) from superseded) s where $5::smallint is not null"));
        check("a unique violation on the current-row index is a conflict, from the submit service's message too",
            RetentionPolicy.isCurrentRowConflict(new RuntimeException(
                "ERROR: duplicate key value violates unique constraint \"retention_policy_current_event_idx\""))
            && !RetentionPolicy.isCurrentRowConflict(new RuntimeException("connection refused"))
            && !RetentionPolicy.isCurrentRowConflict(null));
        check("the departed counts are served again for ten minutes at most",
            EventErasureLedger.DEPARTED_CACHE_MS == 600_000L);

        check("the addresses are the contract's",
            "modality/service/dpo/access".equals(DataProtectionAccessEndpoint.DPO_ACCESS_ADDRESS)
            && "modality/service/dpo/retention".equals(RetentionPolicyEndpoint.DPO_RETENTION_ADDRESS)
            && "modality/service/dpo/retention/set".equals(SetRetentionPolicyEndpoint.DPO_RETENTION_SET_ADDRESS)
            && "modality/service/dpo/activity".equals(DataProtectionActivityEndpoint.DPO_ACTIVITY_ADDRESS));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    private static String key(EventErasureRunner.Refusal refusal) {
        return refusal == null ? null : refusal.key();
    }
}

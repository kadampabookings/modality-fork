package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Check for {@link LabelWritePolicy} and {@link VolunteerWritePolicy}: every write the front office's translation,
 * label-editing and volunteer pages send passes in the state those pages require, and the same writes outside it —
 * including what the 2026-11-12 security review found the first version let through — are refused.
 *
 * <p>The legitimate shapes are copied from use-translation-edits.ts, use-save-label.ts, application-payload.ts,
 * use-volunteer-arrival-response.ts and use-volunteer-date-response.ts. No test framework: runs from main() and exits
 * non-zero on failure, following ChatWritePolicyCheck.
 */
public class LabelAndVolunteerWritePolicyCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    static WriteRequest insert(String entity, Map<String, Object> values) {
        return new WriteRequest(entity, WriteVerb.INSERT, values.keySet().toArray(String[]::new), values, null, false, null);
    }

    static WriteRequest update(String entity, Object targetId, Map<String, Object> values) {
        return new WriteRequest(entity, WriteVerb.UPDATE, values.keySet().toArray(String[]::new), values, targetId, false, null);
    }

    static WriteRequest delete(String entity, Object targetId) {
        return new WriteRequest(entity, WriteVerb.DELETE, new String[0], new HashMap<>(), targetId, false, null);
    }

    static void passes(String what, String reason) {
        check(what + (reason == null ? "" : " — refused: " + reason), reason == null);
    }

    static void refused(String what, String reason) {
        check(what + (reason == null ? " — PASSED" : ""), reason != null);
    }

    // --- Label: person 10 holds the translation grant, account 40 is a tester's, person 20 / account 50 neither. ---

    static final int TRANSLATOR = 10, MEMBER = 20, TESTER_ACCOUNT = 40, MEMBER_ACCOUNT = 50;
    static final LabelWritePolicy.TranslatorLookup TRANSLATORS = (person, account) -> Future.succeededFuture(
        ((Number) person).intValue() == TRANSLATOR || account != null && ((Number) account).intValue() == TESTER_ACCOUNT);

    static String label(WriteRequest write, boolean backoffice, Object person, Object account) {
        return LabelWritePolicy.judge(write, backoffice, person, account, TRANSLATORS).result();
    }

    // --- Volunteer.
    //     Applications: 1 was sent an arrival link (open), 2's link was used, 3 is rejected, 4 was validated by a
    //     coordinator with no link ever sent, 5's link has expired.
    //     Proposals: 11 = coordinator's, open, for application 1 (dates 2026-12-01 → 2026-12-20); 12 = answered;
    //     13 = open, application 2's; 14 = the volunteer's own counter-proposal (no token); 15 = open but expired;
    //     16 = open, for application 1, already countered. ---

    static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(10), PAST = LocalDateTime.now().minusDays(1);
    static final LocalDate LATER = LocalDate.now().plusDays(10), EARLIER = LocalDate.now().minusDays(1);

    static VolunteerWritePolicy.Proposal storedProposal(int application, String status, boolean sent, Object expires, Object used) {
        return new VolunteerWritePolicy.Proposal(application, status, sent, expires, used,
            LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-20"));
    }

    static final VolunteerWritePolicy.VolunteerLookup STORED = new VolunteerWritePolicy.VolunteerLookup() {
        @Override
        public Future<VolunteerWritePolicy.Arrival> arrival(Object id) {
            return Future.succeededFuture(switch (Integer.parseInt(String.valueOf(id))) {
                case 1 -> new VolunteerWritePolicy.Arrival("pending-validation", true, FUTURE, null);
                case 2 -> new VolunteerWritePolicy.Arrival("validated", true, FUTURE, LocalDateTime.now());
                case 3 -> new VolunteerWritePolicy.Arrival("rejected", true, FUTURE, null);
                case 4 -> new VolunteerWritePolicy.Arrival("validated", false, null, null);
                case 5 -> new VolunteerWritePolicy.Arrival("no-response", true, PAST, null);
                default -> null;
            });
        }

        @Override
        public Future<VolunteerWritePolicy.Proposal> proposal(Object id) {
            return Future.succeededFuture(switch (Integer.parseInt(String.valueOf(id))) {
                case 11, 16 -> storedProposal(1, "pending", true, LATER, null);
                case 12 -> storedProposal(1, "accepted", true, LATER, LocalDate.now());
                case 13 -> storedProposal(2, "pending", true, LATER, null);
                case 14 -> storedProposal(1, "pending", false, null, null);
                case 15 -> storedProposal(1, "pending", true, EARLIER, null);
                default -> null;
            });
        }

        @Override
        public Future<VolunteerWritePolicy.Proposal> openProposalOf(Object applicationId) {
            return Future.succeededFuture(Integer.parseInt(String.valueOf(applicationId)) == 1 ? storedProposal(1, "pending", true, LATER, null) : null);
        }

        @Override
        public Future<Boolean> isCountered(Object proposalId) {
            return Future.succeededFuture(Integer.parseInt(String.valueOf(proposalId)) == 16);
        }
    };

    static String volunteer(WriteRequest write, boolean backoffice) {
        return VolunteerWritePolicy.judge(write, backoffice, STORED).result();
    }

    static WriteRequest arrivalAnswer(Object application, String status) {
        return update("VolunteeringApplication", application, values("status", status, "arrivalTokenUsedAt", "$LDT:2026-11-12T10:00"));
    }

    static WriteRequest counter(Object parent) {
        return insert("VolunteeringDateProposal", values("application", 1, "proposedStartDate", "$LD:2026-12-05",
            "proposedEndDate", "$LD:2026-12-22", "proposedBy", "volunteer", "reason", "r", "status", "pending",
            "version", 2, "parentProposal", parent));
    }

    public static void main(String[] args) {
        System.out.println("the switches:");
        check("Label ships OBSERVING", !LabelWritePolicy.ENFORCING);
        check("Volunteer ships OBSERVING", !VolunteerWritePolicy.ENFORCING);
        String job = ChatWritePolicyCheck.readJob();
        check("both are registered", job == null
            || job.contains("new LabelWritePolicy()") && job.contains("new VolunteerWritePolicy()"));

        System.out.println("labels, as the translation page and the testers' editor write them:");
        passes("a grant holder saves a French translation", label(update("Label", 5, values("fr", "Bonjour")), false, TRANSLATOR, MEMBER_ACCOUNT));
        passes("a tester edits a label in place", label(update("Label", 5, values("en", "Room")), false, MEMBER, TESTER_ACCOUNT));
        passes("a translation cleared (null falls back to English)", label(update("Label", "5", values("de", null)), false, TRANSLATOR, null));
        refused("a member who is neither", label(update("Label", 5, values("fr", "x")), false, MEMBER, MEMBER_ACCOUNT));
        refused("an anonymous page", label(update("Label", 5, values("fr", "x")), false, null, null));
        refused("a translator rewriting the ref", label(update("Label", 5, values("fr", "x", "ref", "y")), false, TRANSLATOR, null));
        refused("a translator moving it to another organisation", label(update("Label", 5, values("organization", 7)), false, TRANSLATOR, null));
        refused("a translator creating a label", label(insert("Label", values("en", "x")), false, TRANSLATOR, null));
        refused("a translator deleting one", label(delete("Label", 5), false, TRANSLATOR, null));
        refused("an update naming no label", label(update("Label", null, values("fr", "x")), false, TRANSLATOR, null));
        passes("the back office writes any label field", label(update("Label", 5, values("ref", "x", "organization", 7)), true, MEMBER, null));
        passes("other tables are not this rule's", label(insert("Event", values("name", "x")), false, null, null));

        System.out.println("the public application form:");
        WriteRequest application = insert("VolunteeringApplication", values("organization", 151, "firstName", "A", "lastName", "B",
            "email", "a@b", "hasMedicalInsurance", true, "hasCriminalConvictions", false, "status", "new", "formType", "standard"));
        passes("an application, signed out", volunteer(application, false));
        refused("one that arrives already approved", volunteer(insert("VolunteeringApplication",
            values("firstName", "A", "status", "approved")), false));
        refused("one that sets a coordinator's field", volunteer(insert("VolunteeringApplication",
            values("firstName", "A", "arrivalTokenUsedAt", "t")), false));

        System.out.println("an arrival link, answered:");
        passes("confirmed while open", volunteer(arrivalAnswer(1, "validated"), false));
        passes("cancelled while open, id as a string", volunteer(arrivalAnswer("1", "cancelled"), false));
        passes("and its timeline line", volunteer(insert("VolunteeringTimeline", values("application", 1, "date", "t", "event", "e", "status", "validated")), false));
        refused("once its link was used", volunteer(arrivalAnswer(2, "cancelled"), false));
        refused("on a rejected application", volunteer(arrivalAnswer(3, "validated"), false));
        refused("on an application no link was ever sent for (review finding 1)", volunteer(arrivalAnswer(4, "cancelled"), false));
        refused("after the link expired", volunteer(arrivalAnswer(5, "cancelled"), false));
        refused("without marking the link used (review finding 2c)", volunteer(update("VolunteeringApplication", 1, values("status", "cancelled")), false));
        refused("marking it used with null", volunteer(update("VolunteeringApplication", 1, values("status", "cancelled", "arrivalTokenUsedAt", null)), false));
        refused("to a status no link sets", volunteer(update("VolunteeringApplication", 1, values("status", "approved", "arrivalTokenUsedAt", "t")), false));
        refused("a timeline line where no link was sent", volunteer(insert("VolunteeringTimeline", values("application", 4, "date", "t", "event", "e")), false));
        refused("rewriting someone's medical answer", volunteer(update("VolunteeringApplication", 1, values("hasMedicalInsurance", false)), false));
        refused("deleting an application (it cascades to assignments)", volunteer(delete("VolunteeringApplication", 1), false));

        System.out.println("a dates link, answered:");
        passes("proposal accepted", volunteer(update("VolunteeringDateProposal", 11, values("status", "accepted", "actionTokenUsedAt", "$LD:2026-11-12")), false));
        passes("and the proposal's dates become the application's", volunteer(update("VolunteeringApplication", 1,
            values("startDate", "$LD:2026-12-01", "endDate", "$LD:2026-12-20", "datesStatus", "confirmed", "datesConfirmed", true)), false));
        passes("proposal superseded", volunteer(update("VolunteeringDateProposal", 11, values("status", "superseded", "actionTokenUsedAt", "$LD:2026-11-12")), false));
        passes("the volunteer's counter-proposal", volunteer(counter(11), false));
        passes("and the application back to the coordinator", volunteer(update("VolunteeringApplication", 1, values("datesStatus", "pending-coordinator")), false));
        refused("accepting other dates than proposed (review finding 2a)", volunteer(update("VolunteeringApplication", 1,
            values("startDate", "$LD:2026-01-01", "endDate", "$LD:2026-12-31", "datesStatus", "confirmed", "datesConfirmed", true)), false));
        refused("accepting the volunteer's own counter-proposal (review finding 2b)", volunteer(update("VolunteeringDateProposal", 14,
            values("status", "accepted", "actionTokenUsedAt", "$LD:2026-11-12")), false));
        refused("countering a counter-proposal", volunteer(counter(14), false));
        refused("countering twice", volunteer(counter(16), false));
        refused("answering without marking the link used", volunteer(update("VolunteeringDateProposal", 11, values("status", "accepted")), false));
        refused("answering an expired link", volunteer(update("VolunteeringDateProposal", 15, values("status", "accepted", "actionTokenUsedAt", "d")), false));
        refused("a proposal answered twice", volunteer(update("VolunteeringDateProposal", 12, values("status", "accepted", "actionTokenUsedAt", "d")), false));
        refused("a counter-proposal to another application's proposal", volunteer(counter(13), false));
        refused("a counter-proposal posing as the coordinator", volunteer(insert("VolunteeringDateProposal",
            values("application", 1, "proposedBy", "coordinator", "status", "pending", "parentProposal", 11)), false));
        refused("dates changed with no proposal open", volunteer(update("VolunteeringApplication", 3,
            values("startDate", "$LD:2026-12-01", "endDate", "$LD:2026-12-20", "datesStatus", "confirmed", "datesConfirmed", true)), false));
        refused("a counter-proposal pointing into the batch", volunteer(counter(new GeneratedKeyReference(0)), false));
        refused("an update written as an expression", volunteer(update("VolunteeringApplication", ProtectedEntityWriteRegistry.UNRESOLVED_VALUE,
            values("datesStatus", "pending-coordinator")), false));

        System.out.println("dates and expiries as the wire and the database carry them:");
        check("$LD: string vs LocalDate", VolunteerWritePolicy.sameDate("$LD:2026-12-01", LocalDate.parse("2026-12-01")));
        check("different days differ", !VolunteerWritePolicy.sameDate("$LD:2026-12-02", LocalDate.parse("2026-12-01")));
        check("an unreadable date matches nothing", !VolunteerWritePolicy.sameDate(ProtectedEntityWriteRegistry.UNRESOLVED_VALUE, LocalDate.parse("2026-12-01")));
        check("no expiry never expires", VolunteerWritePolicy.notExpired(null));
        check("a past timestamp has expired", !VolunteerWritePolicy.notExpired(PAST));
        check("today's date has not", VolunteerWritePolicy.notExpired(LocalDate.now()));

        System.out.println("the back office:");
        passes("approves an application", volunteer(update("VolunteeringApplication", 1, values("status", "approved")), true));
        passes("deletes one", volunteer(delete("VolunteeringApplication", 1), true));
        passes("other tables are not this rule's", volunteer(insert("Event", values("name", "x")), false));

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

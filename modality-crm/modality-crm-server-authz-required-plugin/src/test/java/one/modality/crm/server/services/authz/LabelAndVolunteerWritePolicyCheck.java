package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;

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

    static String volunteer(WriteRequest write, boolean backoffice) {
        return VolunteerWritePolicy.judge(write, backoffice);
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

        System.out.println("the public application form, the front office's one volunteer write:");
        WriteRequest application = insert("VolunteeringApplication", values("organization", 151, "firstName", "A", "lastName", "B",
            "email", "a@b", "hasMedicalInsurance", true, "hasCriminalConvictions", false, "status", "new", "formType", "standard"));
        passes("an application, signed out", volunteer(application, false));
        passes("one that leaves the status to its default", volunteer(insert("VolunteeringApplication", values("firstName", "A")), false));
        refused("one that arrives already approved", volunteer(insert("VolunteeringApplication",
            values("firstName", "A", "status", "approved")), false));
        refused("one that sets a coordinator's field", volunteer(insert("VolunteeringApplication",
            values("firstName", "A", "arrivalTokenUsedAt", "t")), false));

        System.out.println("everything the link pages used to write, now the endpoints' (an old bundle, or anyone):");
        refused("an arrival answered by id", volunteer(update("VolunteeringApplication", 1,
            values("status", "cancelled", "arrivalTokenUsedAt", "t")), false));
        refused("dates accepted by id", volunteer(update("VolunteeringApplication", 1,
            values("startDate", "d1", "endDate", "d2", "datesStatus", "confirmed", "datesConfirmed", true)), false));
        refused("a proposal answered by id", volunteer(update("VolunteeringDateProposal", 11,
            values("status", "accepted", "actionTokenUsedAt", "d")), false));
        refused("a counter-proposal inserted", volunteer(insert("VolunteeringDateProposal",
            values("application", 1, "proposedBy", "volunteer", "status", "pending", "parentProposal", 11)), false));
        refused("a timeline line", volunteer(insert("VolunteeringTimeline", values("application", 1, "date", "t", "event", "e")), false));
        refused("rewriting someone's medical answer", volunteer(update("VolunteeringApplication", 1, values("hasMedicalInsurance", false)), false));
        refused("deleting an application (it cascades to assignments)", volunteer(delete("VolunteeringApplication", 1), false));

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

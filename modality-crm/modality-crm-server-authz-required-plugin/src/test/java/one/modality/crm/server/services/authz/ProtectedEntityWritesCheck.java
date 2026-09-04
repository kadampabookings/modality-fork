package one.modality.crm.server.services.authz;

import java.util.Arrays;
import java.util.List;

/**
 * Check for the write-authorization decision logic — what a caller must hold for a given write.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits
 * non-zero on failure, following the checks in webfx-stack-authz-core.
 *
 * <p>Written after the fact, which is the point. TWO bugs in this logic reached production and were
 * found by reading logs rather than by testing:
 * <ul>
 *   <li>entity and field codes were pooled into one any-of list, so holding EditLetterProperties
 *       authorized rewriting a letter's CONTENT — the opposite of the split's purpose;</li>
 *   <li>the groups were evaluated in a chain of async callbacks, so every group after the first was
 *       judged with no principal and refused a super admin.</li>
 * </ul>
 *
 * <p><b>Only the first of those is checkable here</b>, and saying so matters more than the tests
 * themselves: the second was a threading fault in code that looks correct when read, and no assertion
 * about required codes would have caught it. What this pins is the SHAPE of the requirement — which
 * groups exist and what is in them — because that is where a wrong boolean silently widens access.
 */
public class ProtectedEntityWritesCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** True when some group requires exactly this one code — i.e. it cannot be satisfied by anything else. */
    static boolean requiresAlone(List<String[]> groups, String code) {
        return groups.stream().anyMatch(g -> g.length == 1 && g[0].equals(code));
    }

    static boolean anyGroupContains(List<String[]> groups, String code) {
        return groups.stream().anyMatch(g -> Arrays.asList(g).contains(code));
    }

    static List<String[]> groupsFor(String entity, String... fields) {
        return ProtectedEntityWritesJob.requiredCodeGroups(entity, fields);
    }

    public static void main(String[] args) {
        System.out.println("entities the policy does not cover:");
        check("an unprotected entity requires nothing", groupsFor("Document", "person").isEmpty());
        check("a near-miss on the textual pre-filter requires nothing", groupsFor("Mail", "letter").isEmpty());

        System.out.println("privilege-bearing entities:");
        for (String e : new String[] { "AuthorizationRule", "AuthorizationSuperAdmin", "Operation", "OperationGroup" })
            check(e + " requires ManageAuthorizations alone", requiresAlone(groupsFor(e, "name"), "ManageAuthorizations"));
        check("a delete (no fields) is still covered by the entity rule",
            requiresAlone(groupsFor("AuthorizationRule"), "ManageAuthorizations"));

        System.out.println("Letter — the split that a pooled list destroyed:");
        List<String[]> content = groupsFor("Letter", "en");
        check("writing content requires EditLetterContent ON ITS OWN", requiresAlone(content, "EditLetterContent"));
        check("...so holding only EditLetterProperties cannot authorize it",
            !content.stream().allMatch(g -> Arrays.asList(g).contains("EditLetterProperties")));
        List<String[]> properties = groupsFor("Letter", "onHold");
        check("writing a property requires EditLetterProperties on its own",
            requiresAlone(properties, "EditLetterProperties"));
        List<String[]> both = groupsFor("Letter", "en", "onHold");
        check("writing both requires BOTH, as separate groups",
            requiresAlone(both, "EditLetterContent") && requiresAlone(both, "EditLetterProperties"));
        check("the entity rule survives as its own group (the unreadable-statement backstop)",
            both.stream().anyMatch(g -> g.length == 2
                && Arrays.asList(g).contains("EditLetterContent")
                && Arrays.asList(g).contains("EditLetterProperties")));

        System.out.println("Letter — content is matched by pattern, properties are the remainder:");
        for (String f : new String[] { "en", "fr", "zhs", "subject_en", "push_title_de", "push_body_vi" })
            check("'" + f + "' is content", ProtectedEntityWritesJob.isLetterContentField(f));
        for (String f : new String[] { "name", "type", "onHold", "kbs3", "pushContext", "somethingAddedLater" })
            check("'" + f + "' is a property", !ProtectedEntityWritesJob.isLetterContentField(f));

        System.out.println("FrontendAccount — one privileged field on an ordinary row:");
        check("setting backoffice requires ManageBackofficeAccess",
            requiresAlone(groupsFor("FrontendAccount", "backoffice"), "ManageBackofficeAccess"));
        check("signup and a language change require nothing",
            groupsFor("FrontendAccount", "username", "password").isEmpty()
            && groupsFor("FrontendAccount", "language").isEmpty());
        check("a statement touching both still requires the field's code",
            anyGroupContains(groupsFor("FrontendAccount", "language", "backoffice"), "ManageBackofficeAccess"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

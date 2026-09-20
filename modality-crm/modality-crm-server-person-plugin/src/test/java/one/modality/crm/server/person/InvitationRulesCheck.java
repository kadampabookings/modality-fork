package one.modality.crm.server.person;

import java.util.HashSet;
import java.util.Set;

/**
 * Check for the two properties that make an approval's proof worth anything.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, like the other checks here.
 *
 * <p>An invitation is what {@link PersonLinkRules} accepts as evidence that somebody asked for a link. It
 * is only evidence if the person it names as inviter is the person who wrote it, and if the token it
 * carries was not chosen by whoever will present it. Neither is visible in a type signature, so they are
 * asserted here: the statement takes its inviter from a parameter the endpoint fills from the principal,
 * and the token comes from a generator with real entropy rather than from the argument.
 */
public class InvitationRulesCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("InvitationRulesCheck");
        String create = InvitationRules.createStatement();

        // --- the inviter is a bound parameter, never a literal or a second argument slot ---
        check("the row is inserted with a parameterised inviter", create.contains("select now(), $1, $2, $3"));
        check("pending and accepted are decided here, not sent", create.contains("true, false, $6"));

        // --- asking twice is the same request with two live tokens, so it is refused ---
        check("creation is guarded against a second pending request",
            create.contains("where not exists") && create.contains("and pending = true"));
        check("the guard keys on the pair AND the direction",
            create.contains("inviter_id = $1") && create.contains("invitee_id = $2")
                && create.contains("inviter_payer = $3"));

        // --- the token: unguessable, and never the same twice ---
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++)
            tokens.add(InvitationRules.mintToken());
        check("1000 tokens are 1000 distinct tokens", tokens.size() == 1000);
        String token = InvitationRules.mintToken();
        // 32 bytes, base64url, unpadded
        check("a token is 43 characters", token.length() == 43);
        check("a token is URL-safe", token.chars().allMatch(c ->
            Character.isLetterOrDigit(c) || c == '-' || c == '_'));
        check("a token carries no padding", !token.contains("="));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

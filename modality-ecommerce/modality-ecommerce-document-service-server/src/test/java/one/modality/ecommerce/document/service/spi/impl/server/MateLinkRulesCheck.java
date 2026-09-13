package one.modality.ecommerce.document.service.spi.impl.server;

import one.modality.ecommerce.document.service.spi.impl.server.MateLinkRules.LineFacts;

/**
 * Check for {@link MateLinkRules} — what the server requires before it writes a roommate link.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, following ProtectedEntityWritesCheck and the webfx-stack session-token checks.
 *
 * <p>The security cases come first and matter most. The session's back-office flag is client-asserted,
 * so the checks pin that it is never sufficient on its own: a client claiming to be the back office
 * with an account that is not must be refused.
 */
public class MateLinkRulesCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** A share-mate line on booking 700, event 5. */
    static final LineFacts MATE = new LineFacts(true, false, 700L, 5);
    /** A share-owner line on booking 90, same event (as a Long — ids compare by value). */
    static final LineFacts OWNER = new LineFacts(false, true, 90, 5L);

    /** A link from a verified back-office account in the back-office app, naming an owner line. */
    static String backofficeLink(LineFacts mate, LineFacts owner) {
        return MateLinkRules.check(true, true, mate, true, owner, false);
    }

    public static void main(String[] args) {
        System.out.println("MateLinkRules");

        check("a back-office account in the back-office app links a mate to a same-event booker",
            backofficeLink(MATE, OWNER) == null);

        // ── Security: the session flag is client-asserted and never enough ──
        check("refused when the session CLAIMS back office but the account is not a back-office account",
            MateLinkRules.check(true, false, MATE, true, OWNER, false) != null);
        check("refused for a guest or public submitter, who has no account",
            MateLinkRules.check(true, null, MATE, true, OWNER, false) != null);
        check("refused for a back-office account working in the front-office app",
            MateLinkRules.check(false, true, MATE, true, OWNER, false) != null);

        // ── Shape of the link ──
        check("refused when the link names no booker at all",
            MateLinkRules.check(true, true, MATE, false, null, false) != null);
        check("refused when the roommate's line does not exist",
            backofficeLink(null, OWNER) != null);
        check("refused when the roommate's line is not a sharing booking",
            backofficeLink(new LineFacts(false, false, 700L, 5), OWNER) != null);
        check("refused when the named booker line does not exist",
            backofficeLink(MATE, null) != null);
        check("refused when the named line is not a room booking that can be shared",
            backofficeLink(MATE, new LineFacts(false, false, 90, 5)) != null);
        check("refused across events",
            backofficeLink(MATE, new LineFacts(false, true, 90, 6)) != null);
        check("refused when the booker's event is unknown",
            backofficeLink(MATE, new LineFacts(false, true, 90, null)) != null);
        check("refused when a booking is linked to itself",
            backofficeLink(MATE, new LineFacts(false, true, 700, 5)) != null);

        // ── The owner-person form (booker has not booked yet) ──
        check("the owner-person form passes for the back office",
            MateLinkRules.check(true, true, MATE, false, null, true) == null);
        check("the owner-person form still needs a real sharing line",
            MateLinkRules.check(true, true, new LineFacts(false, false, 700, 5), false, null, true) != null);
        check("the owner-person form is still back-office only",
            MateLinkRules.check(true, false, MATE, false, null, true) != null);

        // ── Ids and messages ──
        check("ids compare by value across Integer and Long", MateLinkRules.sameId(5, 5L));
        check("a null id never matches, not even another null", !MateLinkRules.sameId(null, null));
        String refusal = backofficeLink(MATE, new LineFacts(false, true, 90, 6));
        check("every refusal carries the recognisable prefix",
            refusal != null && refusal.startsWith(MateLinkRules.ERROR_PREFIX));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

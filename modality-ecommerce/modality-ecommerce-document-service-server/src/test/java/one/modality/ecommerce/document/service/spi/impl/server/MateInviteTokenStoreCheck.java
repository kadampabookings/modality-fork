package one.modality.ecommerce.document.service.spi.impl.server;

/**
 * Check for {@link MateInviteTokenStore}'s pure helpers — the token generator and the hash that ties
 * a minted token to the row a consume claims. The database operations are not exercised here (no
 * server); what is pinned is that the hash is stable and standard, since mint and consume must agree
 * on it, and that generated tokens are URL-safe and unique.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, following ProtectedEntityWritesCheck and MateLinkRulesCheck.
 */
public class MateInviteTokenStoreCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("MateInviteTokenStore");

        // hashToken must be plain SHA-256 hex, so mint and consume (and any DB inspection) agree.
        // Standard vector: sha256("abc").
        check("hashToken is standard SHA-256 hex",
            MateInviteTokenStore.hashToken("abc")
                .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        check("hashToken is 64 lower-case hex chars",
            MateInviteTokenStore.hashToken("any token").matches("[0-9a-f]{64}"));
        check("different tokens hash differently",
            !MateInviteTokenStore.hashToken("token-a").equals(MateInviteTokenStore.hashToken("token-b")));
        check("the same token always hashes the same",
            MateInviteTokenStore.hashToken("stable").equals(MateInviteTokenStore.hashToken("stable")));

        // Generated tokens must be URL-safe (they ride in an invite URL) and effectively unique.
        String t1 = MateInviteTokenStore.generateToken();
        String t2 = MateInviteTokenStore.generateToken();
        check("generated token is URL-safe (base64url, no padding)", t1.matches("[A-Za-z0-9_-]+"));
        check("generated token carries 128 bits (>= 22 base64 chars)", t1.length() >= 22);
        check("two mints differ", !t1.equals(t2));

        // --- The status a mate may be told about a link -------------------------------------------
        // Pure, and the whole of what the UNAUTHENTICATED resolve endpoint discloses. Worth checking
        // because an inverted branch here would either hide a usable link or advertise a full room —
        // and because nothing else exercises it without a database.
        check("a resolved link with a free bed is usable",
            MateInviteTokenStore.STATUS_USABLE.equals(MateInviteTokenStore.statusOf(true, false, true, false)));
        // An account already in a triple may be booking a SECOND person into it; JOINED here would stop
        // the token riding along, and that person would book unlinked.
        check("a free bed stays usable even for an account already in the room",
            MateInviteTokenStore.STATUS_USABLE.equals(MateInviteTokenStore.statusOf(true, false, true, true)));
        check("a resolved link with no free bed reads as full, not as broken",
            MateInviteTokenStore.STATUS_FULL.equals(MateInviteTokenStore.statusOf(true, false, false, false)));
        check("a full room reads as joined for an account that already holds one of its beds",
            MateInviteTokenStore.STATUS_JOINED.equals(MateInviteTokenStore.statusOf(true, false, false, true)));
        check("a token that exists but has lapsed says so",
            MateInviteTokenStore.STATUS_EXPIRED.equals(MateInviteTokenStore.statusOf(false, true, false, false)));
        check("an unresolvable token is unknown",
            MateInviteTokenStore.STATUS_UNKNOWN.equals(MateInviteTokenStore.statusOf(false, false, false, false)));
        // A token issued for ANOTHER event does not resolve and has not lapsed, so it reads as
        // unknown — deliberately, since confirming it exists elsewhere discloses more than nothing.
        check("a token belonging to another event is not confirmed",
            MateInviteTokenStore.STATUS_UNKNOWN.equals(MateInviteTokenStore.statusOf(false, false, true, false)));
        check("holding a bed never confirms a token that did not resolve",
            MateInviteTokenStore.STATUS_UNKNOWN.equals(MateInviteTokenStore.statusOf(false, false, false, true)));
        check("no status is anything but the five permitted words",
            java.util.List.of("USABLE", "FULL", "JOINED", "EXPIRED", "UNKNOWN").containsAll(java.util.List.of(
                MateInviteTokenStore.statusOf(true, false, true, false),
                MateInviteTokenStore.statusOf(true, false, false, false),
                MateInviteTokenStore.statusOf(true, false, false, true),
                MateInviteTokenStore.statusOf(false, true, false, false),
                MateInviteTokenStore.statusOf(false, false, false, false))));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

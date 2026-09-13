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

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

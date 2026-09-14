package one.modality.crm.server.authn.gateway.totp;

import java.nio.charset.StandardCharsets;

/**
 * Check for the TOTP arithmetic: the RFC 6238 test vectors, the drift window, and the property the
 * replay guard depends on.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from {@code main()} and
 * exits non-zero on failure, following {@code ProtectedEntityWritesCheck} and the
 * {@code webfx-stack-session-token} {@code *Check} classes.
 *
 * <p>Why these assertions and not others. The arithmetic is hand-rolled, so the RFC's own vectors
 * are the only thing that can say it is the same arithmetic every authenticator app implements — a
 * bug here does not throw, it just makes every phone in the organisation wrong, and it would be
 * discovered by 216 people at once. The window assertions pin the OTHER half: a window that is one
 * step too wide multiplies what a guesser may hit, and one that is too narrow rejects a phone whose
 * clock is fifteen seconds out.
 *
 * <p>{@link Totp#matchingStep} is asserted to return WHICH step matched, not merely that one did,
 * because {@code totp_credential.last_used_step} is that value: a version that returned the current
 * step for a code minted at T−1 would let the same code back in a step later, and the window is
 * exactly where a relay proxy lives.
 */
public class TotpCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** RFC 6238 Appendix B's SHA-1 seed: the 20 ASCII bytes "12345678901234567890". */
    static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** The 6-digit code at one of the RFC's test times — the last six digits of its 8-digit vector. */
    static String codeAt(long epochSeconds) {
        return Totp.generate(RFC_SEED, Totp.currentStep(epochSeconds));
    }

    public static void main(String[] args) {
        System.out.println("RFC 6238 Appendix B (HMAC-SHA1), 6-digit truncation of each published vector:");
        // The published values are 8 digits; a 6-digit code is the same dynamic truncation modulo
        // 10^6, i.e. their last six digits.
        check("T=59 → 94287082 → 287082", "287082".equals(codeAt(59L)));
        check("T=1111111109 → 07081804 → 081804", "081804".equals(codeAt(1111111109L)));
        check("T=1111111111 → 14050471 → 050471", "050471".equals(codeAt(1111111111L)));
        check("T=1234567890 → 89005924 → 005924 (leading zero kept)", "005924".equals(codeAt(1234567890L)));
        check("T=2000000000 → 69279037 → 279037", "279037".equals(codeAt(2000000000L)));
        check("T=20000000000 → 65353130 → 353130", "353130".equals(codeAt(20000000000L)));

        System.out.println("step arithmetic (floor of unix time over 30):");
        check("0 s is step 0", Totp.currentStep(0) == 0);
        check("29 s is still step 0", Totp.currentStep(29) == 0);
        check("30 s is step 1", Totp.currentStep(30) == 1);
        check("59 s is step 1 — the Appendix B time", Totp.currentStep(59) == 1);

        System.out.println("the accepted window is T−1, T, T+1 and nothing further:");
        long now = 1_700_000_000L; // an arbitrary fixed moment, so this check never depends on the clock
        long step = Totp.currentStep(now);
        check("the current step is accepted", Totp.matchingStep(RFC_SEED, Totp.generate(RFC_SEED, step), now) == step);
        check("one step behind is accepted (a slow phone)",
            Totp.matchingStep(RFC_SEED, Totp.generate(RFC_SEED, step - 1), now) == step - 1);
        check("one step ahead is accepted (a fast phone)",
            Totp.matchingStep(RFC_SEED, Totp.generate(RFC_SEED, step + 1), now) == step + 1);
        check("two steps behind is REFUSED", Totp.matchingStep(RFC_SEED, Totp.generate(RFC_SEED, step - 2), now) < 0);
        check("two steps ahead is REFUSED", Totp.matchingStep(RFC_SEED, Totp.generate(RFC_SEED, step + 2), now) < 0);
        check("a code from another secret is refused",
            Totp.matchingStep(RFC_SEED, Totp.generate("0000000000nope000000".getBytes(StandardCharsets.US_ASCII), step), now) < 0);
        check("a non-code is refused", Totp.matchingStep(RFC_SEED, "abcdef", now) < 0);
        check("a truncated code is refused", Totp.matchingStep(RFC_SEED, "28708", now) < 0);
        check("null is refused", Totp.matchingStep(RFC_SEED, null, now) < 0);

        System.out.println("what the replay guard stores — the step that matched, not the current one:");
        // last_used_step is this value, and the UPDATE only fires while the stored step is strictly
        // behind it. Returning `step` for a code minted at step−1 would leave the drift window open
        // to a second use of the same code.
        long matched = Totp.matchingStep(RFC_SEED, Totp.generate(RFC_SEED, step - 1), now);
        check("a code minted at T−1 reports T−1", matched == step - 1);
        check("...so a guard holding T−1 refuses it again", !(matched > step - 1));
        check("and the same code still reports T−1 a moment later (replay is a store decision, not a clock one)",
            Totp.matchingStep(RFC_SEED, Totp.generate(RFC_SEED, step - 1), now + 5) == step - 1);

        System.out.println("matches() is exact about the step it is asked about:");
        check("the right code at the right step matches", Totp.matches(RFC_SEED, Totp.generate(RFC_SEED, step), step));
        check("the right code at the wrong step does not", !Totp.matches(RFC_SEED, Totp.generate(RFC_SEED, step), step + 1));
        check("whitespace around a typed code is tolerated", Totp.matches(RFC_SEED, " " + Totp.generate(RFC_SEED, step) + " ", step));

        System.out.println("base32 (RFC 4648, unpadded) — what the otpauth:// URI carries:");
        check("the RFC seed encodes to the published test secret",
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ".equals(Totp.base32Encode(RFC_SEED)));
        check("20 random-ish bytes round-trip", roundTrips(RFC_SEED));
        check("a 1-byte value round-trips (the ragged-tail case)", roundTrips(new byte[]{(byte) 0xAB}));
        check("an empty value round-trips", roundTrips(new byte[0]));
        check("decoding tolerates lower case, padding and the grouping dash",
            java.util.Arrays.equals(RFC_SEED, Totp.base32Decode("gezdgnbvgy3tqojq-gezdgnbvgy3tqojq=")));
        check("a character outside the alphabet is refused", Totp.base32Decode("GEZD!NBV") == null);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    static boolean roundTrips(byte[] value) {
        return java.util.Arrays.equals(value, Totp.base32Decode(Totp.base32Encode(value)));
    }
}

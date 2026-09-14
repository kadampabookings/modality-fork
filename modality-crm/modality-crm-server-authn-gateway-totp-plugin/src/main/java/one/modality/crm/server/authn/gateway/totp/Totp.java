package one.modality.crm.server.authn.gateway.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The TOTP arithmetic (RFC 6238 over RFC 4226), hand-rolled on {@code javax.crypto.Mac} because
 * that is all the standard needs: HMAC-SHA1 over the 8-byte big-endian time step, dynamic
 * truncation, six decimal digits.
 *
 * <p>SHA-1 here is not a hash of anything anyone attacks: it is the MAC every authenticator app
 * implements, keyed by a 20-byte secret, over a counter. Changing it would make every phone in the
 * organisation wrong.
 *
 * <p><b>Window.</b> A code is accepted at steps T−1, T and T+1 — one 30-second step of clock drift
 * either way, which is what the RFC's own "at most one time step" guidance allows. Widening it is
 * the usual way TOTP gets weakened: every extra step multiplies the codes a guesser may hit. The
 * step a code matched is returned so the caller can refuse a step it has already accepted — the
 * window is what makes a replay possible at all, so the replay guard is not optional.
 *
 * <p>Comparisons go through {@link MessageDigest#isEqual} and the three candidate steps are all
 * evaluated before answering, so neither the digits of a code nor which step it matched can be read
 * out of how long the answer took.
 *
 * <p>Proven against RFC 6238 Appendix B by {@code TotpCheck} (src/test, run from {@code main}).
 *
 * @author Claude Code
 */
final class Totp {

    /** RFC 6238's default, and what every authenticator app assumes when the URI omits it. */
    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    /** How many steps either side of the current one are accepted — see the class javadoc. */
    static final int DRIFT_STEPS = 1;
    /** The secret size RFC 4226 recommends, and what enrolment mints. */
    static final int SECRET_BYTES = 20;

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int MODULO = 1_000_000; // 10^DIGITS
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; // RFC 4648

    private Totp() {
    }

    /** The time step a moment falls in: {@code floor(epochSeconds / 30)}. */
    static long currentStep(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    /** Whether the code is the one this secret produces for exactly this step. */
    static boolean matches(byte[] secret, String code, long step) {
        if (secret == null || code == null)
            return false;
        String trimmed = code.trim();
        if (trimmed.length() != DIGITS)
            return false;
        String expected = generate(secret, step);
        if (expected == null)
            return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), trimmed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The step the code matched within the accepted window, or −1 when it matched none.
     *
     * <p>All three candidates are evaluated even after one matches: an early return would make a
     * code that matched T−1 measurably faster to reject than one that matched nothing.
     */
    static long matchingStep(byte[] secret, String code, long nowEpochSeconds) {
        long now = currentStep(nowEpochSeconds);
        long matched = -1;
        for (long offset = -DRIFT_STEPS; offset <= DRIFT_STEPS; offset++) {
            long candidate = now + offset;
            if (matches(secret, code, candidate))
                matched = candidate;
        }
        return matched;
    }

    /** The six-digit code for one step, or null when the platform cannot do HMAC-SHA1 (it can). */
    static String generate(byte[] secret, long step) {
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (step & 0xFF);
            step >>>= 8;
        }
        byte[] hmac;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            hmac = mac.doFinal(counter);
        } catch (GeneralSecurityException e) {
            // HmacSHA1 is mandatory in every JRE; an empty secret is the only realistic way here,
            // and it must read as "no code" rather than as a code
            return null;
        }
        // RFC 4226 dynamic truncation: the low nibble of the last byte picks the 4-byte window,
        // and the top bit is masked off so the result is positive on every platform
        int offset = hmac[hmac.length - 1] & 0x0F;
        int binary = ((hmac[offset] & 0x7F) << 24)
                     | ((hmac[offset + 1] & 0xFF) << 16)
                     | ((hmac[offset + 2] & 0xFF) << 8)
                     | (hmac[offset + 3] & 0xFF);
        // Locale.ROOT is not decoration: a JVM whose default locale uses a non-ASCII numbering
        // system would render "%d" in its own digits, and every code in the organisation would
        // stop matching what the phone shows.
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % MODULO);
    }

    /** RFC 4648 base32, unpadded — what the {@code otpauth://} URI carries and what apps expect. */
    static String base32Encode(byte[] data) {
        StringBuilder encoded = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bitsInBuffer = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5;
                encoded.append(BASE32_ALPHABET.charAt((buffer >>> bitsInBuffer) & 0x1F));
            }
        }
        if (bitsInBuffer > 0) // left-align the remaining bits, as the encoding requires
            encoded.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsInBuffer)) & 0x1F));
        return encoded.toString();
    }

    /**
     * Decodes base32, tolerating lower case, padding and spaces — the three things a person who
     * types a secret back in gets "wrong". Returns null when a character is not in the alphabet.
     */
    static byte[] base32Decode(String text) {
        if (text == null)
            return null;
        byte[] decoded = new byte[text.length() * 5 / 8];
        int written = 0, buffer = 0, bitsInBuffer = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            if (c == '=' || c == ' ' || c == '-')
                continue;
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0)
                return null;
            buffer = (buffer << 5) | value;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8;
                decoded[written++] = (byte) ((buffer >>> bitsInBuffer) & 0xFF);
            }
        }
        if (written == decoded.length)
            return decoded;
        byte[] exact = new byte[written];
        System.arraycopy(decoded, 0, exact, 0, written);
        return exact;
    }
}

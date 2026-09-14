package one.modality.crm.server.authn.gateway.totp;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * The backup codes' alphabet and shape — the one place both the generator and the comparison
 * agree on what a code is made of.
 *
 * <p>Ten characters from 32 symbols is fifty bits, single-use: a guesser gets one attempt per
 * pending login and the per-account cap ends the attempt long before the arithmetic matters. The
 * alphabet is Crockford-flavoured — no {@code 0}, {@code O}, {@code 1} or {@code I} — because these
 * are read off paper or a screenshot and typed back by someone who has just lost their phone, and
 * a code that is impossible to mistype is worth more here than four extra symbols.
 *
 * @author Claude Code
 */
final class BackupCodes {

    /** 32 symbols: digits 2–9 and A–Z without I and O. */
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final int CODE_LENGTH = 10;
    /** Shown as {@code xxxxx-xxxxx}; the dash is presentation only and never hashed. */
    static final int GROUP_LENGTH = 5;
    /** One sheet's worth. */
    static final int CODES_PER_GENERATION = 10;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private BackupCodes() {
    }

    /** A fresh generation of codes, in their canonical (undashed) form. */
    static List<String> generate() {
        List<String> codes = new ArrayList<>(CODES_PER_GENERATION);
        for (int i = 0; i < CODES_PER_GENERATION; i++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int c = 0; c < CODE_LENGTH; c++)
                // The alphabet is exactly 32 symbols, so nextInt is uniform with no rejection step
                code.append(ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length())));
            codes.add(code.toString());
        }
        return codes;
    }

    /** The grouped form shown to the owner, once. */
    static String format(String canonicalCode) {
        if (canonicalCode == null || canonicalCode.length() <= GROUP_LENGTH)
            return canonicalCode;
        return canonicalCode.substring(0, GROUP_LENGTH) + "-" + canonicalCode.substring(GROUP_LENGTH);
    }
}

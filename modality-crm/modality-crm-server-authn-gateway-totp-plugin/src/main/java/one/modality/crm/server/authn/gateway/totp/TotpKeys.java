package one.modality.crm.server.authn.gateway.totp;

import dev.webfx.platform.conf.Config;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.substitution.Substitutor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Loads the TOTP secret-encryption key(s) from configuration, once, at boot.
 *
 * <p>The state is static because two service providers need the same answer: the gateway (which
 * refuses enrolment without a key) and {@link TotpSecondFactorVerifier} (which must fail CLOSED
 * without one). They are separate {@code ServiceLoader} instances, so a per-instance field would
 * give the login path a different view of the world from the enrolment path — the kind of
 * disagreement that ends with a second factor being quietly skipped.
 *
 * <p>Decoding follows {@code SessionTokenKeysInitializer.addKeyIfPresent}, deliberately, including
 * its refusals:
 * <ul>
 *   <li>an unresolved {@code ${{ VAR }}} comes back as the literal template text, not null, and
 *       would otherwise become a perfectly usable AES key made of the words
 *       "TOTP_ENCRYPTION_KEY" — treated as absent;</li>
 *   <li>a value shorter than 32 bytes is REFUSED, never padded or hashed into usability: a server
 *       that encrypts every staff second factor under a four-character key looks entirely healthy
 *       and protects nothing;</li>
 *   <li>the value itself is never logged — only whether it was usable, and which fingerprint
 *       ({@link TotpSecretCipher#keyId()}) rows will now carry.</li>
 * </ul>
 *
 * <p>A value longer than 32 bytes is truncated to the first 32 (AES-256 takes exactly that) and the
 * truncation is said out loud, so an operator who pasted a 64-byte signing key learns that only
 * half of it is in use rather than discovering it during a rotation.
 *
 * @author Claude Code
 */
final class TotpKeys {

    private static final String LOG_PREFIX = "[totp] ";
    private static final String CURRENT_KEY_NAME = "encryptionKey";
    private static final String PREVIOUS_KEY_NAME = "previousEncryptionKey";
    /** AES-256. Below this the key is refused; above it, truncated. */
    private static final int KEY_BYTES = 32;

    /** Null until a usable key is configured — and that null is what makes the plugin fail closed. */
    private static volatile TotpSecretCipher cipher;

    private TotpKeys() {
    }

    /**
     * Reads both keys from the loaded configuration and installs the cipher, or leaves the plugin
     * inert with a loud reason. Called from the gateway's {@code boot()}; safe to call again when
     * the configuration is reloaded.
     */
    static void initFromConfig(Config config) {
        byte[] current = decodeKey(config == null ? null : config.getString(CURRENT_KEY_NAME), CURRENT_KEY_NAME);
        byte[] previous = decodeKey(config == null ? null : config.getString(PREVIOUS_KEY_NAME), PREVIOUS_KEY_NAME);
        if (current == null) {
            cipher = null;
            if (previous != null)
                // A rotation with only the OLD half configured: every existing row is still
                // readable, but nothing can be written, and the next paragraph's refusal applies
                Console.log(LOG_PREFIX + "⚠️ " + PREVIOUS_KEY_NAME + " is set but " + CURRENT_KEY_NAME
                            + " is not — the previous key alone is not enough to run on");
            // Said on every boot, because the failure this guards against is shipping it, believing
            // it is on, and never checking. Silence would read as success.
            Console.log(LOG_PREFIX + "🔒 TOTP is INERT — TOTP_ENCRYPTION_KEY is unset, unresolved or too short."
                        + " Enrolment is refused, and an account that already holds a TOTP row is treated as ENROLLED"
                        + " (its back-office login is refused rather than silently downgraded to password-only)."
                        + " A declare@ file only takes effect once `webfx update` has merged it into the server"
                        + " application's generated src-root.json.");
            return;
        }
        TotpSecretCipher loaded = new TotpSecretCipher(current, previous);
        cipher = loaded;
        Console.log(LOG_PREFIX + "🛡 TOTP second factor enabled (key " + loaded.keyId()
                    + (loaded.hasPreviousKey() ? ", previous key configured — rotation in flight" : "") + ")");
    }

    /** The cipher, or null when no usable key is configured. */
    static TotpSecretCipher cipher() {
        return cipher;
    }

    /** Whether a secret can be read or written at all — the plugin's on/off state. */
    static boolean isUsable() {
        return cipher != null;
    }

    private static byte[] decodeKey(String configuredKey, String keyName) {
        if (configuredKey == null || configuredKey.isBlank())
            return null;
        if (!Substitutor.areValuesNonNullAndResolved(configuredKey)) {
            Console.log(LOG_PREFIX + "⚠️ Ignoring " + keyName + ": its variable is not set");
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKey.trim());
        } catch (IllegalArgumentException e) {
            // Not base64: take the raw text as bytes rather than refuse outright, but hold it to the
            // same length bar, so a pasted-wrong value fails on length instead of passing quietly
            decoded = configuredKey.trim().getBytes(StandardCharsets.UTF_8);
        }
        if (decoded.length < KEY_BYTES) {
            Console.log(LOG_PREFIX + "⚠️ Ignoring " + keyName + ": needs at least " + KEY_BYTES
                        + " bytes, got " + decoded.length);
            return null;
        }
        if (decoded.length == KEY_BYTES)
            return decoded;
        Console.log(LOG_PREFIX + "⚠️ " + keyName + " is " + decoded.length + " bytes — using the first "
                    + KEY_BYTES + " (AES-256 takes exactly that)");
        byte[] truncated = new byte[KEY_BYTES];
        System.arraycopy(decoded, 0, truncated, 0, KEY_BYTES);
        return truncated;
    }
}

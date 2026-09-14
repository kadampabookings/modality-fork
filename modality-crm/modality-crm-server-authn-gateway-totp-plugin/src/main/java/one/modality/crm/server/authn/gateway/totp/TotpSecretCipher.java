package one.modality.crm.server.authn.gateway.totp;

import dev.webfx.platform.util.Numbers;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Encrypts and decrypts one TOTP secret at rest: AES-256-GCM, a fresh 12-byte nonce per row, and
 * the owning account id as additional authenticated data.
 *
 * <p><b>Why the AAD.</b> The secret is what the second factor IS — anyone holding it can produce
 * this account's codes forever. Authenticated encryption stops a dump from being usable, but on its
 * own it would not stop somebody with UPDATE rights from copying one account's {@code secret_enc}
 * onto another's row and then enrolling that phone as the victim's factor. Binding the ciphertext
 * to the account id makes that copy fail to decrypt, so the substitution is loud instead of silent.
 *
 * <p>The id is normalised through {@code Long} before it becomes AAD bytes, and this is
 * load-bearing rather than tidiness: account ids travel as {@code Object} and come back from the
 * session token as {@code Byte} or {@code Short} for small values ({@code ModalityUserPrincipal}'s
 * own javadoc spells this out), so {@code String.valueOf} on the raw object would produce the same
 * text for 7 and 7L but a different one for anything that stringifies differently — and an AAD that
 * depends on boxing would fail to decrypt a perfectly good row.
 *
 * <p><b>Rotation.</b> {@link #keyId()} fingerprints the key that wrote a row (the first 8 hex of
 * SHA-256 over the key bytes — four bytes of a hash of a 32-byte secret tells an attacker nothing
 * useful, and tells an operator which key a row needs). {@link #decrypt} tries the current key and
 * then the previous one, so a rotation deploys without a flag day.
 *
 * <p>Nothing here logs. A failure returns null and the caller decides what the user is told; the
 * key, the nonce and the secret never reach a log line.
 *
 * @author Claude Code
 */
final class TotpSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int NONCE_BYTES = 12;   // the GCM standard nonce size
    private static final int TAG_BITS = 128;     // the full tag; truncating it buys nothing here
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec currentKey;
    private final SecretKeySpec previousKey; // null unless a rotation is in flight
    private final String keyId;

    TotpSecretCipher(byte[] currentKey, byte[] previousKey) {
        this.currentKey = new SecretKeySpec(currentKey, KEY_ALGORITHM);
        this.previousKey = previousKey == null ? null : new SecretKeySpec(previousKey, KEY_ALGORITHM);
        this.keyId = fingerprint(currentKey);
    }

    /** Which key wrote a row — stored in {@code totp_credential.key_id}, safe to log and to show. */
    String keyId() {
        return keyId;
    }

    /** Whether a previous key is configured (for the boot log; never says what it is). */
    boolean hasPreviousKey() {
        return previousKey != null;
    }

    /**
     * {@code base64url(nonce ‖ ciphertext ‖ tag)} under the current key, or null if the platform
     * refuses the operation (it does not, but a null must not become a stored empty string).
     */
    String encrypt(byte[] secret, Object accountId) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            SECURE_RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(accountId));
            byte[] sealed = cipher.doFinal(secret);
            byte[] stored = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, stored, 0, nonce.length);
            System.arraycopy(sealed, 0, stored, nonce.length, sealed.length);
            return B64URL_ENCODER.encodeToString(stored);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The plaintext secret, or null when the row cannot be opened — wrong key, wrong account,
     * truncated value, tampered ciphertext. The caller cannot tell those apart, which is the point.
     */
    byte[] decrypt(String stored, Object accountId) {
        if (stored == null || stored.isBlank())
            return null;
        byte[] raw;
        try {
            raw = B64URL_DECODER.decode(stored.trim());
        } catch (RuntimeException e) {
            return null;
        }
        if (raw.length <= NONCE_BYTES)
            return null;
        byte[] plain = decryptWith(currentKey, raw, accountId);
        // Rotation: a row written by the previous key stays readable until it is re-encrypted
        return plain != null || previousKey == null ? plain : decryptWith(previousKey, raw, accountId);
    }

    private byte[] decryptWith(SecretKeySpec key, byte[] raw, Object accountId) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES));
            cipher.updateAAD(aad(accountId));
            return cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES);
        } catch (Exception e) {
            // Includes AEADBadTagException — the normal answer for "not this key" and for "not this
            // account". Swallowed on purpose: a caller that could tell them apart would be an oracle.
            return null;
        }
    }

    /** The account id as its decimal string, normalised through Long — see the class javadoc. */
    private static byte[] aad(Object accountId) {
        Long normalised = Numbers.toLong(accountId);
        String decimal = normalised != null ? Long.toString(normalised) : String.valueOf(accountId);
        return decimal.getBytes(StandardCharsets.UTF_8);
    }

    /** First 8 hex characters of SHA-256 over the key bytes — an identifier, not key material. */
    private static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++)
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return hex.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

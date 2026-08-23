package one.modality.base.server.rest.images;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Signs Bunny.net CDN "directory tokens" — the short-lived credential a browser must present
 * to read anything under a protected path on the pull zone.
 *
 * <p>Why this exists: volunteer applicant portraits live on the CDN, outside the database, and
 * until now anyone holding (or guessing) the URL could fetch one. The application form promises
 * applicants their photograph stays within the volunteer team. Turning on Token Authentication
 * for the {@code /volunteers/} prefix and handing out one-hour tokens to authenticated staff is
 * what makes that promise true at the CDN edge rather than merely in our own UI.
 *
 * <p>A <b>directory</b> token (as opposed to a per-file one) is signed over a path prefix, so a
 * single token covers every portrait a back-office list renders. The alternative — a token per
 * image — would mean a round-trip per row.
 *
 * <p>The token also has to survive Bunny's image optimizer: the clients append {@code width},
 * {@code height} and a cache-busting {@code t} to every image URL. Those are query parameters,
 * and Bunny folds query parameters into the signature unless told otherwise, so the signed URL
 * carries {@code token_ignore_params=true} and the parameters are left out of the hash input.
 *
 * <p>No IP is bound into the token: back-office users move between networks, and an IP-locked
 * token would break mid-session on a phone handover between wifi and mobile data.
 */
final class BunnyUrlTokenSigner {

    /** Query parameter names, as Bunny expects them on the URL. */
    static final String TOKEN_PARAM = "token";
    static final String EXPIRES_PARAM = "expires";
    static final String TOKEN_PATH_PARAM = "token_path";
    static final String IGNORE_PARAMS_PARAM = "token_ignore_params";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Mandatory prefix on an Advanced-token-authentication token. Bunny's scheme is
     * {@code "HS256-" + flags + Base64URL(HMAC-SHA256(...))}, where the flags segment is empty
     * when no IP is bound into the signature and {@code "1-"} when one is. We bind no IP, so the
     * prefix is exactly this. A token without it is rejected at the edge.
     */
    private static final String TOKEN_PREFIX = "HS256-";

    private BunnyUrlTokenSigner() {}

    /**
     * Produce the {@code token} value for a directory token.
     *
     * <p>Bunny's Advanced scheme is
     * {@code "HS256-" + flags + Base64URL(HMAC-SHA256(security_key, signature_path + expires +
     * user_ip + signing_data))}. We bind no IP, so {@code user_ip} and the flags segment are both
     * empty; and because the URL carries {@code token_ignore_params=true} the edge validates
     * without folding query parameters in, so {@code signing_data} is empty too. That last point
     * is load-bearing rather than cosmetic: every image URL this system builds carries the CDN
     * optimizer's {@code width}/{@code height} and a cache-busting {@code t}, none of which the
     * server could know in advance to sign.
     *
     * <p><b>Verify this against a real pull zone before relying on it.</b> The scheme cannot be
     * checked offline — there is no published test vector — and a wrong signature fails in the
     * quietest possible way: tokens are still issued, the browser still requests the image, and
     * the CDN simply refuses it. Enable token authentication on the staging zone and confirm a
     * signed URL returns 200 before switching it on in production.
     *
     * @param securityKey the pull zone's URL Token Authentication key (per-zone secret)
     * @param signaturePath the directory being granted, with leading and trailing slash — e.g.
     *                      {@code /volunteers/}
     * @param expiresEpochSeconds absolute expiry, in seconds since the epoch
     * @return the token string to put in the {@code token} query parameter
     * @throws IllegalStateException when the JVM lacks HMAC-SHA256, which cannot happen on a
     *         standard runtime and is not something a caller can handle
     */
    static String signDirectoryToken(String securityKey, String signaturePath, long expiresEpochSeconds) {
        String hashInput = signaturePath + expiresEpochSeconds;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(securityKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(hashInput.getBytes(StandardCharsets.UTF_8));
            // Base64URL per Bunny: '+' -> '-', '/' -> '_', padding removed.
            return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("Cannot sign Bunny CDN token", e);
        }
    }
}

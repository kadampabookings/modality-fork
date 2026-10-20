package one.modality.ecommerce.payment.spi.impl.server;

import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.session.token.SignedToken;

/**
 * The bearer token that authorizes the public resume-payment page, minted for one payment at initiation.
 *
 * <p><b>Why a token of its own, rather than the booking's cart uuid.</b> The cart uuid was the first answer
 * and it is three things this is not: shared by every payment on that cart, so it cannot say WHICH payment
 * the holder made; permanent, with no expiry; and a sign-in credential, because {@code authenticateWithCart}
 * mints a guest session from it — so a copy of it leaking from a browser's storage was worth a session, not a
 * receipt. This is per payment, expires, and is worth exactly one payment's confirmation.
 *
 * <p><b>Nothing is stored.</b> {@link SignedToken} signs with the server's existing rotating key, so the token
 * is verified by recomputing rather than by looking it up: no column, no migration, no anonymisation
 * classification, no retention question, and the expiry comes with the facility. The payload names the
 * payment, so a token minted for one payment cannot be presented for another.
 *
 * <p><b>It cannot be revoked individually</b>, being a pure function of the payment and the key. Acceptable
 * here and stated rather than discovered: what it protects is a receipt for a payment somebody made, and
 * "you paid this" does not stop being true. The expiry is what bounds it.
 *
 * @author Claude Code
 */
final class ResumePaymentToken {

    /**
     * How long a payer may come back and be told what happened.
     *
     * <p>Generous against the minutes a payment takes, and short against the cart uuid's forever. A payer who
     * bookmarks the confirmation and returns next week is refused and sent to their orders, which is the right
     * trade: the page exists for the moment after paying.
     */
    private static final long LIFETIME_MILLIS = 24 * 60 * 60 * 1000L;

    /*
     * TWO COUPLINGS this figure has, neither of them enforced here:
     *
     *  - It is NOT scaled by SessionLifetime.getScale(), so alarm mode — which shortens access windows
     *    system-wide — does not shorten this. Defensible, since what it opens is a receipt rather than a
     *    session, but it is a decision and not an oversight.
     *  - It must stay inside the previous-signing-key retention window. A key rotation that drops the
     *    previous key sooner than this refuses tokens that are still live, and the payer sees "we cannot
     *    show this payment here" with nothing in the logs about a key.
     */

    /** Namespaced, so a token minted here can never be verified as some other kind of signed token. */
    private static final String PAYLOAD_PREFIX = "resume-payment:";

    private ResumePaymentToken() {
    }

    /**
     * The payload for one payment, or null when the id is not a number.
     *
     * <p><b>NORMALISED, and that is the point of the method.</b> Minting takes the id from the entity layer
     * and verifying takes it off the wire, and the two need not be the same Java type — an id that arrives as
     * 123, 123L or 123.0 must build the same payload or no token would ever verify. A payload built from a
     * raw {@code toString()} would have worked in every test, where both sides are Integers, and refused
     * every payer the day the wire decoded a number differently.
     */
    private static String payloadFor(Object paymentPrimaryKey) {
        Long id = Numbers.toLong(paymentPrimaryKey);
        return id == null ? null : PAYLOAD_PREFIX + id;
    }

    /**
     * A token for this payment, or null when the server has no signing key.
     *
     * <p>Null rather than an unsigned placeholder: a caller that cannot mint must not pretend it did, and the
     * client treats a missing token as "this payment has no resume page" rather than as "no token needed".
     */
    static String mint(Object paymentPrimaryKey) {
        if (paymentPrimaryKey == null)
            return null;
        if (!SignedToken.isConfigured()) {
            Console.log("⚠️ No session token signing key: minting no resume-payment token, so the payer will"
                        + " be sent to their orders instead of a confirmation (webfx.stack.session.token)");
            return null;
        }
        String payload = payloadFor(paymentPrimaryKey);
        // An ABSOLUTE deadline, not a duration: SignedToken.mint takes epoch millis. Passing the lifetime
        // itself minted tokens that expired in January 1970 — they signed and decoded fine and every payer
        // was refused, which is the kind of wrong that ships green.
        return payload == null ? null : SignedToken.mint(payload, System.currentTimeMillis() + LIFETIME_MILLIS);
    }

    /**
     * Whether this token was minted for this payment and has not expired.
     *
     * <p><b>Fails CLOSED when there is no signing key.</b> An unconfigured server cannot verify, and a verifier
     * that cannot verify must refuse — the alternative is a page that silently stops checking, which is the
     * failure mode that would never be noticed.
     */
    static boolean isValidFor(String token, Object paymentPrimaryKey) {
        if (token == null || paymentPrimaryKey == null)
            return false;
        if (!SignedToken.isConfigured()) {
            Console.log("⚠️ No session token signing key: refusing every resume-payment token rather than"
                        + " skipping the check (webfx.stack.session.token)");
            return false;
        }
        String expected = payloadFor(paymentPrimaryKey);
        String payload = expected == null ? null : SignedToken.verify(token, System.currentTimeMillis());
        // Compared to the payload we would have minted, so a valid token for payment A is not a valid token
        // for payment B — the signature alone only says "we issued this", never "we issued it for this".
        return payload != null && payload.equals(expected);
    }
}

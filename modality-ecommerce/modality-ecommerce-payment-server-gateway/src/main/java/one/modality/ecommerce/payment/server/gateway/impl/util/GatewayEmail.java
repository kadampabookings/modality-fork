package one.modality.ecommerce.payment.server.gateway.impl.util;

import java.util.regex.Pattern;

/**
 * Shape check for an email address that is about to be handed to a payment gateway.
 *
 * <p>Gateways validate addresses themselves and reject the <b>whole API call</b> on a malformed
 * one — Stripe on {@code receipt_email}, Square on {@code buyer_email_address}. Those calls are
 * the payment itself, so junk in {@code person.email} (a free-text field, and staff have typed
 * things like "n/a" into it) must never reach them: a missing receipt is a far smaller failure
 * than a payer who cannot pay at all. Anything that doesn't look like an address is dropped here
 * instead, and the gateway call goes out without it.
 *
 * <p>Deliberately stricter than RFC 5322 — no quoted local parts, no IP-literal domains. Those
 * are legal addresses that no member has, and accepting them would only widen what we forward.
 * 254 characters is the practical maximum for an address and sits inside every gateway's own
 * limit (Square caps {@code buyer_email_address} at 255).
 *
 * @author Bruno Salmon
 */
public final class GatewayEmail {

    private static final int MAX_LENGTH = 254;

    private static final Pattern EMAIL_SHAPE = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+");

    private GatewayEmail() {}

    /**
     * Returns the trimmed value if it plausibly is an email address, otherwise null — so a caller
     * can write {@code if (email != null) request.setEmail(email)} and never send junk.
     */
    public static String emailOrNull(String email) {
        if (email == null)
            return null;
        String trimmed = email.trim();
        return trimmed.length() <= MAX_LENGTH && EMAIL_SHAPE.matcher(trimmed).matches() ? trimmed : null;
    }
}

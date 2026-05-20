package one.modality.crm.shared.services.authn;

/**
 * Credentials for requesting a "restore your booking" email.
 *
 * The server finds all booking carts linked to the given email address that
 * still have an active magic link (i.e. the guest has not yet created an account),
 * then sends an email containing /cart/:cartUuid links for each.
 *
 * Typical use case: a guest installed the PWA and their session was not carried
 * over (iOS Safari isolated storage), so they enter their email to receive a
 * fresh link to their booking cart.
 *
 * @author Bruno Salmon
 */
public record SendBookingAccessEmailCredentials(
    String email,
    String clientOrigin,
    String lang
) {
}

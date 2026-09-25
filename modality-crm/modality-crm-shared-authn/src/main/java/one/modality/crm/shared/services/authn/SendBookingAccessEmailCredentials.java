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
 * {@code clientOrigin} is IGNORED by the server, which links to its own configured
 * front office. It stays in the record only so clients already deployed keep
 * decoding; this request needs no sign-in, so an origin taken from it would let
 * anyone point a genuine KBS mail's button at their own host.
 *
 * @author Bruno Salmon
 */
public record SendBookingAccessEmailCredentials(
    String email,
    String clientOrigin,
    String lang
) {
}

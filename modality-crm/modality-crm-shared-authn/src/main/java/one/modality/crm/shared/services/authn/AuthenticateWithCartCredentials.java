package one.modality.crm.shared.services.authn;

/**
 * Credentials for authenticating a guest user via a booking cart UUID.
 *
 * When a guest visits /cart/:cartUuid, the cart is checked for an associated
 * BOOKING_ACCESS magic link. If one is found, this credential is used to
 * authenticate the guest as a ModalityGuestPrincipal without requiring them
 * to navigate to the magic-link route explicitly.
 *
 * @author Bruno Salmon
 */
public record AuthenticateWithCartCredentials(String cartUuid) {
}

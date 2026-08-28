package one.modality.crm.shared.services.authn;

/**
 * Redeems a back-office view token in the back office, opening it read-only as the target user.
 *
 * <p>Deliberately distinct from {@link AuthenticateWithSupportViewCredentials} even though both
 * carry a support-flavoured token: the two passes open different applications with different grant
 * scopes, so each redemption path accepts only its own link type and refuses the other. Sharing one
 * entry point would let a token minted for the weaker context be replayed into the stronger one.
 *
 * @author Claude Code
 */
public record AuthenticateWithBackOfficeViewCredentials(String token) {
}

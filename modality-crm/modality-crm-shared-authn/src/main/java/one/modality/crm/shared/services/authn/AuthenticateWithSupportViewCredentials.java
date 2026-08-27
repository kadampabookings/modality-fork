package one.modality.crm.shared.services.authn;

/**
 * Redeems a support-view token in the front office, opening the customer's account read-only.
 *
 * <p>Deliberately distinct from {@link dev.webfx.stack.authn.AuthenticateWithMagicLinkCredentials}
 * even though both carry a token: a magic link authenticates its bearer <em>as</em> the account
 * holder with full rights, whereas this authenticates a member of staff as a read-only visitor to
 * that account. Sharing one entry point would make the weaker outcome reachable through the
 * stronger path, so the two never meet.
 *
 * @author Claude Code
 */
public record AuthenticateWithSupportViewCredentials(String token) {
}

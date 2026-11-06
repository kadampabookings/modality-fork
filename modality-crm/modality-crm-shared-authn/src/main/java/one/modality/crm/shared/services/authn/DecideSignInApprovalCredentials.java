package one.modality.crm.shared.services.authn;

/**
 * A super administrator's decision on one pending second factor ({@code kind} = passkey | totp). Approving an
 * account's FIRST factor ends all its sessions; rejecting with {@code lockAccount} also ends them and closes its
 * password sign-in (suspected compromise). Never on one's own account.
 *
 * @author Claude Code
 */
public record DecideSignInApprovalCredentials(String kind, Object id, Boolean approve, Boolean lockAccount) {
}

package one.modality.crm.shared.services.authn;

/**
 * Asks the server for a one-time pass to view the back office as another back-office user.
 *
 * <p>The back-office sibling of {@link RequestSupportViewCredentials}, with a deliberately narrower
 * audience: only a super admin may request one, and only for a target whose account has back-office
 * access. There is no operation code to delegate this through — the server checks
 * {@code AuthorizationSuperAdmin} membership directly, so no role can ever be granted the ability.
 *
 * <p>As with the front-office pass, the reply is a short-lived, single-use token, never a credential
 * belonging to the target, and {@code targetPersonId} is a request rather than an instruction: the
 * server re-checks the caller, resolves the account itself, and refuses unsuitable targets.
 *
 * @author Claude Code
 */
public record RequestBackOfficeViewCredentials(Object targetPersonId) {
}

package one.modality.crm.shared.services.authn;

/**
 * Asks whether this account's password sign-in is closed ("Stop my password working", V0096), and whether it could be.
 *
 * <p>Sent on the {@code updateCredentials} path by the signed-in owner, never inside a support view. The reply is a
 * JSON object string {@code {"closed": boolean, "canClose": boolean}}: {@code canClose} is whether the account holds a
 * passkey that opens its way in, which closing requires — without one, closing would lock its owner out as surely as
 * disabling the account.
 *
 * @author Claude Code
 */
public record PasswordSignInStatusCredentials() {
}

package one.modality.crm.shared.services.authn;

/**
 * Stops this account's password working: password sign-in and every emailed way in (recovery, sign-in links and
 * codes) are refused, and the stored password is wiped, until the owner reopens it. The passkey keeps working.
 *
 * <p>Sent on the {@code updateCredentials} path by the signed-in owner, never inside a support view. Refused unless the
 * account holds a passkey that opens its way in. Idempotent: closing a closed account succeeds and changes nothing.
 *
 * @author Claude Code
 */
public record ClosePasswordSignInCredentials() {
}

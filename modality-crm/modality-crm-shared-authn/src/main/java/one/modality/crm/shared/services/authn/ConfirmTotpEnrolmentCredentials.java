package one.modality.crm.shared.services.authn;

/**
 * Confirms the TOTP enrolment started by {@link StartTotpEnrolmentCredentials} with a first code
 * from the authenticator app.
 *
 * <p>Sent on the {@code updateCredentials} path from the same account's session. The server checks
 * the code against the unconfirmed secret it stored, marks the row confirmed, and generates that
 * account's backup codes. The reply carries the credential, the backup codes ONCE — they are hashed
 * on the server and can never be shown again — and the refreshed listing.
 *
 * <p>The first correct code is what turns a stored secret into a factor: until it arrives the row is
 * never asked for at login, so a scan that silently failed cannot lock its owner out of the back office.
 *
 * @param code the current 6-digit code from the authenticator app
 *
 * @author Claude Code
 */
public record ConfirmTotpEnrolmentCredentials(String code) {
}

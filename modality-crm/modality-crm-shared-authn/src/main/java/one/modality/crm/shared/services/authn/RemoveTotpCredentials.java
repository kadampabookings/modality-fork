package one.modality.crm.shared.services.authn;

/**
 * Removes the authenticator app from the logged-in account, with its backup codes.
 *
 * <p>Sent on the {@code updateCredentials} path. Two things must hold, not one: {@code totpId} is
 * treated as a request rather than an instruction — the server deletes the row only when it belongs
 * to the caller's own account (ownership is part of the DELETE's WHERE clause) — and the caller must
 * quote a FRESH code from the app being removed.
 *
 * <p>The fresh code is what stops a borrowed session from stripping the factor: whoever holds the
 * session has the password at best, and removing the second factor behind the first alone would
 * leave the account defended by exactly one credential again.
 *
 * @param totpId the id of the credential row to remove, as listed by {@link ListSecondFactorsCredentials}
 * @param code   the current 6-digit code from the authenticator app
 *
 * @author Claude Code
 */
public record RemoveTotpCredentials(Object totpId, String code) {
}

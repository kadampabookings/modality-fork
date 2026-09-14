package one.modality.crm.shared.services.authn;

/**
 * Asks the server for the second factors registered on the logged-in account.
 *
 * <p>Sent on the {@code updateCredentials} path; the server answers only for the caller's own
 * account (resolved from the session principal — the client cannot name a target). The reply is a
 * JSON object string {@code {totp: {id, label, createdAt, confirmedAt, lastUsedAt} | null,
 * backupCodesLeft, passkeys: [...]}}: what the owner needs to manage their factors, and nothing
 * that could authenticate anyone. Secrets, backup codes and their hashes are never returned.
 *
 * <p>Carries no fields on purpose, like {@link ListPasskeysCredentials}: an id parameter here would
 * be an account-enumeration surface with no use case behind it.
 *
 * @author Claude Code
 */
public record ListSecondFactorsCredentials() {
}

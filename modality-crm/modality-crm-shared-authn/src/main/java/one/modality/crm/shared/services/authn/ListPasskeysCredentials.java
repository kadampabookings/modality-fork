package one.modality.crm.shared.services.authn;

/**
 * Asks the server for the list of passkeys registered on the logged-in account.
 *
 * <p>Sent on the {@code updateCredentials} path; the server answers only for the caller's own
 * account (resolved from the session principal — the client cannot name a target). The reply is a
 * JSON array string of credential summaries: id, label, aaguid, transports, createdAt, lastUsedAt.
 * Public keys and user handles are never returned.
 *
 * @author Claude Code
 */
public record ListPasskeysCredentials() {
}

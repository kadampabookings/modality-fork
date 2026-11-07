package one.modality.crm.shared.services.authn;

/**
 * A super administrator signing someone out: the one session {@code sessionId} of that account, or every session of
 * it when {@code sessionId} is null. Never one's own account; the note records why.
 *
 * @author Claude Code
 */
public record EndSessionsCredentials(Object accountId, String sessionId, String note) {
}

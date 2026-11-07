package one.modality.crm.shared.services.authn;

/**
 * A super administrator lifting their own kind of block ({@link BlockAccountCredentials}), on the strength of the
 * check recorded in the note. Never re-opens an account its owner closed after a theft.
 *
 * @author Claude Code
 */
public record UnblockAccountCredentials(Object accountId, String note) {
}

package one.modality.crm.shared.services.authn;

/**
 * A super administrator blocking a back-office account: nobody can sign in to it, and its sessions end, until a
 * super administrator unblocks it. Its devices are left as they are. Never one's own account; the note records why.
 *
 * @author Claude Code
 */
public record BlockAccountCredentials(Object accountId, String note) {
}

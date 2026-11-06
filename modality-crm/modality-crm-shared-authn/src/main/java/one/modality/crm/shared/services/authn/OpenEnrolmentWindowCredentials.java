package one.modality.crm.shared.services.authn;

/**
 * A super administrator reopening second-factor setup for 48 hours for one account the {@code required} deadline
 * locked out, after an out-of-band identity check recorded in {@code note}. Never on one's own account.
 *
 * @author Claude Code
 */
public record OpenEnrolmentWindowCredentials(Object accountId, String note) {
}

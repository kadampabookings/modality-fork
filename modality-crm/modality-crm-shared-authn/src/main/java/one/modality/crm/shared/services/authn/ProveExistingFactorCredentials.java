package one.modality.crm.shared.services.authn;

/**
 * A current code from the caller's APPROVED authenticator app, given on purpose so that a device added from this tab
 * in the next five minutes is approved without a super administrator. Backup codes are not accepted: they are the
 * recovery sheet, not a device. Answered by the TOTP gateway.
 *
 * @author Claude Code
 */
public record ProveExistingFactorCredentials(String code) {
}

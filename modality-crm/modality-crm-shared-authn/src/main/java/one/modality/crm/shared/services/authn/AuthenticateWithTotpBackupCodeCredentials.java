package one.modality.crm.shared.services.authn;

/**
 * Completes a back-office login step-up with one of the account's printed backup codes — the
 * everyday path for a phone left at home.
 *
 * <p>Sent on the {@code authenticate} path, on the same {@code runId} as the password step, and
 * answered exactly like {@link AuthenticateWithTotpCredentials}: the pending entry names the
 * account, the code is checked against that account's unused codes, and the session is minted with
 * {@code pwd,totp}. A code is single-use — consumed by the same statement that accepts it — and the
 * reply says how many remain, so nobody discovers they are out of codes at the moment they need one.
 *
 * <p>Backup codes are the recovery path for a lost device, not for a lost device AND lost codes:
 * that one is a super administrator's {@link ResetSecondFactorCredentials}, after an out-of-band
 * identity check. Never email — the channel this factor exists to stop relying on.
 *
 * @param code one of the backup codes shown once at enrolment or regeneration
 *
 * @author Claude Code
 */
public record AuthenticateWithTotpBackupCodeCredentials(String code) {
}

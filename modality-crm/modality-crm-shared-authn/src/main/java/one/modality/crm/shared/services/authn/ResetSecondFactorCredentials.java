package one.modality.crm.shared.services.authn;

/**
 * Clears a second factor from ANOTHER account, so its owner can enrol again after losing both the
 * device and the backup codes.
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator (membership, re-checked
 * server-side on every call — never an operation code, so nothing in the grant tables can delegate
 * it), and never on their own account. The server deletes the named factor, revokes that person's
 * live sessions — so a session opened by whoever caused the reset does not outlive it — and records
 * the reset with who decided it and how many sessions it ended.
 *
 * <p>There is no self-service reset, by design: one behind the password would make the second factor
 * one factor wearing a hat. The control that makes this safe is out of band and human — a call back
 * on a number the centre already holds, or in person — and {@code note} is where the administrator
 * writes what they checked. It is the record, so it is asked for and kept.
 *
 * @param accountId the frontend account whose factor is cleared, as listed in the back office
 * @param what      which factor: {@code "TOTP"} (the app and its backup codes) or {@code "BACKUP_CODES"} (the codes alone)
 * @param note      what the out-of-band identity check was (≤ 256 chars)
 *
 * @author Claude Code
 */
public record ResetSecondFactorCredentials(Object accountId, String what, String note) {
}

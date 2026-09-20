package one.modality.crm.shared.services.authn;

/**
 * Lets somebody back in after they reported a device stolen: re-enables the account and lifts the restriction the
 * panic button wrote ({@link ReportDeviceStolenCredentials}).
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator, whose membership is re-checked
 * server-side on every call, never inside a support view, and never for their own account. The note records the
 * out-of-band check they made — re-enabling an account on somebody's say-so is exactly the request a social
 * engineer would make — and is kept with the rescue in {@code second_factor_reset}, like every other rescue's.
 *
 * <p>It lifts only what the owner closed, never an account an administrator disabled by hand. The passkeys stay
 * rejected: the one on the stolen device is still on the stolen device, so the owner enrols again on the devices
 * they still have. The reply is whether a restriction was actually lifted.
 *
 * @param accountId the account to let back in
 * @param note      the super administrator's record of the check they made
 *
 * @author Claude Code
 */
public record RescueStolenAccountCredentials(Object accountId, String note) {
}

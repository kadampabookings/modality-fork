package one.modality.crm.shared.services.authn;

/**
 * The panic button: the device this account was signed in on is in somebody else's hands.
 *
 * <p>Disables the account, rejects every passkey it holds — the stolen device may hold one — and ends every
 * session of the person, the one sending this included. Signing the owner out too is the point rather than a side
 * effect: nothing here can tell which session is the thief's, and the premise of the control is that stopping them
 * matters more than keeping the owner working.
 *
 * <p>Sent on the {@code updateCredentials} path by the signed-in owner, never inside a support view, and refused
 * for an account without back-office access: self-service disabling with super-administrator recovery is
 * proportionate for staff, while for a member a mis-tap would mean a support queue and a lost booking.
 *
 * <p>The way back is {@link RescueStolenAccountCredentials}, which only a super administrator may send. The reply
 * is how many sessions were ended.
 *
 * @author Claude Code
 */
public record ReportDeviceStolenCredentials() {
}

package one.modality.crm.shared.services.authn;

/**
 * Removes the authenticator app from the logged-in account, with its backup codes.
 *
 * <p>Sent on the {@code updateCredentials} path. {@code totpId} is treated as a request rather than an
 * instruction: the server deletes the row only when it belongs to the caller's own account (ownership is
 * part of the DELETE's WHERE clause). A refused app stays on record until a super administrator resets it.
 *
 * <p>Removal asks for nothing more, as a passkey's does: whoever removes a factor gains no way in (a new
 * device needs the password, and as a first factor a super administrator's approval), while a code would
 * lock out the owners who most need to remove an app, those who lost the phone. An app that opens the back office
 * is removed only from a back-office session, which presented a factor — never from a front-office, setup-only or
 * recovery-only one, which proves the password or the mailbox at most.
 *
 * @param totpId the id of the credential row to remove, as listed by {@link ListSecondFactorsCredentials}
 * @param code   ignored since removal stopped asking for a code; kept so the wire format, and clients
 *               that still send one, are unchanged
 *
 * @author Claude Code
 */
public record RemoveTotpCredentials(Object totpId, String code) {
}

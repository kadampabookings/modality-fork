package one.modality.crm.shared.services.authn;

/**
 * Asks the server for every passkey awaiting back-office approval: those of back-office
 * accounts other than the caller's own (a super administrator never decides their own key).
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator; the server re-checks
 * that membership on every call and refuses everyone else. The reply is a JSON object string,
 * {@code {"approvalEnabled": bool, "pending": [...]}}: whether the server's approval switch is
 * on, and the pending credentials with the account they belong to (username), their label,
 * authenticator identity and registration time — what an approver needs to recognise a request,
 * and nothing that could authenticate anyone.
 *
 * <p>Why approval exists at all: a passkey enrolled behind a weak password would otherwise let
 * whoever cracked the password upgrade themselves to a phishing-resistant credential. Approval
 * is the out-of-band gate on BACK-OFFICE use of a passkey — front-office use needs none, since
 * it grants nothing the password did not already. The gate is a server switch
 * ({@code WEBAUTHN_BACKOFFICE_APPROVAL}), off while the account's {@code backoffice} flag alone
 * decides who enters and on once the passkey is the factor that matters; the queue is served
 * either way. See docs/security/backoffice-second-factor.md.
 *
 * @author Claude Code
 */
public record ListPendingPasskeysCredentials() {
}

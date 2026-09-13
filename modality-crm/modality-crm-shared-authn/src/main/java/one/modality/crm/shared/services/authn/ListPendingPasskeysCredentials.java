package one.modality.crm.shared.services.authn;

/**
 * Asks the server for every passkey awaiting back-office approval: those of back-office
 * accounts other than the caller's own (a super administrator never decides their own key).
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator; the server re-checks
 * that membership on every call and refuses everyone else. The reply is a JSON array string of
 * pending credentials with the account they belong to (username), their label, authenticator
 * identity and registration time — what an approver needs to recognise a request, and nothing
 * that could authenticate anyone.
 *
 * <p>Why approval exists at all: a passkey enrolled behind a weak password would otherwise let
 * whoever cracked the password upgrade themselves to a phishing-resistant credential. Approval
 * is the out-of-band gate on BACK-OFFICE use of a passkey — front-office use needs none, since
 * it grants nothing the password did not already. See docs/security/backoffice-second-factor.md.
 *
 * @author Claude Code
 */
public record ListPendingPasskeysCredentials() {
}

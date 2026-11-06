package one.modality.crm.shared.services.authn;

/**
 * The super-administrator approval queue for BOTH second factors — passkeys and authenticator apps waiting for a
 * decision, with the context an approver needs to judge them. Never lists the caller's own. Answered by the TOTP
 * gateway, re-checked as a super administrator on every call.
 *
 * @author Claude Code
 */
public record ListSignInApprovalsCredentials() {
}

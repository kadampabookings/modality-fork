package one.modality.crm.shared.services.authn;

/**
 * Asks the server to start a passkey (WebAuthn) login ceremony.
 *
 * <p>Sent anonymously on the {@code authenticate} path. The reply is a JSON string shaped like
 * {@code PublicKeyCredentialRequestOptions} (challenge, rpId, empty allowCredentials — the
 * discoverable-credential flow, so no username is typed) for the browser's
 * {@code navigator.credentials.get()} call.
 *
 * <p>Carries no fields on purpose: an account is never named before the authenticator has proven
 * possession, so this request cannot be used to probe whether an account has passkeys.
 *
 * @author Claude Code
 */
public record StartPasskeyAssertionCredentials() {
}

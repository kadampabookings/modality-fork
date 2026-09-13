package one.modality.crm.shared.services.authn;

/**
 * Completes a passkey (WebAuthn) registration ceremony started by {@link StartPasskeyRegistrationCredentials}.
 *
 * <p>Sent on the {@code updateCredentials} path by the same logged-in session that started the
 * ceremony. The server verifies the attestation response against the pending challenge (single-use,
 * short TTL) before storing the credential's public key.
 *
 * @param attestationObject the authenticator's attestation object, base64url without padding
 * @param clientDataJSON    the client data JSON bytes, base64url without padding
 * @param transports        the transports reported by the browser, comma-joined (e.g. "internal,hybrid"), may be null
 * @param label             optional user-chosen display name for the credential (≤ 64 chars), may be null
 *
 * @author Claude Code
 */
public record FinalisePasskeyRegistrationCredentials(String attestationObject, String clientDataJSON, String transports, String label) {
}

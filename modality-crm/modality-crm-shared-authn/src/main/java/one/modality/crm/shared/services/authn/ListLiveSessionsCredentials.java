package one.modality.crm.shared.services.authn;

/**
 * The signed-in sessions of staff (back-office) accounts now, person by person — the Sign-in security page's Signed-in
 * tab. Members' sessions are never listed. Capped by the server. No IP address or device: none is stored. Super
 * administrators only.
 *
 * @author Claude Code
 */
public record ListLiveSessionsCredentials() {
}

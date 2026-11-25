package one.modality.crm.shared.services.authn;

/**
 * A device asking for a session it cannot sign in for itself — the first step of the browser-approved device
 * login (docs/security/cli-device-login-design.md). Sent by a command-line client with no session at all.
 *
 * <p>The answer is JSON: a short code to print, the page to open, and how long it is good for. It is not a
 * session and it names nobody.
 *
 * <h3>It carries no identity, and must never be given one</h3>
 *
 * <p>Anyone can send this. The pending entry it creates deliberately holds nothing about who the caller
 * claims to be, because <b>who the session belongs to is decided entirely by the session that APPROVES</b>.
 * An attacker who floods the server with requests therefore learns nothing and can become nobody; the worst
 * they achieve is filling a capped store, which refuses rather than evicting.
 *
 * <p>It follows that a request cannot be refused for naming an account that is not enabled for this — there
 * is no account in it to check. The {@code device_login} flag is tested at APPROVAL, which is also what stops
 * this flow being used to enumerate which accounts are enabled.
 *
 * @param backoffice which arm the device is asking for. A REQUEST, not a grant: the audience minted comes
 *                   from the approving session, so a device that asks for the back office and is approved
 *                   from a recovery-only session gets the front office and had better notice. Recorded so
 *                   the approval page can say what is being asked for.
 *
 * @author Claude Code
 */
public record RequestDeviceLoginCredentials(boolean backoffice) {
}

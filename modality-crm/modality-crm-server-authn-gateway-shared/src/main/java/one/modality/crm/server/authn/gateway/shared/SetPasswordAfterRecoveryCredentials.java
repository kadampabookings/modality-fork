package one.modality.crm.server.authn.gateway.shared;

/**
 * A password change that a recovery flow has ALREADY authorised — the only kind allowed to arrive without
 * the old password.
 *
 * <h3>Why this type exists</h3>
 *
 * <p>Changing a password from a live session used to need no old password, on the reasoning that the
 * session proves who you are. It proves who you WERE when it was opened. A laptop taken unlocked carries a
 * session that is bounded — three hours idle, a day at most, and "sign out my other devices" ends it — and
 * a session that can set a password turns that into an account, unbounded until somebody notices. So a
 * change from a session now has to name the current password.
 *
 * <p>Recovery cannot: whoever forgot their password cannot type it. Its proof is different — a link or code
 * redeemed from the mailbox — and before this type existed it reached the password gateway as an ordinary
 * {@code UpdatePasswordCredentials} with a null old password, indistinguishable from a session trying to
 * skip the check. Requiring the old password there would have broken forgot-password for everyone.
 *
 * <h3>Why a separate type is safe, and what would make it unsafe</h3>
 *
 * <p>It has <b>no serial codec, and must never be given one.</b> Incoming bus arguments are decoded by
 * looking their {@code $codec} id up in a registry, and an unregistered id is refused — so no client
 * message can produce one of these. The only way to hold one is to be server code that constructs it, which
 * today means the magic-link gateway, after it has matched a link redeemed by this runId in the last 15
 * minutes to the very account that is signed in (a proof of the mailbox is a proof NOW, not forever). That is the
 * whole security boundary: registering a codec for this record, "for symmetry" or to make it testable over
 * the bus, would hand every client the ability to change a password without knowing it.
 *
 * <p>It does NOT bypass a closed password. A restriction the owner set (V0096) refuses this too — that is
 * what "stop my password working" closing recovery means in practice: the link can still be redeemed, but
 * it cannot put a password back.
 *
 * @param newPassword the password to set, in clear, exactly as {@code UpdatePasswordCredentials} carries it
 * @author Claude Code
 */
public record SetPasswordAfterRecoveryCredentials(String newPassword) {
}

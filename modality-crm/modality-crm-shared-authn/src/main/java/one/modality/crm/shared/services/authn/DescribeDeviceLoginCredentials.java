package one.modality.crm.shared.services.authn;

/**
 * What a device login with this code is asking for, so the approval page can say it before anybody approves
 * anything. Answered to an authenticated browser session only, and it consumes nothing.
 *
 * <h3>Why this is not an oracle</h3>
 *
 * <p>It does tell a caller that a code is live — but learning that costs the same work as guessing the code
 * in the first place, and a guesser who had one could simply approve it instead. Approving mints a session
 * for the APPROVER's own account, so a stranger who guesses a code hands their own session to somebody
 * else's terminal, which is not an attack on anyone but themselves.
 *
 * <p>What this must never reveal is anything about an ACCOUNT. It answers from the pending entry, which
 * holds no identity, so there is nothing about who is enabled for device login to leak — that check happens
 * at approval, for exactly this reason.
 *
 * <p>Unauthenticated callers are refused outright, so the oracle such as it is costs an attacker a working
 * back-office login before it tells them anything at all.
 *
 * @param code the code the operator typed, as typed: hyphens, spaces and case are normalised server-side
 *
 * @author Claude Code
 */
public record DescribeDeviceLoginCredentials(String code) {
}

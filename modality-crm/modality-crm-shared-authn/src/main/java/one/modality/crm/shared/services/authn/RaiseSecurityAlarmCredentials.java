package one.modality.crm.shared.services.authn;

/**
 * Raises alarm mode across the system for a fixed spell, or extends one already raised.
 *
 * <p>While it is in force every session's access window shortens to a couple of minutes, so every session in
 * the system exchanges its token — and therefore re-checks itself against the session store — many times more
 * often than is worth paying for normally. That is the whole of this first version: the other postures the
 * design calls for (step-up on sensitive operations, suspending magic-link login, louder identity logging) are
 * separate refusals, each wanting its own decision rather than riding in on this switch.
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator, whose membership is re-checked
 * server-side on every call, from a session this server established rather than an identity merely asserted.
 * It takes no duration: how long an alarm lasts is the server's to decide, so that a caller cannot raise one
 * that never lapses. Raising it again extends it.
 *
 * <p>There is no stand-down, by design. What is stored is the moment it ends, not a flag somebody has to
 * remember to turn off at the end of a long night. The reply is the expiry in force afterwards, in epoch
 * millis — the later of what this call asked for and what somebody else may have raised a moment ago.
 *
 * @author Claude Code
 */
public record RaiseSecurityAlarmCredentials() {
}

package one.modality.crm.shared.services.authn;

/**
 * Asks whether alarm mode is raised, and until when.
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator, whose membership is re-checked
 * server-side on every call. Only they may ask: the alarm is deliberately silent, so that an attacker holding
 * a stolen session learns nothing from the system behaving differently, and somebody who cannot raise it has
 * nothing to do with the answer.
 *
 * <p>The reply is JSON: {@code {"expiresAtMillis": 0}} when no alarm is in force, or the moment it lapses.
 *
 * @author Claude Code
 */
public record SecurityAlarmStatusCredentials() {
}

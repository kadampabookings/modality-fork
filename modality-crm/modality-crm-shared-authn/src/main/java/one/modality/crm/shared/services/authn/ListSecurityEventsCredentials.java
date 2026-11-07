package one.modality.crm.shared.services.authn;

/**
 * The last 90 days of sign-in security decisions, newest first: approvals and refusals, resets, sign-outs, blocks,
 * rescues and alarms — the Sign-in security page's Activity tab. Super administrators only.
 *
 * @author Claude Code
 */
public record ListSecurityEventsCredentials() {
}

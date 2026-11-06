package one.modality.crm.shared.services.authn;

/**
 * The caller's own second-factor standing in the back office: the policy, the deadline, approved and pending
 * factors, an open enrolment window, and whether this session is setup-only or recovery-only. Any signed-in caller;
 * it names no account (the account is the session's). Answered by the TOTP gateway.
 *
 * @author Claude Code
 */
public record SecondFactorStatusCredentials() {
}

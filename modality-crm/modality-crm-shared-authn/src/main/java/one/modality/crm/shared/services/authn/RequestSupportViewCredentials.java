package one.modality.crm.shared.services.authn;

/**
 * Asks the server for a one-time pass to view a customer's front office as they see it.
 *
 * <p>Sent by an authenticated back-office member. The reply is a short-lived, single-use token —
 * never a credential belonging to the customer. This is the whole point of the mechanism: support
 * gets a pass of its own, issued to a named person for a named target and expiring by itself,
 * instead of holding something that unlocks the account forever.
 *
 * <p>{@code targetPersonId} is the only thing the caller chooses, and the server treats it as a
 * request rather than an instruction: it re-checks the caller's permission, resolves the account
 * itself, and refuses targets that would escalate privilege.
 *
 * @author Claude Code
 */
public record RequestSupportViewCredentials(Object targetPersonId) {
}

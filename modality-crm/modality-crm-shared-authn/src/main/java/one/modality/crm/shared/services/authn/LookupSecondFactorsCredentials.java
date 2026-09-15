package one.modality.crm.shared.services.authn;

/**
 * Finds ONE account by its exact login email (or the person's exact email) and reports which second
 * factors it holds, so a super administrator can see what a locked-out member of staff is actually
 * carrying before deciding what to clear.
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator (membership re-checked
 * server-side on every call — never an operation code, so nothing in the grant tables can delegate
 * it). Everyone else gets the same generic management failure they would get for a malformed
 * request, so this cannot tell a non-administrator whether an address is known here.
 *
 * <p><b>Exact match only, never a search.</b> The server compares the whole trimmed string,
 * case-insensitively, against the account username and the person email — no prefix, no
 * {@code like}, no wildcard — so one call can confirm one address that the caller already had, and
 * cannot be walked across the member table. A super administrator seeing {@code found: false} for
 * an address that does not exist is expected and discloses nothing: they may read those rows in the
 * back office anyway.
 *
 * <p>The reply is a JSON object string:
 * {@code {"found": bool, "account": {"id", "username", "personName", "backoffice", "disabled"},
 * "totp": {"id", "label", "createdAt", "confirmedAt", "lastUsedAt"}, "backupCodesLeft": N}} — where
 * {@code account} is absent unless {@code found} is true, {@code totp} is absent when the account
 * holds no TOTP row, and every optional field inside either object is absent rather than null.
 * Passkeys are NOT here: they live in another plugin's table and are asked for separately with
 * {@link ListAccountPasskeysCredentials}, using the {@code account.id} this call returns.
 *
 * <p>One more key appears, and only when it is true: {@code "ambiguous": true} alongside
 * {@code found: false}, meaning the address is a CONTACT email on more than one account. The server
 * will not pick between them — choosing on a tiebreak would let an approver clear the factors of the
 * account its owner does not log in with — so the answer is "search by the login username instead".
 * A client that ignores the key reads it as a plain miss, which shows no account and therefore
 * clears none. It can never accompany a match on the login username, which belongs to one account.
 *
 * <p>{@code personName} is there for one purpose — letting the approver confirm out of band that
 * they have the right human before clearing anything. Nothing here can authenticate anybody: no
 * secret, no code, no hash.
 *
 * @param email the exact login email or person email to look up (trimmed and compared case-insensitively)
 *
 * @author Claude Code
 */
public record LookupSecondFactorsCredentials(String email) {
}

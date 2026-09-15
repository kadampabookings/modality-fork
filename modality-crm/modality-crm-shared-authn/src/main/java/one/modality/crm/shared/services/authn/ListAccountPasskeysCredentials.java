package one.modality.crm.shared.services.authn;

/**
 * Lists ANOTHER account's passkeys, so a super administrator can see — and then withdraw — the
 * credentials a locked-out member of staff still holds.
 *
 * <p>Sent on the {@code updateCredentials} path by a super administrator (membership re-checked
 * server-side on every call; never delegable through an operation code). Everyone else is refused
 * with the same "only a super administrator" error every other operation in that queue uses —
 * before the account id is looked at, so the refusal says nothing about whether it exists. This is
 * not a way for an ordinary account to read anybody else's credentials, nor to probe for accounts.
 * An id that matches nothing answers, to a super administrator, with an empty list — the same as a
 * real account holding no passkeys.
 *
 * <p>The account id is the one {@link LookupSecondFactorsCredentials} returned, not a number the
 * approver typed: the two calls together are the rescue screen. The reply is a JSON object string
 * {@code {"passkeys": [{"id", "label", "status", "aaguid", "transports", "createdAt", "lastUsedAt"}]}}
 * — the same per-row shape the owner's own {@link ListPasskeysCredentials} emits, so one client
 * parser serves both; {@code id} and {@code status} are always present and the rest are absent when
 * the row has no value for them. Never the credential id, never the public key.
 *
 * <p>Each row is then acted on with {@link RevokeApprovedPasskeyCredentials}, which refuses a
 * credential for back-office use from now on.
 *
 * @param accountId the frontend account whose passkeys are listed
 *
 * @author Claude Code
 */
public record ListAccountPasskeysCredentials(Object accountId) {
}

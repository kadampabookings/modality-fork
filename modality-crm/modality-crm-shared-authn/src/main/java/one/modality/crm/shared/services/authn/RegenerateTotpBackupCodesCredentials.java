package one.modality.crm.shared.services.authn;

/**
 * Issues a fresh generation of backup codes for the logged-in account's authenticator app.
 *
 * <p>Sent on the {@code updateCredentials} path, behind a FRESH code from the app — the same
 * reasoning as {@link RemoveTotpCredentials}: a session alone must not be able to mint a new set of
 * standing credentials for the account. The previous generation is deleted as the new one is
 * inserted, so codes printed earlier stop working the moment the new list is shown.
 *
 * <p>The reply carries the new codes ONCE. The server keeps only salted hashes, so a list closed
 * without being saved is gone — the owner regenerates.
 *
 * @param code the current 6-digit code from the authenticator app
 *
 * @author Claude Code
 */
public record RegenerateTotpBackupCodesCredentials(String code) {
}

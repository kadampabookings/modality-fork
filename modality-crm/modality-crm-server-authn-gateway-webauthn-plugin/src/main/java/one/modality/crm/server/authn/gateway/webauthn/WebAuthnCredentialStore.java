package one.modality.crm.server.authn.gateway.webauthn;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.util.ArrayList;
import java.util.List;

/**
 * All SQL access to the {@code webauthn_credential} table (created by migration V0084).
 *
 * <p>The table is deliberately NOT in the domain model (like bo_device/V0078 and
 * person_account_move/V0063): generic client DQL cannot reach it, so ownership is enforced solely
 * here — every delete/rename carries {@code AND frontend_account_id = caller} in its WHERE clause —
 * and no KBS2-side DomainModel snapshot regeneration is needed. Raw SQL therefore uses
 * {@code language = null} (bypasses DQL compilation, both interceptors check for it) with native
 * {@code $n} placeholders; precedents: ServerQueryPushServiceProviderBase.rawSqlQuery for reads,
 * ModalityWebPushSubscriptionStore for SubmitService writes. Raw-SQL results are read by column
 * POSITION, in SELECT order — they carry values, not usable column names.
 *
 * @author Claude Code
 */
final class WebAuthnCredentialStore {

    /** One credential row as needed at assertion time (lookup by credential id). */
    record CredentialRow(long id, Object accountId, String publicKeyCose, long signCount, String userHandle) {
    }

    /** One credential row as shown by the management UI (never the key material). */
    record CredentialSummary(long id, String credentialId, String userHandle, String label, String aaguid,
                             String transports, Object createdAt, Object lastUsedAt) {
    }

    // SELECT column positions, in SELECT order (raw SQL ⇒ read by position)
    private static final String SELECT_BY_ACCOUNT_SQL =
        "SELECT id, credential_id, user_handle, label, aaguid, transports, created_at, last_used_at" +
        " FROM webauthn_credential WHERE frontend_account_id = $1 ORDER BY id";
    private static final int A_ID = 0, A_CREDENTIAL_ID = 1, A_USER_HANDLE = 2, A_LABEL = 3, A_AAGUID = 4,
        A_TRANSPORTS = 5, A_CREATED_AT = 6, A_LAST_USED_AT = 7;

    private static final String SELECT_BY_CREDENTIAL_ID_SQL =
        "SELECT id, frontend_account_id, public_key_cose, sign_count, user_handle" +
        " FROM webauthn_credential WHERE credential_id = $1";
    private static final int C_ID = 0, C_ACCOUNT_ID = 1, C_PUBLIC_KEY = 2, C_SIGN_COUNT = 3, C_USER_HANDLE = 4;

    private static final String INSERT_SQL =
        "INSERT INTO webauthn_credential" +
        " (frontend_account_id, credential_id, public_key_cose, sign_count, user_handle, transports, aaguid, label)" +
        " VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";

    // GREATEST keeps the stored counter monotonic even under the warn-and-accept regression policy
    private static final String UPDATE_USAGE_SQL =
        "UPDATE webauthn_credential SET sign_count = GREATEST(sign_count, $1), last_used_at = now() WHERE id = $2";

    // Ownership lives IN the statement: a valid id belonging to someone else matches zero rows.
    // The lowercase " returning id" is load-bearing: SubmitResult.getRowCount() counts result SETS
    // (always 1 for a single statement — VertxSqlUtil.toWebFxSubmitResult), so affected-vs-not can
    // only be observed through the generated keys that a " returning " statement populates: one key
    // when the row matched, none when it did not.
    private static final String DELETE_OWNED_SQL =
        "DELETE FROM webauthn_credential WHERE id = $1 AND frontend_account_id = $2 returning id";
    private static final String RENAME_OWNED_SQL =
        "UPDATE webauthn_credential SET label = $1 WHERE id = $2 AND frontend_account_id = $3 returning id";

    /** Every passkey of one account, oldest first — feeds both the management list and excludeCredentials. */
    Future<List<CredentialSummary>> findByAccount(Object accountId) {
        return executeRawQuery(SELECT_BY_ACCOUNT_SQL, new Object[]{accountId})
            .map(result -> {
                List<CredentialSummary> summaries = new ArrayList<>(result.getRowCount());
                for (int row = 0; row < result.getRowCount(); row++) {
                    summaries.add(new CredentialSummary(
                        longValue(result.getValue(row, A_ID)),
                        stringValue(result.getValue(row, A_CREDENTIAL_ID)),
                        stringValue(result.getValue(row, A_USER_HANDLE)),
                        stringValue(result.getValue(row, A_LABEL)),
                        stringValue(result.getValue(row, A_AAGUID)),
                        stringValue(result.getValue(row, A_TRANSPORTS)),
                        result.getValue(row, A_CREATED_AT),
                        result.getValue(row, A_LAST_USED_AT)));
                }
                return summaries;
            });
    }

    /** The row for one credential id, or null — the assertion-time lookup. */
    Future<CredentialRow> findByCredentialId(String credentialIdB64) {
        return executeRawQuery(SELECT_BY_CREDENTIAL_ID_SQL, new Object[]{credentialIdB64})
            .map(result -> {
                if (result.getRowCount() != 1)
                    return null;
                return new CredentialRow(
                    longValue(result.getValue(0, C_ID)),
                    result.getValue(0, C_ACCOUNT_ID),
                    stringValue(result.getValue(0, C_PUBLIC_KEY)),
                    longValue(result.getValue(0, C_SIGN_COUNT)),
                    stringValue(result.getValue(0, C_USER_HANDLE)));
            });
    }

    Future<?> insert(Object accountId, String credentialIdB64, String publicKeyCoseB64, long signCount,
                     String userHandleB64, String transports, String aaguid, String label) {
        return executeRawSubmit(INSERT_SQL,
            accountId, credentialIdB64, publicKeyCoseB64, signCount, userHandleB64, transports, aaguid, label);
    }

    /** Fire-and-forget usage stamp after a successful assertion; a failure must not fail the login. */
    Future<?> updateUsage(long id, long newSignCount) {
        return executeRawSubmit(UPDATE_USAGE_SQL, newSignCount, id);
    }

    /** Deletes the row only when it belongs to the account; resolves to whether a row was deleted. */
    Future<Boolean> deleteOwned(long id, Object accountId) {
        return executeRawSubmit(DELETE_OWNED_SQL, id, accountId).map(WebAuthnCredentialStore::returnedARow);
    }

    /** Renames the row only when it belongs to the account; resolves to whether a row was renamed. */
    Future<Boolean> renameOwned(long id, Object accountId, String label) {
        return executeRawSubmit(RENAME_OWNED_SQL, label, id, accountId).map(WebAuthnCredentialStore::returnedARow);
    }

    private Future<QueryResult> executeRawQuery(String sql, Object[] parameters) {
        // language = null ⇒ raw SQL (no DQL compilation); sendMetadata = false — we read by position
        return QueryService.executeQuery(new QueryArgument(
            null, DataSourceModelService.getDefaultDataSourceId(), null, null, sql, parameters, null, false, false));
    }

    private Future<SubmitResult> executeRawSubmit(String sql, Object... parameters) {
        return SubmitService.executeSubmit(SubmitArgument.builder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql) // no setLanguage ⇒ raw SQL, native $n placeholders
            .setParameters(parameters)
            .build());
    }

    /** Whether a " returning " statement actually returned a row — the real affected-row signal. */
    private static boolean returnedARow(SubmitResult submitResult) {
        Object[] generatedKeys = submitResult.getGeneratedKeys();
        return generatedKeys != null && generatedKeys.length > 0;
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}

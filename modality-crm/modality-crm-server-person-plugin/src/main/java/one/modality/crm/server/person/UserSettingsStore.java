package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

/**
 * Reads and patches one account's {@code account_settings} document (V0158).
 *
 * <p>Raw SQL, because the table is deliberately absent from the domain model: no client DQL can name it, so the
 * two userSettings endpoints are the only way in, and every statement here names the single account the caller's
 * session belongs to. The account id is never taken from a request.
 *
 * @author Claude Code
 */
final class UserSettingsStore {

    private UserSettingsStore() {
    }

    /** No row is the same as an empty document: an account that never saved a setting has its defaults. */
    static final String EMPTY = "{}";

    private static final String LOAD_SQL =
        "select settings::text from account_settings where account_id = $1";

    /**
     * Merges a patch in ONE statement, so two devices changing different keys at the same moment both keep their
     * change: the second waits on the first's row lock and then merges into the row the first wrote.
     *
     * <p>{@code ||} writes a key sent as JSON null as null, and {@code jsonb_strip_nulls} then removes it — that
     * is how a key is deleted. On the insert branch the same function drops nulls from a first patch.
     *
     * <p>{@code $2::text::jsonb}, not {@code $2::jsonb}: the driver infers a parameter's type from its cast, and a
     * bare jsonb cast can make it demand a JSON object rather than accept the String this passes (the trap a
     * {@code ::date} cast set elsewhere). Through text, a String is what it expects.
     *
     * <p>The CHECKs on the table (an object, at most 8 KB) are enforced on the MERGED document, which is the only
     * place its size is known; a patch that would push it over fails as a whole.
     */
    private static final String PATCH_SQL =
        "insert into account_settings (account_id, settings)"
        + " values ($1, jsonb_strip_nulls($2::text::jsonb))"
        + " on conflict (account_id) do update"
        + " set settings = jsonb_strip_nulls(account_settings.settings || $2::text::jsonb), updated_at = now()"
        + " returning settings::text";

    /** The account's document as JSON text. Run as the server: a read has nothing to audit. */
    static Future<String> load(Object accountId) {
        return ServerWrite.asServer(() -> QueryService.executeQuery(QueryArgument.builder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(LOAD_SQL) // no setLanguage => raw SQL, native $n placeholders
                .setParameters(normaliseId(accountId))
                .build()))
            .map(UserSettingsStore::documentOf);
    }

    /**
     * Applies a patch already validated by {@link UserSettingsRules#patchJsonOf} and returns the merged document.
     *
     * @param callerUserId the caller's principal, read on the caller's own thread — writes in this package carry
     *                     it (see {@link ServerWrite})
     */
    static Future<String> patch(Object accountId, String patchJson, Object callerUserId) {
        return ServerWrite.asServerActingFor(callerUserId, () -> SubmitService.executeSubmit(SubmitArgument.builder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(PATCH_SQL)
                .setParameters(normaliseId(accountId), patchJson)
                .build()))
            .map(UserSettingsStore::returnedDocumentOf);
    }

    private static String documentOf(QueryResult result) {
        Object value = result == null || result.getRowCount() == 0 ? null : result.getValue(0, 0);
        return value == null ? EMPTY : value.toString();
    }

    /** The provider puts column 0 of the first RETURNING row in the generated keys. */
    private static String returnedDocumentOf(SubmitResult result) {
        Object[] keys = result == null ? null : result.getGeneratedKeys();
        Object value = keys == null || keys.length == 0 ? null : keys[0];
        if (value == null) // an upsert always returns its row; nothing back means the write did not happen
            throw new IllegalStateException("account_settings patch returned no row");
        return value.toString();
    }

    /**
     * Widens a small id to a Long before it reaches the driver: an id read from a session principal can come back
     * as a Byte or Short, which the pg driver's raw Tuple binding refuses (AccountSignInRestrictionStore records
     * the same trap).
     */
    private static Object normaliseId(Object id) {
        Long normalised = Numbers.toLong(id);
        return normalised != null ? normalised : id;
    }
}

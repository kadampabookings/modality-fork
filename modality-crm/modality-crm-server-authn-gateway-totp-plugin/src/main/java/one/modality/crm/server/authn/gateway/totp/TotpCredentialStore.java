package one.modality.crm.server.authn.gateway.totp;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * All SQL access to {@code totp_credential}, {@code totp_backup_code} and
 * {@code second_factor_reset} (created by migration V0091).
 *
 * <p>Same stance as {@code WebAuthnCredentialStore}: the tables are deliberately NOT in the domain
 * model, so generic client DQL cannot reach them and ownership is enforced solely here — every
 * statement that can change a row carries {@code AND frontend_account_id = caller} in its WHERE
 * clause. Raw SQL therefore uses {@code language = null} (bypasses DQL compilation) with native
 * {@code $n} placeholders, and raw-SQL results are read by column POSITION, in SELECT order.
 *
 * <p>The lowercase {@code " returning id"} on every statement whose outcome matters is load-bearing
 * rather than decoration: {@code SubmitResult.getRowCount()} counts result SETS (always 1 for a
 * single statement), so affected-vs-not can only be observed through the generated keys a
 * {@code returning} statement populates.
 *
 * <p><b>Secrets never leave this class in the clear.</b> {@code secret_enc} is handed out as stored
 * and opened by {@link TotpSecretCipher}; backup codes are stored only as a salted SHA-256, and the
 * hashing lives here so the write side and the compare side cannot drift apart.
 *
 * <p>Account ids are expected already normalised to {@code Long} by the caller: the pg driver's raw
 * Tuple binding refuses the {@code Byte}/{@code Short} boxing that ids deserialized from a session
 * token can carry (see {@code ModalityUserPrincipal}'s javadoc).
 *
 * @author Claude Code
 */
final class TotpCredentialStore {

    /** Self-service enrolment, kept for the future approval gate — V0091's CHECK allows the rest. */
    static final String STATUS_APPROVED = "APPROVED";

    /** The reason written on every session the reset ends; matched by the revoke statement below. */
    private static final String REVOKED_REASON = "second-factor-reset";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();
    private static final int SALT_BYTES = 16;

    /** One TOTP factor as the gateway needs it; {@code secretEnc} is still sealed. */
    record TotpRow(long id, String secretEnc, String keyId, String status, String label,
                   Object createdAt, Object confirmedAt, Object lastUsedAt, long lastUsedStep) {
        /** A factor is a factor only once a first correct code has proved the phone holds it. */
        boolean isConfirmed() {
            return confirmedAt != null;
        }
    }

    /** One unused backup code row, as needed to compare a typed code against it. */
    private record BackupCodeRow(long id, String codeHash, String salt) {
    }

    // ===== totp_credential =======================================================================

    // SELECT column positions, in SELECT order (raw SQL ⇒ read by position)
    private static final String SELECT_BY_ACCOUNT_SQL =
        "SELECT id, secret_enc, key_id, status, label, created_at, confirmed_at, last_used_at, last_used_step" +
        " FROM totp_credential WHERE frontend_account_id = $1";
    private static final int T_ID = 0, T_SECRET = 1, T_KEY_ID = 2, T_STATUS = 3, T_LABEL = 4,
        T_CREATED_AT = 5, T_CONFIRMED_AT = 6, T_LAST_USED_AT = 7, T_LAST_USED_STEP = 8;

    // frontend_account_id is UNIQUE, so a new enrolment can only land once the previous attempt is
    // gone. Only an UNCONFIRMED row is cleared: a confirmed factor is removed through the deliberate
    // remove-with-a-fresh-code path, never as a side effect of somebody starting an enrolment.
    private static final String DELETE_UNCONFIRMED_SQL =
        "DELETE FROM totp_credential WHERE frontend_account_id = $1 AND confirmed_at IS NULL";
    private static final String INSERT_UNCONFIRMED_SQL =
        "INSERT INTO totp_credential (frontend_account_id, secret_enc, key_id, status, label) VALUES ($1, $2, $3, $4, $5)";

    private static final String CONFIRM_SQL =
        "UPDATE totp_credential SET confirmed_at = now() WHERE id = $1 AND frontend_account_id = $2" +
        " AND confirmed_at IS NULL returning id";

    // The replay guard. A step is accepted at most once, the ±1 drift window included: the UPDATE
    // only matches while the stored step is strictly behind the one just verified, so a code
    // replayed inside its own 30 seconds — the whole window a relay proxy has — changes no row and
    // the caller reads that as a failed code.
    private static final String RECORD_USE_SQL =
        "UPDATE totp_credential SET last_used_step = $1, last_used_at = now() WHERE id = $2" +
        " AND last_used_step < $1 returning id";

    // Keyed on the account, not on a client-supplied row id: one TOTP per account, so the account
    // IS the ownership check and no id from the wire ever selects the row to delete.
    private static final String DELETE_OWNED_SQL =
        "DELETE FROM totp_credential WHERE frontend_account_id = $1 returning id";

    /** The account's TOTP factor, or null. */
    Future<TotpRow> findByAccount(Object accountId) {
        return executeRawQuery(SELECT_BY_ACCOUNT_SQL, new Object[]{accountId})
            .map(result -> {
                if (result.getRowCount() != 1)
                    return null;
                return new TotpRow(
                    longValue(result.getValue(0, T_ID)),
                    stringValue(result.getValue(0, T_SECRET)),
                    stringValue(result.getValue(0, T_KEY_ID)),
                    stringValue(result.getValue(0, T_STATUS)),
                    stringValue(result.getValue(0, T_LABEL)),
                    result.getValue(0, T_CREATED_AT),
                    result.getValue(0, T_CONFIRMED_AT),
                    result.getValue(0, T_LAST_USED_AT),
                    longValue(result.getValue(0, T_LAST_USED_STEP)));
            });
    }

    /** Stores a freshly minted, still unconfirmed secret, replacing any unconfirmed attempt. */
    Future<Void> insertUnconfirmed(Object accountId, String secretEnc, String keyId, String label) {
        return executeRawSubmit(DELETE_UNCONFIRMED_SQL, accountId)
            .compose(ignored -> executeRawSubmit(INSERT_UNCONFIRMED_SQL, accountId, secretEnc, keyId, STATUS_APPROVED, label))
            .mapEmpty();
    }

    /** Marks the factor confirmed; false when it was already confirmed or is not this account's. */
    Future<Boolean> confirm(long id, Object accountId) {
        return executeRawSubmit(CONFIRM_SQL, id, accountId).map(TotpCredentialStore::returnedARow);
    }

    /** The replay guard: true when this step had not been used yet, false when it had. */
    Future<Boolean> recordUse(long id, long step) {
        return executeRawSubmit(RECORD_USE_SQL, step, id).map(TotpCredentialStore::returnedARow);
    }

    /** Removes the account's TOTP factor; false when it had none. */
    Future<Boolean> deleteOwned(Object accountId) {
        return executeRawSubmit(DELETE_OWNED_SQL, accountId).map(TotpCredentialStore::returnedARow);
    }

    // ===== totp_backup_code ======================================================================

    private static final String SELECT_UNUSED_SQL =
        "SELECT id, code_hash, salt FROM totp_backup_code WHERE frontend_account_id = $1 AND used_at IS NULL ORDER BY id";
    private static final int B_ID = 0, B_CODE_HASH = 1, B_SALT = 2;

    private static final String COUNT_UNUSED_SQL =
        "SELECT count(*) FROM totp_backup_code WHERE frontend_account_id = $1 AND used_at IS NULL";

    // Generations are monotonic so a support conversation can say which sheet a person is holding;
    // read BEFORE the delete below, which is why it is a statement of its own.
    private static final String NEXT_GENERATION_SQL =
        "SELECT COALESCE(MAX(generation), 0) + 1 FROM totp_backup_code WHERE frontend_account_id = $1";

    private static final String DELETE_ALL_CODES_SQL =
        "DELETE FROM totp_backup_code WHERE frontend_account_id = $1";

    // Single-use, and observably so: a second caller racing on the same code finds used_at already
    // set and matches no row.
    private static final String CONSUME_SQL =
        "UPDATE totp_backup_code SET used_at = now() WHERE id = $1 AND used_at IS NULL returning id";

    /**
     * Replaces the account's backup codes with a new generation — "regenerate = insert generation
     * n+1, delete generation n" — and answers which generation that is. The old codes go in the
     * same call: two live sheets would mean a lost one still works.
     */
    Future<Integer> insertGeneration(Object accountId, List<String> codes) {
        // Refused rather than run: the INSERT below is built from the list, and an empty one would
        // produce "VALUES " with nothing after it — a malformed statement instead of a clear no.
        if (codes == null || codes.isEmpty())
            return Future.failedFuture("insertGeneration called with no codes");
        return executeRawQuery(NEXT_GENERATION_SQL, new Object[]{accountId})
            .map(result -> result.getRowCount() == 1 ? (int) longValue(result.getValue(0, 0)) : 1)
            .compose(generation -> {
                List<Object> parameters = new ArrayList<>(2 + codes.size() * 2);
                parameters.add(accountId);
                parameters.add(generation);
                StringBuilder values = new StringBuilder();
                for (int i = 0; i < codes.size(); i++) {
                    byte[] salt = new byte[SALT_BYTES];
                    SECURE_RANDOM.nextBytes(salt);
                    parameters.add(hash(salt, codes.get(i)));
                    parameters.add(B64URL_ENCODER.encodeToString(salt));
                    // $1 and $2 are shared by every tuple; each code contributes its own pair
                    values.append(i == 0 ? "" : ",")
                        .append("($1, $").append(3 + i * 2).append(", $").append(4 + i * 2).append(", $2)");
                }
                String insertSql = "INSERT INTO totp_backup_code (frontend_account_id, code_hash, salt, generation) VALUES " + values;
                return executeRawSubmit(DELETE_ALL_CODES_SQL, accountId)
                    .compose(ignored -> executeRawSubmit(insertSql, parameters.toArray()))
                    .map(ignored -> generation);
            });
    }

    /** How many of the account's codes are still unused — what the owner's list shows. */
    Future<Integer> countUnused(Object accountId) {
        return executeRawQuery(COUNT_UNUSED_SQL, new Object[]{accountId})
            .map(result -> result.getRowCount() == 1 ? (int) longValue(result.getValue(0, 0)) : 0);
    }

    /**
     * Consumes one of the account's unused backup codes; false when the code matches none.
     *
     * <p>The compare happens in Java over the account's own unused rows because each row carries its
     * own salt: a WHERE on the hash would need the salt to find the row whose salt it is. Every
     * candidate is compared with {@link MessageDigest#isEqual} and the loop does not stop early, so
     * a code that matches the first row is not measurably quicker than one that matches nothing.
     */
    Future<Boolean> consumeUnused(Object accountId, String typedCode) {
        String canonical = canonicalCode(typedCode);
        if (canonical == null)
            return Future.succeededFuture(false);
        return executeRawQuery(SELECT_UNUSED_SQL, new Object[]{accountId})
            .compose(result -> {
                List<BackupCodeRow> rows = new ArrayList<>(result.getRowCount());
                for (int row = 0; row < result.getRowCount(); row++)
                    rows.add(new BackupCodeRow(
                        longValue(result.getValue(row, B_ID)),
                        stringValue(result.getValue(row, B_CODE_HASH)),
                        stringValue(result.getValue(row, B_SALT))));
                long matchedId = -1;
                for (BackupCodeRow row : rows)
                    if (matchesHash(row, canonical))
                        matchedId = row.id();
                if (matchedId < 0)
                    return Future.succeededFuture(false);
                return executeRawSubmit(CONSUME_SQL, matchedId).map(TotpCredentialStore::returnedARow);
            });
    }

    /** Removes every backup code of the account — used by remove and by a super-admin reset. */
    Future<Void> deleteAllFor(Object accountId) {
        return executeRawSubmit(DELETE_ALL_CODES_SQL, accountId).mapEmpty();
    }

    // ===== second_factor_reset + the by-person session revoke ====================================

    private static final String INSERT_RESET_SQL =
        "INSERT INTO second_factor_reset (frontend_account_id, what, reset_by_person_id, note, sessions_revoked)" +
        " VALUES ($1, $2, $3, $4, $5)";

    // Counted before the revoke, because the count cannot be read out of the revoke itself: the
    // submit layer collects at most ONE generated key per statement (VertxSqlUtil.toWebFxSubmitResult),
    // so a multi-row " returning id" says "at least one" and no more. The number is an audit note on
    // the reset row; the UPDATE below is the part that has to be right.
    private static final String COUNT_LIVE_SESSIONS_SQL =
        "SELECT count(*) FROM auth_session WHERE person_id = $1 AND revoked IS NULL";

    // Raw SQL from here rather than through the authsession plugin, which owns this table otherwise
    // (ModalityAuthSessionStore): that store revokes ONE family by id, and a by-person revoke would
    // mean adding an SPI method, a provider lookup and a cross-plugin dependency for a single
    // statement. If auth_session ever grows a second by-person operation, move both there together.
    private static final String REVOKE_SESSIONS_SQL =
        "UPDATE auth_session SET revoked = now(), revoked_reason = '" + REVOKED_REASON + "'" +
        " WHERE person_id = $1 AND revoked IS NULL returning id";

    /**
     * Ends every live session of one person, and reports how many there were.
     *
     * <p>A reset exists because somebody lost control of a factor, so a session opened by whoever
     * caused it must not outlive the reset. The count can be stale by whatever opened or expired
     * between the two statements; the revoke is unconditional either way.
     */
    Future<Integer> revokeLiveSessions(Object personId) {
        return executeRawQuery(COUNT_LIVE_SESSIONS_SQL, new Object[]{personId})
            .map(result -> result.getRowCount() == 1 ? (int) longValue(result.getValue(0, 0)) : 0)
            .compose(count -> executeRawSubmit(REVOKE_SESSIONS_SQL, personId).map(ignored -> count));
    }

    /** Records what was reset, by whom, on whose word, and how many sessions it ended. */
    Future<Void> insertReset(Object accountId, String what, Object resetByPersonId, String note, int sessionsRevoked) {
        return executeRawSubmit(INSERT_RESET_SQL, accountId, what, resetByPersonId, note, sessionsRevoked).mapEmpty();
    }

    // ===== backup-code hashing ===================================================================

    /**
     * The canonical form a code is hashed and compared in: upper case, with the grouping dash and
     * any spacing removed. The sheet shows {@code xxxxx-xxxxx}; what gets typed back is whatever the
     * person's keyboard produced.
     */
    static String canonicalCode(String typedCode) {
        if (typedCode == null)
            return null;
        StringBuilder canonical = new StringBuilder(typedCode.length());
        // Locale.ROOT: the Turkish locale's dotless-i rules would otherwise change what an
        // upper-cased character IS, on a server whose locale the code has no say over
        for (char c : typedCode.toUpperCase(Locale.ROOT).toCharArray())
            if (BackupCodes.ALPHABET.indexOf(c) >= 0)
                canonical.append(c);
        return canonical.length() == BackupCodes.CODE_LENGTH ? canonical.toString() : null;
    }

    /**
     * SHA-256 over {@code salt ‖ code}, base64url.
     *
     * <p>Salted per row, unlike {@code MateInviteTokenStore.hashToken}: that hashes 32 random bytes,
     * where a rainbow table is meaningless. A backup code is ~50 bits and drawn from a published
     * alphabet, so one unsalted hash column would be precomputable across every row at once.
     */
    private static String hash(byte[] salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(code.getBytes(StandardCharsets.UTF_8));
            return B64URL_ENCODER.encodeToString(digest.digest());
        } catch (Exception e) {
            // SHA-256 is mandatory in every JRE; a null here would store nothing that can match
            return null;
        }
    }

    private static boolean matchesHash(BackupCodeRow row, String canonicalCode) {
        if (row.codeHash() == null || row.salt() == null)
            return false;
        byte[] salt;
        try {
            salt = B64URL_DECODER.decode(row.salt());
        } catch (RuntimeException e) {
            return false;
        }
        String candidate = hash(salt, canonicalCode);
        return candidate != null
               && MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                                        row.codeHash().getBytes(StandardCharsets.UTF_8));
    }

    // ===== raw SQL plumbing (WebAuthnCredentialStore pattern) ====================================

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

    /**
     * An id as the pg driver's raw Tuple binding will take it.
     *
     * <p>Ids travel as {@code Object} and come back from a session token as {@code Byte} or
     * {@code Short} for small values — DQL coerces those, raw SQL refuses them — so every id that
     * reaches a statement here goes through this first. Left as-is when it is not a number at all,
     * so the failure is the driver's rather than a silent null.
     */
    static Object normaliseId(Object id) {
        Long normalised = Numbers.toLong(id);
        return normalised != null ? normalised : id;
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

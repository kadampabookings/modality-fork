package one.modality.crm.server.authn.gateway.webauthn;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
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
 * All SQL access to the {@code webauthn_credential} table (created by migration V0088).
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
 * <p>{@code status} is the credential's back-office trust (added by V0089): APPROVED, or PENDING
 * until a super administrator decides (APPROVED or REJECTED). Which one a new row starts with, and
 * whether PENDING blocks a back-office login, is the gateway's approval switch — only the gateway
 * interprets it; this store just reads and writes it.
 *
 * @author Claude Code
 */
final class WebAuthnCredentialStore {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_REJECTED = "REJECTED";

    /**
     * Whether a credential with this status can complete a BACK-OFFICE assertion <b>today</b> — the
     * one place the back-office APPROVAL rule is written, read by the gateway's approval gate and by
     * {@link PasskeySecondFactorVerifier}'s enrolment answer.
     *
     * <p>({@link #STATUS_REJECTED} is also refused on its own line in the assertion path, before this
     * is consulted, because a rejection must refuse a FRONT-office assertion too and this predicate
     * answers only the back-office question. So the REJECTED half is written twice on purpose — once
     * here, where it keeps the two back-office callers in step, and once there, where it covers the
     * origin this predicate says nothing about.)
     *
     * <p>It exists because those two must never disagree. "Enrolled in a passkey" is what decides
     * whether a password login is held for a second step and what the pending marker advertises; if
     * it said yes where the assertion path says no, the owner would be asked for a factor the server
     * refuses — locked out — and if it said no where the assertion path says yes, the account would
     * be quietly downgraded to password-only. Two copies of the rule is two answers waiting to drift
     * apart, so there is one.
     *
     * <p>The rule, and it depends on the switch the gateway holds:
     * <ul>
     * <li>{@link #STATUS_REJECTED} — never, whatever the switch says. A rejection is a decision on
     *     record, not a queue state, and it signs in nowhere.</li>
     * <li>approval required ({@code WEBAUTHN_BACKOFFICE_APPROVAL} on) — only
     *     {@link #STATUS_APPROVED}: a PENDING row is waiting for a super administrator and opens
     *     nothing until it has one.</li>
     * <li>approval off (phase 1) — any row that is not REJECTED, PENDING included: with the gate
     *     dormant the account's own {@code backoffice} flag is what decides who enters.</li>
     * </ul>
     *
     * <p>An unrecognised status follows the same two lines rather than a rule of its own, so the two
     * callers stay identical for a value neither expects (the V0089 CHECK constraint makes one
     * unreachable from the database anyway).
     */
    static boolean opensBackofficeLogin(String status, boolean approvalRequired) {
        if (STATUS_REJECTED.equals(status))
            return false;
        return !approvalRequired || STATUS_APPROVED.equals(status);
    }

    /**
     * An id normalised to Long for raw-SQL binding, mirroring {@code TotpCredentialStore.normaliseId}:
     * ids deserialized from a session token can come back as Byte/Short for small values, which DQL
     * coerces but the pg driver's raw Tuple binding refuses. Also what makes two ids from two
     * different sources — a raw-SQL row and an entity query — comparable with {@code equals}.
     */
    static Object normaliseId(Object id) {
        Long normalised = Numbers.toLong(id);
        return normalised != null ? normalised : id;
    }

    /** One credential row as needed at assertion time (lookup by credential id). */
    record CredentialRow(long id, Object accountId, String publicKeyCose, long signCount, String userHandle, String status) {
    }

    /** One credential row as shown by its owner's management UI (never the key material). */
    record CredentialSummary(long id, String credentialId, String userHandle, String label, String aaguid,
                             String transports, Object createdAt, Object lastUsedAt, String status) {
    }

    /** One pending credential as shown to an approver: whose it is, what it is, when it arrived. */
    record PendingSummary(long id, String username, String label, String aaguid, String transports, Object createdAt) {
    }

    // SELECT column positions, in SELECT order (raw SQL ⇒ read by position)
    private static final String SELECT_BY_ACCOUNT_SQL =
        "SELECT id, credential_id, user_handle, label, aaguid, transports, created_at, last_used_at, status" +
        " FROM webauthn_credential WHERE frontend_account_id = $1 ORDER BY id";
    private static final int A_ID = 0, A_CREDENTIAL_ID = 1, A_USER_HANDLE = 2, A_LABEL = 3, A_AAGUID = 4,
        A_TRANSPORTS = 5, A_CREATED_AT = 6, A_LAST_USED_AT = 7, A_STATUS = 8;

    private static final String SELECT_BY_CREDENTIAL_ID_SQL =
        "SELECT id, frontend_account_id, public_key_cose, sign_count, user_handle, status" +
        " FROM webauthn_credential WHERE credential_id = $1";
    private static final int C_ID = 0, C_ACCOUNT_ID = 1, C_PUBLIC_KEY = 2, C_SIGN_COUNT = 3, C_USER_HANDLE = 4, C_STATUS = 5;

    // The approval queue: pending credentials of BACK-OFFICE accounts, with the account each
    // belongs to. Members' passkeys start PENDING too (while approval is on) but are not
    // listed — there is nothing to decide until the account is granted back-office access, at
    // which point they appear here.
    // The approver's own account ($2) is excluded: a super administrator must not certify a
    // credential enrolled behind their own password, so another one has to. The username is the
    // account's login email — personal data, shown only to super administrators and never
    // logged. Oldest first, so the queue is worked in arrival order.
    private static final String SELECT_PENDING_SQL =
        "SELECT c.id, a.username, c.label, c.aaguid, c.transports, c.created_at" +
        " FROM webauthn_credential c JOIN frontend_account a ON a.id = c.frontend_account_id" +
        " WHERE c.status = $1 AND a.backoffice = true AND a.disabled IS NOT TRUE AND c.frontend_account_id <> $2" +
        " ORDER BY c.created_at, c.id";
    private static final int P_ID = 0, P_USERNAME = 1, P_LABEL = 2, P_AAGUID = 3, P_TRANSPORTS = 4, P_CREATED_AT = 5;

    // status is bound explicitly (the column DEFAULT is only a safety net): the gateway decides
    // the initial state from its approval switch — PENDING for the queue, APPROVED when the gate
    // is off — so the database default never silently decides it
    private static final String INSERT_SQL =
        "INSERT INTO webauthn_credential" +
        " (frontend_account_id, credential_id, public_key_cose, sign_count, user_handle, transports, aaguid, label, status)" +
        " VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)";

    // GREATEST keeps the stored counter monotonic even under the warn-and-accept regression policy
    private static final String UPDATE_USAGE_SQL =
        "UPDATE webauthn_credential SET sign_count = GREATEST(sign_count, $1), last_used_at = now() WHERE id = $2";

    // Ownership lives IN the statement: a valid id belonging to someone else matches zero rows.
    // The lowercase " returning id" is load-bearing: SubmitResult.getRowCount() counts result SETS
    // (always 1 for a single statement — VertxSqlUtil.toWebFxSubmitResult), so affected-vs-not can
    // only be observed through the generated keys that a " returning " statement populates: one key
    // when the row matched, none when it did not.
    // A REJECTED row ($3) cannot be deleted by its owner: the rejection stays on record instead
    // of being cleared by a delete-and-re-enrol from the same session that earned it.
    private static final String DELETE_OWNED_SQL =
        "DELETE FROM webauthn_credential WHERE id = $1 AND frontend_account_id = $2 AND status <> $3 returning id";
    private static final String RENAME_OWNED_SQL =
        "UPDATE webauthn_credential SET label = $1 WHERE id = $2 AND frontend_account_id = $3 returning id";

    // A decision only lands on a PENDING row: two approvers racing, or an approve after a reject,
    // find nothing to change instead of overwriting each other — the first decision stands. It
    // never lands on the approver's own account ($5): self-approval would make the gate a
    // formality for the very accounts it matters most for. And it only lands on rows the queue
    // would list (a live back-office account): approving a member's passkey by id would hand
    // them a pre-approved credential the day they are granted back-office access.
    private static final String DECIDE_PENDING_SQL =
        "UPDATE webauthn_credential SET status = $1, decided_by_person_id = $2, decided_at = now()" +
        " WHERE id = $3 AND status = $4 AND frontend_account_id <> $5" +
        " AND EXISTS (SELECT 1 FROM frontend_account a WHERE a.id = webauthn_credential.frontend_account_id" +
        "             AND a.backoffice = true AND a.disabled IS NOT TRUE)" +
        " returning id";

    // The decision REVISITED — the one thing DECIDE_PENDING_SQL cannot do. It only ever lands on a
    // PENDING row, so an APPROVED passkey (approved at the queue, or approved from birth because the
    // gate was off) could never be taken back: a lost or shared authenticator stayed trusted for as
    // long as the row existed. This moves any row that is not ALREADY REJECTED to REJECTED ($1) and
    // records who decided.
    //
    // WIDENED from "status = APPROVED" to "status <> REJECTED", and the reason is
    // opensBackofficeLogin above: with WEBAUTHN_BACKOFFICE_APPROVAL off — the shipped default — a
    // PENDING row opens a back-office login exactly as an APPROVED one does. An APPROVED-only
    // withdrawal therefore could not clear every factor from an account that holds one, which is
    // what a super administrator rescuing a locked-out member of staff has to be able to do. It is
    // also the only way to decide a PENDING row of an account that is no longer a live back-office
    // one: DECIDE_PENDING_SQL's EXISTS clause refuses those, so until now such a row could be
    // decided by nothing at all and sprang back to life the day the account was re-promoted.
    // What the widening does NOT do is matter: the target status is REJECTED and only REJECTED, so
    // this can never approve anything, never resurrect a rejection (a REJECTED row matches no row
    // here and the caller reads that as a refusal), and never pre-empt an APPROVAL — the queue keeps
    // that decision to itself.
    // The approver's own account ($4) is excluded for the same reason as above: a super
    // administrator must not rule on a credential enrolled behind their own password.
    // NO live-back-office-account EXISTS clause, unlike DECIDE_PENDING_SQL, and the asymmetry is the
    // point: that clause is there to stop an APPROVAL being banked against a back-office grant that
    // has not happened yet, while withdrawing trust from an account that has since been disabled or
    // demoted is precisely when a revocation is wanted.
    private static final String REVOKE_SQL =
        "UPDATE webauthn_credential SET status = $1, decided_by_person_id = $2, decided_at = now()" +
        " WHERE id = $3 AND status <> $1 AND frontend_account_id <> $4" +
        " returning id";

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
                        result.getValue(row, A_LAST_USED_AT),
                        stringValue(result.getValue(row, A_STATUS))));
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
                    stringValue(result.getValue(0, C_USER_HANDLE)),
                    stringValue(result.getValue(0, C_STATUS)));
            });
    }

    /**
     * The approval queue: every PENDING credential of a back-office account other than the
     * approver's own, oldest first.
     */
    Future<List<PendingSummary>> findPending(Object approverAccountId) {
        return executeRawQuery(SELECT_PENDING_SQL, new Object[]{STATUS_PENDING, approverAccountId})
            .map(result -> {
                List<PendingSummary> pending = new ArrayList<>(result.getRowCount());
                for (int row = 0; row < result.getRowCount(); row++) {
                    pending.add(new PendingSummary(
                        longValue(result.getValue(row, P_ID)),
                        stringValue(result.getValue(row, P_USERNAME)),
                        stringValue(result.getValue(row, P_LABEL)),
                        stringValue(result.getValue(row, P_AAGUID)),
                        stringValue(result.getValue(row, P_TRANSPORTS)),
                        result.getValue(row, P_CREATED_AT)));
                }
                return pending;
            });
    }

    /** Stores a new credential with the initial {@code status} the gateway decided (one of the STATUS_ constants). */
    Future<?> insert(Object accountId, String credentialIdB64, String publicKeyCoseB64, long signCount,
                     String userHandleB64, String transports, String aaguid, String label, String status) {
        return executeRawSubmit(INSERT_SQL,
            accountId, credentialIdB64, publicKeyCoseB64, signCount, userHandleB64, transports, aaguid, label, status);
    }

    /** Fire-and-forget usage stamp after a successful assertion; a failure must not fail the login. */
    Future<?> updateUsage(long id, long newSignCount) {
        return executeRawSubmit(UPDATE_USAGE_SQL, newSignCount, id);
    }

    /**
     * Deletes the row only when it belongs to the account and is not REJECTED; resolves to
     * whether a row was deleted.
     */
    Future<Boolean> deleteOwned(long id, Object accountId) {
        return executeRawSubmit(DELETE_OWNED_SQL, id, accountId, STATUS_REJECTED).map(WebAuthnCredentialStore::returnedARow);
    }

    /** Renames the row only when it belongs to the account; resolves to whether a row was renamed. */
    Future<Boolean> renameOwned(long id, Object accountId, String label) {
        return executeRawSubmit(RENAME_OWNED_SQL, label, id, accountId).map(WebAuthnCredentialStore::returnedARow);
    }

    /**
     * Records an approver's decision on a PENDING row; resolves to whether the row was still
     * pending (false when it was already decided, does not exist, belongs to the approver, or
     * belongs to an account that is not a live back-office one).
     */
    Future<Boolean> decidePending(long id, String newStatus, Object deciderPersonId, Object deciderAccountId) {
        return executeRawSubmit(DECIDE_PENDING_SQL, newStatus, deciderPersonId, id, STATUS_PENDING, deciderAccountId)
            .map(WebAuthnCredentialStore::returnedARow);
    }

    /**
     * Withdraws a credential — anything not already REJECTED becomes REJECTED, with the decision
     * recorded; resolves to whether a row actually changed (false when it was already rejected, when
     * it no longer exists, or when it belongs to the approver's own account).
     *
     * <p>PENDING counts as something to withdraw, not only APPROVED: see {@link #REVOKE_SQL} and
     * {@link #opensBackofficeLogin} — while the approval switch is off a PENDING row signs its owner
     * in, so leaving it alone would leave a usable factor behind on an account a super administrator
     * was clearing.
     *
     * <p>The row is kept rather than deleted, as a rejection at the queue is: its owner sees a
     * decision instead of a passkey that silently vanished, and cannot clear it by removing the row
     * themselves (the owner's delete refuses a REJECTED one).
     */
    Future<Boolean> revoke(long id, Object deciderPersonId, Object deciderAccountId) {
        return executeRawSubmit(REVOKE_SQL, STATUS_REJECTED, deciderPersonId, id, deciderAccountId)
            .map(WebAuthnCredentialStore::returnedARow);
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

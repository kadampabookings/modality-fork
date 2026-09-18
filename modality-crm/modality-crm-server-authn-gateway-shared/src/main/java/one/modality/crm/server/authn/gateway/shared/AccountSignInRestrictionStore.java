package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

/**
 * Which ways into an account its owner has deliberately closed — {@code account_sign_in_restriction}, V0096.
 *
 * <h3>What this is for</h3>
 *
 * <p>The everyday incident is not a stolen laptop but a password that may be known: typed into a
 * convincing fake, or reused somewhere since breached. Disabling the whole account answers it and costs
 * its owner their access until a super administrator gives it back. Closing the password alone answers it
 * and costs nothing, because the passkey still works — which is the entire reason this exists as a
 * separate, calmer control.
 *
 * <h3>Password is to mean password AND recovery — one half of that is built</h3>
 *
 * <p>Both routes are to be closed by one restriction, and that is the point rather than an extra: a
 * password that cannot be used to sign in but can be replaced from an email has not stopped working, it
 * has moved house. <b>Today only the sign-in half has a reader</b>, the password gateway; closing recovery
 * is the next increment, and until the writer lands with it this table has no writer at all.
 *
 * <p>The consequence, for whoever writes that: with recovery closed the ways back are a passkey or a
 * super administrator, so nothing may create one of these for an account that has no usable passkey.
 * This class is deliberately not the place that checks it — it records and reports restrictions, and the
 * caller owns the precondition because the caller is what knows about passkeys. That disclaimer is
 * load-bearing rather than tidy: a forgotten precondition locks a real person out until an administrator
 * intervenes.
 *
 * <h3>Raw SQL, like its neighbours</h3>
 *
 * <p>{@code account_sign_in_restriction} is KBS3-only and deliberately outside the domain model, as
 * {@code webauthn_credential} and {@code totp_credential} are: generic client DQL cannot reach it, so
 * ownership rests entirely on the statements here, every one of which names the account it is allowed to
 * touch. Do not add a statement that does not.
 *
 * @author Claude Code
 */
public final class AccountSignInRestrictionStore {

    /** The only method a restriction can name today — see the migration's note on why it is a column. */
    public static final String PASSWORD_METHOD = "password";

    /**
     * Whether this account's password is closed — the read on the sign-in path.
     *
     * <p>One index-only probe beside a query that already runs there. Asked on the password path today and
     * on the recovery path when that lands, and on neither is a cached answer good enough: the whole value
     * of the control is that it takes effect on the next attempt.
     */
    private static final String IS_RESTRICTED_SQL =
        "select 1 from account_sign_in_restriction" +
        " where frontend_account_id = $1 and method = $2 and lifted_at is null limit 1";

    /**
     * Closes it. Idempotent by the partial unique index rather than by checking first: two clicks a second
     * apart would otherwise both see nothing and both insert, leaving a second row that a later lift would
     * miss — and an account that looks restricted after being un-restricted is the failure mode that costs
     * somebody their morning.
     */
    private static final String RESTRICT_SQL =
        "insert into account_sign_in_restriction (frontend_account_id, method, created_by_person_id, note)" +
        " values ($1, $2, $3, $4) on conflict do nothing returning id";

    /** Lifts it, keeping the row: a security control that leaves no trace of having been used is worth less. */
    private static final String LIFT_SQL =
        "update account_sign_in_restriction set lifted_at = now(), lifted_by_person_id = $3" +
        " where frontend_account_id = $1 and method = $2 and lifted_at is null returning id";

    private AccountSignInRestrictionStore() {}

    /**
     * Whether password sign-in and password recovery are closed for this account.
     *
     * <p><b>Fails OPEN on a database error, deliberately, and only because of where it is called.</b> This
     * sits on the sign-in path: a store that could not answer would otherwise turn any blip on this table
     * into everybody being refused — the same mistake as treating "the database did not answer" as "this
     * session is over". The restriction is a convenience its owner chose, not the system's last line: an
     * account whose password is closed still has a password that is hashed, salted and unknown to the
     * attacker unless they already had it. Do NOT copy this posture to a check that is somebody's last line.
     */
    public static Future<Boolean> isPasswordClosed(Object frontendAccountId) {
        if (frontendAccountId == null)
            return Future.succeededFuture(false);
        return executeRawQuery(IS_RESTRICTED_SQL, normaliseId(frontendAccountId), PASSWORD_METHOD)
            .map(result -> result != null && result.getRowCount() > 0)
            .otherwise(e -> {
                Console.log("⚠️ Could not read sign-in restrictions — letting the attempt proceed: " + e);
                return false;
            });
    }

    /**
     * Closes password sign-in and recovery for this account.
     *
     * @param createdByPersonId who asked — the owner, or a super administrator acting for them
     * @param note              the owner's own words, optional, never logged
     */
    public static Future<Boolean> closePassword(Object frontendAccountId, Object createdByPersonId, String note) {
        return executeRawSubmit(RESTRICT_SQL, normaliseId(frontendAccountId), PASSWORD_METHOD,
                                normaliseId(createdByPersonId), note)
            .map(AccountSignInRestrictionStore::returnedARow);
    }

    /**
     * Re-opens password sign-in (and recovery, once that half lands) for this account.
     *
     * @return whether a restriction was actually lifted — false when there was none in force
     */
    public static Future<Boolean> openPassword(Object frontendAccountId, Object liftedByPersonId) {
        return executeRawSubmit(LIFT_SQL, normaliseId(frontendAccountId), PASSWORD_METHOD,
                                normaliseId(liftedByPersonId))
            .map(AccountSignInRestrictionStore::returnedARow);
    }

    /**
     * Whether the statement changed anything, read from the keys a {@code returning id} populates.
     *
     * <p>Not from {@code getRowCount()}, which counts result SETS and is 1 for a single statement whether
     * it matched a row or none — so a lift against an account with no restriction would otherwise report
     * success, and the UI above it would tell somebody their password works again when nothing changed.
     */
    private static boolean returnedARow(SubmitResult result) {
        return result != null && result.getGeneratedKeys() != null && result.getGeneratedKeys().length > 0;
    }

    /**
     * Widens a small id to a Long before it reaches the driver.
     *
     * <p>Same guard as {@code TotpCredentialStore.normaliseId} and for the same reason: an id decoded from
     * a session token comes back as Byte or Short for small values, which DQL coerces but the pg driver's
     * raw Tuple binding refuses. Not reachable from today's only caller, whose id comes from a server-side
     * EntityStore query — but every caller this store is waiting for is a bus endpoint reading the
     * principal, which is exactly the case that breaks.
     */
    private static Object normaliseId(Object id) {
        Long normalised = Numbers.toLong(id);
        return normalised != null ? normalised : id;
    }

    private static Future<QueryResult> executeRawQuery(String sql, Object... parameters) {
        return QueryService.executeQuery(QueryArgument.builder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql) // no setLanguage ⇒ raw SQL, native $n placeholders
            .setParameters(parameters)
            .build());
    }

    private static Future<SubmitResult> executeRawSubmit(String sql, Object... parameters) {
        return SubmitService.executeSubmit(SubmitArgument.builder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters)
            .build());
    }
}

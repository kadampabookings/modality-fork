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
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;

import java.security.SecureRandom;

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
 * <h3>Password means password AND recovery</h3>
 *
 * <p>Both routes are closed by one restriction, and that is the point rather than an extra: a password
 * that cannot be used to sign in but can be replaced from an email has not stopped working, it has moved
 * house. And "recovery" is every emailed way in, not only the reset: sign-in links and codes and booking
 * access are refused where a link is validated (MagicLinkService), and KBS2's own reset is shut in the
 * database (V0098). The password gateway reads it on sign-in ({@link #isPasswordClosed}, fail-open) and
 * before ANY password is set ({@link #readPasswordClosed}, fail-closed). The writer is the owner's Security
 * page control, through the passkey gateway, and a super administrator's rescue.
 *
 * <p>The consequence, for every writer: with recovery closed the ways back are a passkey or a super
 * administrator, so nothing may create one of these for an account that has no usable passkey.
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
     * Whether this account's password is closed.
     *
     * <p>One index-only probe beside a query that already runs there. Asked on password sign-in and before
     * every password set, and on neither is a cached answer good enough: the whole value of the control is
     * that it takes effect on the next attempt.
     */
    private static final String IS_RESTRICTED_SQL =
        "select 1 from account_sign_in_restriction" +
        " where frontend_account_id = $1 and method = $2 and lifted_at is null limit 1";

    /** How a wiped password starts: readable in the row, and a dash no MD5 hex digest ever holds. */
    static final String WIPED_PASSWORD_PREFIX = "CLOSED-";
    private static final String WIPED_PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * What a closed account's stored password is replaced with. {@code frontend_account.password} is
     * {@code char(32) not null}, so the password cannot simply be removed; this takes its place: exactly 32
     * characters, starting with {@link #WIPED_PASSWORD_PREFIX}.
     *
     * <p>Two properties, both needed. It matches no password: a stored password is an MD5 hex digest
     * ({@link StoredPasswords}), and no digest holds a dash, whatever the case. And it is RANDOM, drawn afresh on
     * every close, because not every reader hashes before comparing: a KBS2 login that compares a client-supplied
     * value with the stored column as it stands would accept a fixed, public wiped value typed in as-is. About 148
     * bits of it are unknown to anyone.
     */
    static String newWipedPassword() {
        StringBuilder wiped = new StringBuilder(WIPED_PASSWORD_PREFIX);
        while (wiped.length() < 32)
            wiped.append(WIPED_PASSWORD_ALPHABET.charAt(RANDOM.nextInt(WIPED_PASSWORD_ALPHABET.length())));
        return wiped.toString();
    }

    /**
     * The same question asked by the address an emailed link or code goes to. The account is resolved exactly as
     * redeeming the link resolves it (MagicLinkService.loadUserPersonFromMagicLink: live persons first, the owner
     * first, then the lowest id), so that with two accounts whose usernames differ only in case, the one asked
     * about is the one the link would open.
     */
    private static final String IS_RESTRICTED_BY_USERNAME_SQL =
        "select 1 from account_sign_in_restriction r" +
        " where r.method = $2 and r.lifted_at is null and r.frontend_account_id = (" +
        "   select p.frontend_account_id from person p join frontend_account fa on fa.id = p.frontend_account_id" +
        "   where fa.corporation_id = 1 and lower(fa.username) = lower($1) and not fa.disabled" +
        "   order by p.removed, p.owner desc, p.id limit 1)" +
        " limit 1";

    /**
     * Closes it, and wipes the stored password in the same statement. Idempotent by the partial unique index
     * rather than by checking first: two clicks a second apart would otherwise both see nothing and both insert,
     * leaving a second row that a later lift would miss — and an account that looks restricted after being
     * un-restricted is the failure mode that costs somebody their morning.
     *
     * <p>The wipe is what makes the control worth more than a flag. Stored passwords are MD5 with a salt derived
     * from the email, so a copied {@code frontend_account} table is close to a list of passwords, and people reuse
     * them. A closed account's is no longer in it. Any KBS2 reset token already mailed goes with it (KBS2's reset
     * is otherwise shut by V0098), and so do the account's pending sign-in and email-change links. It runs whether or
     * not this call inserted the row, so a second click still leaves the password wiped.
     */
    private static final String RESTRICT_SQL =
        "with restricted as (" +
        "  insert into account_sign_in_restriction (frontend_account_id, method, created_by_person_id, note)" +
        "  values ($1, $2, $3, $4) on conflict do nothing returning id" +
        "), wiped as (" +
        "  update frontend_account set password = $5, pwdreset_token = null, pwdreset_expires = null" +
        "  where id = $1 returning username" +
        "), voided as (" +
        // Pending sign-in and email-change links for the account die with the close: redeeming them is refused anyway,
        // but an email change started before it (by whoever knew the password) must not be finishable after it
        "  update magic_link set usage_date = now()" +
        // (LOGIN rows are mostly stored with no type; support rows, whose old_email is the AGENT, are left alone)
        "  where (link_type is null or link_type = 'LOGIN') and usage_date is null and exists (select 1 from wiped w" +
        "    where lower(magic_link.email) = lower(w.username) or lower(magic_link.old_email) = lower(w.username))" +
        "  returning id" +
        ") select id from restricted";

    /** Lifts it, keeping the row: a security control that leaves no trace of having been used is worth less. */
    private static final String LIFT_SQL =
        "update account_sign_in_restriction set lifted_at = now(), lifted_by_person_id = $3" +
        " where frontend_account_id = $1 and method = $2 and lifted_at is null returning id";

    /**
     * A super administrator's rescue lift, recorded where every other rescue is: a {@code second_factor_reset} row
     * ({@code what = 'PASSWORD'}, V0099) carrying the approver and the note of the out-of-band check they made. In one
     * statement, and the audit row only when something was lifted.
     */
    private static final String RESCUE_LIFT_SQL =
        "with lifted as (" +
        "  update account_sign_in_restriction set lifted_at = now(), lifted_by_person_id = $3" +
        "  where frontend_account_id = $1 and method = $2 and lifted_at is null returning id" +
        "), audited as (" +
        "  insert into second_factor_reset (frontend_account_id, what, reset_by_person_id, note)" +
        "  select $1, 'PASSWORD', $3, $4 where exists (select 1 from lifted) returning id" +
        ") select id from lifted";

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
        // No account, no restriction to read: a "no" here, where readPasswordClosed() would refuse.
        if (frontendAccountId == null)
            return Future.succeededFuture(false);
        return failOpen(readPasswordClosed(frontendAccountId));
    }

    /** A read that could not answer counts as "no" — only for the callers documented as fail-open. */
    private static Future<Boolean> failOpen(Future<Boolean> read) {
        return read.otherwise(e -> {
            Console.log("⚠️ Could not read sign-in restrictions — letting the attempt proceed: " + e);
            return false;
        });
    }

    /**
     * The refusal for an emailed link or code — sign-in, recovery or booking access — reaching an account whose
     * password is closed. One definition, because the client recognises it by its key.
     */
    public static <T> Future<T> emailSignInClosedFailure() {
        return Future.failedFuture("[%s] Sign-in links and codes are turned off for this account"
            .formatted(ModalityAuthenticationI18nKeys.AuthnEmailSignInClosedError));
    }

    /**
     * The same question, but a failure to answer is a FAILURE, not a "no".
     *
     * <p>For every caller where refusing on a blip is cheap and proceeding is not — which is every caller
     * except the sign-in read above. Setting a password is the case that motivated it: if this table cannot
     * be read, a restricted account must not be allowed to put a password back on the strength of a query
     * that did not run. The person retries a minute later; the alternative is a control that quietly stops
     * holding exactly when the database is struggling.
     */
    public static Future<Boolean> readPasswordClosed(Object frontendAccountId) {
        // Fail-closed extends to the question itself: an account id that did not arrive is a caller that
        // could not say whose password this is, which is not the same as an account with no restriction.
        if (frontendAccountId == null)
            return Future.failedFuture("readPasswordClosed() needs an account id");
        return executeRawQuery(IS_RESTRICTED_SQL, normaliseId(frontendAccountId), PASSWORD_METHOD)
            .map(result -> result != null && result.getRowCount() > 0);
    }

    /**
     * Whether the account that signs in with this address has its password closed — for the paths that know an
     * address rather than an account (a link or code to email, a cart link to redeem). No such account: false.
     * Fails OPEN like {@link #isPasswordClosed}, so only for a path whose outcome is checked again fail-closed.
     */
    public static Future<Boolean> isPasswordClosedForEmail(String email) {
        return failOpen(readPasswordClosedForEmail(email));
    }

    /** The same by address, fail-CLOSED: a failure to answer is a failure. No such account (or no address): false. */
    public static Future<Boolean> readPasswordClosedForEmail(String email) {
        if (email == null || email.isBlank())
            return Future.succeededFuture(false);
        return executeRawQuery(IS_RESTRICTED_BY_USERNAME_SQL, email.trim(), PASSWORD_METHOD)
            .map(result -> result != null && result.getRowCount() > 0);
    }

    /**
     * Closes password sign-in and recovery for this account.
     *
     * @param createdByPersonId who asked — the owner, or a super administrator acting for them
     * @param note              the owner's own words, optional, never logged
     */
    public static Future<Boolean> closePassword(Object frontendAccountId, Object createdByPersonId, String note) {
        return executeRawSubmit(RESTRICT_SQL, normaliseId(frontendAccountId), PASSWORD_METHOD,
                                normaliseId(createdByPersonId), note, newWipedPassword())
            .map(AccountSignInRestrictionStore::returnedARow);
    }

    /**
     * Re-opens password sign-in and recovery for this account. Nothing comes back: the password was wiped when it
     * was closed, so the owner sets a new one through recovery.
     *
     * @return whether a restriction was actually lifted — false when there was none in force
     */
    public static Future<Boolean> openPassword(Object frontendAccountId, Object liftedByPersonId) {
        return executeRawSubmit(LIFT_SQL, normaliseId(frontendAccountId), PASSWORD_METHOD,
                                normaliseId(liftedByPersonId))
            .map(AccountSignInRestrictionStore::returnedARow);
    }

    /**
     * A super administrator's rescue of an owner who lost every passkey: reopens the account's password and records
     * who did it, and the check they made, beside the other rescues.
     *
     * @return whether a restriction was actually lifted
     */
    public static Future<Boolean> openPasswordByAdministrator(Object frontendAccountId, Object administratorPersonId, String note) {
        return executeRawSubmit(RESCUE_LIFT_SQL, normaliseId(frontendAccountId), PASSWORD_METHOD,
                                normaliseId(administratorPersonId), note)
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

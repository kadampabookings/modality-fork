package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import dev.webfx.stack.session.state.StateAccessor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * What the account may do in the back office under "setup first" — the two facts every gateway needs and must read
 * the same way: how many APPROVED second factors it holds, and whether a super administrator has reopened setup for it.
 *
 * <h3>Why a session without an approved factor is a FRONT-OFFICE session</h3>
 *
 * <p>Under {@code required} ({@link SecondFactorPolicy#isSetupFirst}), a password sign-in of an account with no
 * approved factor — and an emailed link or code of an account that must present one — still mints a session, because
 * setting up a device and choosing a new password both need to know whose account it is. But it mints it with the
 * FRONT-OFFICE audience ({@code $aud=fo}). Every back-office guard demands {@code bo} (M1: RouteAccessGuard, the
 * super-administrator operations), so the session reaches nothing in the back office, and what it CAN reach is exactly
 * what the same person's member account reaches on the front office already. A guard that tested "has an approved
 * factor" at each back-office entry point would not do: client reads and writes are not all behind RouteAccessGuard,
 * and an audience is a fact the token carries, settled when the credential was checked.
 *
 * <p>The setup and recovery calls are not back-office guarded — they act on the caller's own account and are fenced
 * by their own proofs — so they keep working on such a session; that is the whole of what it is for.
 *
 * @author Claude Code
 */
public final class SecondFactorAccess {

    /** How long a super administrator's reopened setup lasts. */
    public static final int ENROLMENT_WINDOW_HOURS = 48;

    // Approved = what opens the back office under the current approval switch: APPROVED only while it is on, anything
    // but REJECTED while it is off — the same rule as factorOpensBackoffice, bound as approvedStatuses()
    private static final String COUNT_APPROVED_SQL = "SELECT " + approvedFactorCountSql("$1", "$2, $3");

    private static final String OPEN_WINDOW_SQL =
        "SELECT max(window_until) FROM second_factor_reset" +
        " WHERE frontend_account_id = $1 AND what = 'ENROLMENT_WINDOW' AND window_until > now()";

    // How many live super administrators hold an approved factor — the bootstrap test of approverStanding
    private static final String SUPER_ADMINS_WITH_FACTOR_SQL =
        "SELECT count(DISTINCT a.id) FROM authorization_super_admin s" +
        "  JOIN person p ON p.id = s.super_admin_id JOIN frontend_account a ON a.id = p.frontend_account_id" +
        " WHERE p.removed IS NOT TRUE AND a.disabled IS NOT TRUE AND " + approvedFactorCountSql("a.id", "$1, $2") + " > 0";

    /**
     * What a super administrator's session is worth for the second-factor operations — the ONE rule every such
     * operation (in the passkey and the TOTP gateways alike) applies, so an older endpoint cannot become the way round
     * a newer one:
     * <ul>
     *   <li>{@link #USED_FACTOR} — the session presented {@code pk} or {@code totp} ({@code auth_session.methods}): may
     *       do anything a super administrator may;</li>
     *   <li>{@link #BOOTSTRAP} — a password session while NO super administrator holds an approved factor yet: nobody
     *       could approve the first one otherwise, so it may read, and approve a SUPER ADMINISTRATOR's factor — never a
     *       staff member's, never reopen setup, never reset or revoke;</li>
     *   <li>{@link #REFUSED} — a password session once some super administrator holds a factor.</li>
     * </ul>
     */
    public enum ApproverStanding {USED_FACTOR, BOOTSTRAP, REFUSED}

    private SecondFactorAccess() {}

    /** The standing of the session {@code sessionFamilyId} — see {@link ApproverStanding}. Fails closed to REFUSED. */
    public static Future<ApproverStanding> approverStanding(String sessionFamilyId) {
        return FactorRequest.sessionMethods(sessionFamilyId).compose(methods -> {
            if (FactorRequest.includesSecondFactor(methods))
                return Future.succeededFuture(ApproverStanding.USED_FACTOR);
            Object[] statuses = approvedStatuses();
            return RawSql.query(SUPER_ADMINS_WITH_FACTOR_SQL, statuses[0], statuses[1])
                .map(result -> result.getRowCount() == 1 && intValue(result.getValue(0, 0)) == 0
                    ? ApproverStanding.BOOTSTRAP : ApproverStanding.REFUSED);
        }).otherwise(e -> ApproverStanding.REFUSED);
    }

    /** Whether {@code accountId} belongs to a live super administrator — what a BOOTSTRAP approval may decide. */
    public static Future<Boolean> isSuperAdminAccount(Object accountId) {
        return RawSql.query(SUPER_ADMIN_ACCOUNT_SQL, normaliseId(accountId))
            .map(result -> result.getRowCount() > 0)
            .otherwise(e -> false);
    }

    private static final String SUPER_ADMIN_ACCOUNT_SQL =
        "SELECT 1 FROM authorization_super_admin s JOIN person p ON p.id = s.super_admin_id" +
        " WHERE p.frontend_account_id = $1 AND p.removed IS NOT TRUE LIMIT 1";

    /**
     * The SQL expression (parenthesised) counting the approved factors of {@code account} — a placeholder or a column —
     * passkeys and authenticator app together: the one place every "approved factor" query takes its rule from.
     * {@code statuses} names the two placeholders bound to {@link #approvedStatuses()}. A TOTP counts only once
     * confirmed: an unconfirmed row is a setup in progress, not a factor.
     */
    public static String approvedFactorCountSql(String account, String statuses) {
        return "((SELECT count(*) FROM webauthn_credential c WHERE c.frontend_account_id = " + account +
               " AND c.status IN (" + statuses + "))" +
               " + (SELECT count(*) FROM totp_credential t WHERE t.frontend_account_id = " + account +
               " AND t.confirmed_at IS NOT NULL AND t.status IN (" + statuses + ")))";
    }

    /** The two statuses that count as approved under the current approval switch, for {@link #approvedFactorCountSql}. */
    public static Object[] approvedStatuses() {
        boolean approvalOn = SecondFactorPolicy.get().isFactorApprovalRequired();
        return new Object[]{"APPROVED", approvalOn ? "APPROVED" : "PENDING"};
    }

    /** The account's approved factors, passkeys and authenticator app together. A database error fails the future. */
    public static Future<Integer> approvedFactorCount(Object accountId) {
        Object[] statuses = approvedStatuses();
        return RawSql.query(COUNT_APPROVED_SQL, normaliseId(accountId), statuses[0], statuses[1])
            .map(result -> result.getRowCount() == 1 ? intValue(result.getValue(0, 0)) : 0);
    }

    /** When the open enrolment window closes, or null when there is none. Fails closed: an error reads as none. */
    public static Future<Instant> openEnrolmentWindowUntil(Object accountId) {
        return RawSql.query(OPEN_WINDOW_SQL, normaliseId(accountId))
            .map(result -> result.getRowCount() == 1 ? instantOf(result.getValue(0, 0)) : null)
            .otherwise(e -> null);
    }

    /**
     * Whether a password sign-in of an account with NO approved factor may still get its setup-only session: always
     * before the deadline, after it only inside a window a super administrator opened.
     */
    public static Future<Boolean> maySetUpWithoutFactor(Object accountId) {
        if (!SecondFactorPolicy.get().isRequiredNow())
            return Future.succeededFuture(true);
        return openEnrolmentWindowUntil(accountId).map(until -> until != null);
    }

    /**
     * Whether the caller may add a device NOW. On a back-office session, always (it has presented its factor, or the
     * policy does not ask for one). On a narrowed (front-office-audience) session under setup-first, an account with no
     * approved factor may only while it could still get its setup-only session — before the deadline, or inside a
     * window a super administrator opened. That is checked here, at every enrolment, and not only at sign-in: a
     * setup-only session minted inside a window outlives it, and an emailed sign-in opens a recovery window that
     * CredentialChangeProof accepts as proof. Outside it the device would still need an approver, but it would mail
     * them for an account the deadline has closed. MUST be called on the caller's thread.
     */
    public static Future<Boolean> mayEnrolNow(Object accountId) {
        boolean openedForBackoffice = Boolean.TRUE.equals(
            StateAccessor.getVerifiedBackoffice(ThreadLocalStateHolder.getThreadLocalState()));
        if (openedForBackoffice || !SecondFactorPolicy.get().isSetupFirst())
            return Future.succeededFuture(true);
        return approvedFactorCount(accountId)
            .compose(count -> count != null && count > 0 ? Future.succeededFuture(true) : maySetUpWithoutFactor(accountId))
            .otherwise(e -> false);
    }

    static Instant instantOf(Object value) {
        if (value instanceof Instant instant)
            return instant;
        if (value instanceof OffsetDateTime offset)
            return offset.toInstant();
        if (value instanceof ZonedDateTime zoned)
            return zoned.toInstant();
        if (value instanceof LocalDateTime local) // a timestamptz read back without its zone is UTC
            return local.toInstant(ZoneOffset.UTC);
        return null;
    }

    private static Object normaliseId(Object id) {
        Long normalised = Numbers.toLong(id);
        return normalised != null ? normalised : id;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

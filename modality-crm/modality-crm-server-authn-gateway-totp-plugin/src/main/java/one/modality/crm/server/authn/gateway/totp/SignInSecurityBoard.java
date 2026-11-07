package one.modality.crm.server.authn.gateway.totp;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.json.Json;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.session.token.RevokedFamilies;
import dev.webfx.stack.session.token.SessionFamilyStore;
import one.modality.crm.server.authn.gateway.shared.AccountSignInRestrictionStore;
import one.modality.crm.server.authn.gateway.shared.SecondFactorAccess;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.List;
import java.util.Objects;

import static one.modality.crm.server.authn.gateway.totp.TotpCredentialStore.executeRawQuery;
import static one.modality.crm.server.authn.gateway.totp.TotpCredentialStore.executeRawSubmit;

/**
 * The super administrators' Sign-in security page, server side: who is protected, who is signed in, the recent
 * decisions, and the two controls the rescue screen lacked — signing someone out, and blocking an account.
 *
 * <p>Every call is dispatched by {@link ModalityTotpAuthenticationGateway} behind {@code requireSuperAdmin}, re-checked
 * on every call; the writes additionally need a session that presented a second factor ({@code USED_FACTOR}), a note,
 * and refuse the caller's own account — a session taken from a super administrator must not be able to free itself or
 * lock out the colleague who would notice. Each write leaves an audit row ({@code second_factor_reset} or
 * {@code account_sign_in_restriction}), which the activity list reads back.
 *
 * <p><b>Personal data.</b> Names, usernames and sign-in times, to super administrators only. A session carries no IP
 * address or device (none is stored); its id never leaves the server — the page gets a one-way reference to it.
 *
 * @author Claude Code
 */
final class SignInSecurityBoard {

    private static final String LOG_PREFIX = "[2fa] ";
    /** The most rows any list returns: a page of people, not an export. */
    private static final int MAX_ROWS = 500;
    private static final int EVENT_DAYS = 90;
    private static final int MAX_EVENTS = 200;
    private static final int STALE_DEVICE_DAYS = 90;

    // A timestamp column as an ISO instant (see SignInApprovals.utcInstant): with and without time zone
    private static String utc(String timestamptz) {
        return "to_char((" + timestamptz + ") AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')";
    }

    private static String asTimestamptz(String timestamp) {
        return "(" + timestamp + " AT TIME ZONE current_setting('TimeZone'))";
    }

    // The account's display person: its owner first, as the password gateway picks it
    private static final String PERSON_OF_ACCOUNT =
        "LEFT JOIN LATERAL (SELECT first_name, last_name FROM person WHERE frontend_account_id = a.id" +
        "                   AND removed IS NOT TRUE ORDER BY owner DESC NULLS LAST, id LIMIT 1) p ON true";

    // ---- the roster ----------------------------------------------------------------------------------------------

    // Every back-office account, blocked ones included ($1/$2 = SecondFactorAccess.approvedStatuses()). An APPROVED app
    // is a confirmed TOTP row; its backup codes count only beside one
    private static final String ROSTER_SQL =
        "SELECT a.id, a.username, concat_ws(' ', p.first_name, p.last_name)," +
        "  EXISTS (SELECT 1 FROM authorization_super_admin s JOIN person sp ON sp.id = s.super_admin_id" +
        "          WHERE sp.frontend_account_id = a.id AND sp.removed IS NOT TRUE)," +
        "  (SELECT count(*) FROM webauthn_credential c WHERE c.frontend_account_id = a.id AND c.status IN ($1, $2))," +
        "  EXISTS (SELECT 1 FROM totp_credential t WHERE t.frontend_account_id = a.id AND t.confirmed_at IS NOT NULL" +
        "          AND t.status IN ($1, $2))," +
        "  (SELECT count(*) FROM webauthn_credential c WHERE c.frontend_account_id = a.id AND c.status = 'PENDING')" +
        "  + (SELECT count(*) FROM totp_credential t WHERE t.frontend_account_id = a.id AND t.confirmed_at IS NOT NULL" +
        "     AND t.status = 'PENDING')," +
        "  (SELECT count(*) FROM totp_backup_code b WHERE b.frontend_account_id = a.id AND b.used_at IS NULL)," +
        "  " + utc(asTimestamptz("GREATEST(" +
        "    (SELECT max(c.last_used_at) FROM webauthn_credential c WHERE c.frontend_account_id = a.id)," +
        "    (SELECT max(t.last_used_at) FROM totp_credential t WHERE t.frontend_account_id = a.id))")) + "," +
        // Approved devices nobody has used for STALE_DEVICE_DAYS (never used counts from when it was added)
        "  (SELECT count(*) FROM webauthn_credential c WHERE c.frontend_account_id = a.id AND c.status IN ($1, $2)" +
        "     AND coalesce(c.last_used_at, c.created_at) < now() - interval '" + STALE_DEVICE_DAYS + " days')" +
        "  + (SELECT count(*) FROM totp_credential t WHERE t.frontend_account_id = a.id AND t.confirmed_at IS NOT NULL" +
        "     AND t.status IN ($1, $2) AND coalesce(t.last_used_at, t.confirmed_at) < now() - interval '" + STALE_DEVICE_DAYS + " days')," +
        "  a.disabled IS TRUE," +
        "  EXISTS (SELECT 1 FROM account_sign_in_restriction r WHERE r.frontend_account_id = a.id AND r.lifted_at IS NULL" +
        "          AND r.method = '" + AccountSignInRestrictionStore.PASSWORD_METHOD + "')," +
        "  EXISTS (SELECT 1 FROM account_sign_in_restriction r WHERE r.frontend_account_id = a.id AND r.lifted_at IS NULL" +
        "          AND r.method = '" + AccountSignInRestrictionStore.ACCOUNT_METHOD + "')," +
        "  EXISTS (SELECT 1 FROM account_sign_in_restriction r WHERE r.frontend_account_id = a.id AND r.lifted_at IS NULL" +
        "          AND r.method = '" + AccountSignInRestrictionStore.BLOCKED_METHOD + "')" +
        " FROM frontend_account a " + PERSON_OF_ACCOUNT +
        " WHERE a.backoffice ORDER BY 4 DESC, 3, 2 LIMIT " + MAX_ROWS;

    // ---- who is signed in ----------------------------------------------------------------------------------------

    // Live session families of STAFF accounts, newest activity first — whatever their audience: a setup-only or
    // recovery-only session (front-office audience) of a staff account is exactly what a password or mailbox thief
    // holds, while members' sessions are none of this page's business and are never listed. The id goes out only as a
    // one-way reference, which EndSessions maps back within the same account. Through person_id, which is indexed
    // (auth_session_person_live) where account_id is not.
    private static final String SESSION_REF = "substring(md5(s.id), 1, 16)";
    private static final String LIVE_SESSIONS_SQL =
        "SELECT " + SESSION_REF + ", a.id, a.username, concat_ws(' ', p.first_name, p.last_name)," +
        "  coalesce(s.audience, s.tier), s.methods, " + utc("s.issued") + ", " + utc("s.last_renewed") + "," +
        "  s.id = $1" +
        " FROM auth_session s JOIN person sp ON sp.id = s.person_id" +
        " JOIN frontend_account a ON a.id = sp.frontend_account_id " + PERSON_OF_ACCOUNT +
        " WHERE s.revoked IS NULL AND s.absolute_expiry > now() AND a.backoffice" +
        " ORDER BY s.last_renewed DESC LIMIT " + MAX_ROWS;

    private static final String SESSION_OF_ACCOUNT_SQL =
        "SELECT s.id FROM auth_session s WHERE s.person_id IN (SELECT id FROM person WHERE frontend_account_id = $1)" +
        "   AND " + SESSION_REF + " = $2 AND s.revoked IS NULL AND s.absolute_expiry > now()";
    private static final String REVOKE_FAMILY_SQL =
        "UPDATE auth_session SET revoked = now(), revoked_reason = 'super-administrator' WHERE id = $1 AND revoked IS NULL";
    private static final String AUDIT_SESSIONS_SQL =
        "INSERT INTO second_factor_reset (frontend_account_id, what, reset_by_person_id, note, sessions_revoked)" +
        " VALUES ($1, 'SESSIONS', $2, $3, $4) returning id";

    // ---- recent decisions ----------------------------------------------------------------------------------------

    // One timeline over the audit tables. A super administrator's lift is already a second_factor_reset row (PASSWORD,
    // ACCOUNT, UNBLOCK), so a restriction's lift is listed only when the owner did it (reopening their own password)
    private static final String RECENT = " > now() - interval '" + EVENT_DAYS + " days'";
    private static final String EVENTS_SQL =
        "SELECT e.kind, e.detail, " + utc("e.at") + ", e.account_id, a.username, concat_ws(' ', p.first_name, p.last_name)," +
        "  concat_ws(' ', bp.first_name, bp.last_name), e.note, e.sessions" +
        " FROM (" +
        "   SELECT 'RESET' kind, r.what detail, " + asTimestamptz("r.created_at") + " at, r.frontend_account_id account_id," +
        "          r.reset_by_person_id by_person, r.note, r.sessions_revoked sessions" +
        "     FROM second_factor_reset r WHERE r.created_at" + RECENT +
        "   UNION ALL SELECT 'CLOSED', x.method, x.created_at, x.frontend_account_id, x.created_by_person_id, x.note, NULL" +
        "     FROM account_sign_in_restriction x WHERE x.created_at" + RECENT +
        "   UNION ALL SELECT 'REOPENED', x.method, x.lifted_at, x.frontend_account_id, x.lifted_by_person_id, NULL, NULL" +
        "     FROM account_sign_in_restriction x WHERE x.lifted_at" + RECENT +
        "      AND x.lifted_by_person_id IN (SELECT id FROM person WHERE frontend_account_id = x.frontend_account_id)" +
        "   UNION ALL SELECT 'ALARM', NULL, s.raised_at, NULL, s.raised_by_person_id, NULL, NULL" +
        "     FROM security_alarm s WHERE s.raised_at" + RECENT +
        "   UNION ALL SELECT 'DECISION', 'passkey:' || c.status, " + asTimestamptz("c.decided_at") + ", c.frontend_account_id," +
        "          c.decided_by_person_id, NULL, NULL" +
        "     FROM webauthn_credential c WHERE c.decided_by_person_id IS NOT NULL AND c.decided_at" + RECENT +
        "   UNION ALL SELECT 'DECISION', 'totp:' || t.status, " + asTimestamptz("t.decided_at") + ", t.frontend_account_id," +
        "          t.decided_by_person_id, NULL, NULL" +
        "     FROM totp_credential t WHERE t.decided_by_person_id IS NOT NULL AND t.decided_at" + RECENT +
        " ) e LEFT JOIN frontend_account a ON a.id = e.account_id " + PERSON_OF_ACCOUNT +
        " LEFT JOIN person bp ON bp.id = e.by_person" +
        // One more than shown, so the reply can say the list was cut
        " ORDER BY e.at DESC LIMIT " + (MAX_EVENTS + 1);

    private final SignInApprovals signInApprovals;

    SignInSecurityBoard(SignInApprovals signInApprovals) {
        this.signInApprovals = signInApprovals;
    }

    // ===== reads ===================================================================================================

    Future<String> rosterJson() {
        return executeRawQuery(ROSTER_SQL, SecondFactorAccess.approvedStatuses()).map(result -> {
            AstArray people = AST.createArray();
            for (int row = 0; row < result.getRowCount(); row++) {
                AstObject person = AST.createObject();
                person.set("accountId", longValue(result.getValue(row, 0)));
                setIfPresent(person, "username", string(result, row, 1));
                setIfPresent(person, "personName", blankToNull(string(result, row, 2)));
                person.set("superAdmin", bool(result.getValue(row, 3)));
                person.set("passkeys", longValue(result.getValue(row, 4)));
                boolean hasApp = bool(result.getValue(row, 5));
                person.set("app", hasApp);
                person.set("pending", longValue(result.getValue(row, 6)));
                if (hasApp)
                    person.set("backupCodesLeft", longValue(result.getValue(row, 7)));
                setIfPresent(person, "lastUsedAt", string(result, row, 8));
                person.set("staleDevices", longValue(result.getValue(row, 9)));
                person.set("disabled", bool(result.getValue(row, 10)));
                person.set("passwordClosed", bool(result.getValue(row, 11)));
                person.set("theftClosed", bool(result.getValue(row, 12)));
                person.set("blocked", bool(result.getValue(row, 13)));
                people.push(person);
            }
            AstObject reply = AST.createObject();
            reply.setArray("people", people);
            reply.set("truncated", result.getRowCount() >= MAX_ROWS);
            reply.set("staleAfterDays", STALE_DEVICE_DAYS);
            return Json.formatObject(reply);
        });
    }

    /** {@code ownFamilyId} (the caller's session, read on its thread) is marked as the current one. */
    Future<String> liveSessionsJson(String ownFamilyId) {
        return executeRawQuery(LIVE_SESSIONS_SQL, ownFamilyId == null ? "" : ownFamilyId).map(result -> {
            AstArray sessions = AST.createArray();
            for (int row = 0; row < result.getRowCount(); row++) {
                AstObject session = AST.createObject();
                setIfPresent(session, "ref", string(result, row, 0));
                Object accountId = result.getValue(row, 1);
                if (accountId != null)
                    session.set("accountId", longValue(accountId));
                setIfPresent(session, "username", string(result, row, 2));
                setIfPresent(session, "personName", blankToNull(string(result, row, 3)));
                setIfPresent(session, "app", string(result, row, 4));
                setIfPresent(session, "methods", string(result, row, 5));
                setIfPresent(session, "signedInAt", string(result, row, 6));
                setIfPresent(session, "lastActiveAt", string(result, row, 7));
                session.set("current", bool(result.getValue(row, 8)));
                sessions.push(session);
            }
            AstObject reply = AST.createObject();
            reply.setArray("sessions", sessions);
            reply.set("truncated", result.getRowCount() >= MAX_ROWS);
            return Json.formatObject(reply);
        });
    }

    Future<String> eventsJson() {
        return executeRawQuery(EVENTS_SQL).map(result -> {
            AstArray events = AST.createArray();
            int shown = Math.min(result.getRowCount(), MAX_EVENTS);
            for (int row = 0; row < shown; row++) {
                AstObject event = AST.createObject();
                setIfPresent(event, "kind", string(result, row, 0));
                setIfPresent(event, "detail", string(result, row, 1));
                setIfPresent(event, "at", string(result, row, 2));
                Object accountId = result.getValue(row, 3);
                if (accountId != null)
                    event.set("accountId", longValue(accountId));
                setIfPresent(event, "username", string(result, row, 4));
                setIfPresent(event, "personName", blankToNull(string(result, row, 5)));
                setIfPresent(event, "byPersonName", blankToNull(string(result, row, 6)));
                setIfPresent(event, "note", blankToNull(string(result, row, 7)));
                Object sessions = result.getValue(row, 8);
                if (sessions != null)
                    event.set("sessionsRevoked", longValue(sessions));
                events.push(event);
            }
            AstObject reply = AST.createObject();
            reply.setArray("events", events);
            reply.set("days", EVENT_DAYS);
            reply.set("truncated", result.getRowCount() > MAX_EVENTS);
            return Json.formatObject(reply);
        });
    }

    // ===== writes ==================================================================================================

    /** Signs one session of the account out ({@code sessionRef}), or all of them when it is null. */
    Future<String> endSessions(ModalityUserPrincipal approver, Object approverAccountId, Object targetArgument,
                               String sessionRef, String note) {
        Object target = TotpCredentialStore.normaliseId(targetArgument);
        String checkedNote = checkedNote(note);
        if (target == null || checkedNote == null || isOwn(target, approverAccountId))
            return failure();
        Object approverPersonId = TotpCredentialStore.normaliseId(approver.getUserPersonId());
        Future<Integer> ended = sessionRef == null || sessionRef.isBlank()
            ? signInApprovals.revokeAccountSessions(target)
            : executeRawQuery(SESSION_OF_ACCOUNT_SQL, target, sessionRef).compose(match -> {
                if (match.getRowCount() != 1)
                    return Future.succeededFuture(0); // already ended, or not this account's
                String familyId = match.getValue(0, 0);
                return executeRawSubmit(REVOKE_FAMILY_SQL, familyId).map(ignored -> {
                    // Refused on sight from here on, and the device told at once — as every other revocation is
                    RevokedFamilies.noteAll(List.of(new SessionFamilyStore.Revocation(familyId, System.currentTimeMillis())));
                    return 1;
                });
            });
        return ended.compose(count -> executeRawSubmit(AUDIT_SESSIONS_SQL, target, approverPersonId, checkedNote, count)
            .map(ignored -> {
                Console.log(LOG_PREFIX + "Super administrator person " + approverPersonId + " signed out " + count
                            + " session(s) of account " + target);
                return replyWith("sessionsRevoked", count);
            }));
    }

    /**
     * Blocks a back-office account and ends its sessions. Never a super administrator's: a session taken from one must
     * not be able to lock out the colleague who would notice — and nobody could then rescue them but the database.
     * (Take the super-administrator role away first, on the Authorizations page, if it has to happen.)
     */
    Future<String> block(ModalityUserPrincipal approver, Object approverAccountId, Object targetArgument, String note) {
        Object target = TotpCredentialStore.normaliseId(targetArgument);
        String checkedNote = checkedNote(note);
        if (target == null || checkedNote == null || isOwn(target, approverAccountId))
            return failure();
        return SecondFactorAccess.isSuperAdminAccount(target).compose(isSuperAdmin -> {
            if (!Boolean.FALSE.equals(isSuperAdmin)) { // fail closed: an unanswered check refuses
                Console.log(LOG_PREFIX + "Block refused: account " + target + " belongs to a super administrator");
                return failure();
            }
            return blockNonSuperAdmin(approver, target, checkedNote);
        });
    }

    private Future<String> blockNonSuperAdmin(ModalityUserPrincipal approver, Object target, String checkedNote) {
        return AccountSignInRestrictionStore.blockAccount(target, approver.getUserPersonId(), checkedNote).compose(blocked -> {
            if (!Boolean.TRUE.equals(blocked))
                return failure(); // not a back-office account, or no such account
            // The flag stops the return; ending the sessions stops what is in progress (renewal does not read the flag)
            return signInApprovals.revokeAccountSessions(target).map(count -> replyWith("sessionsRevoked", count));
        });
    }

    /** Lifts a super administrator's block — never the owner's own theft report, which keeps its own rescue. */
    Future<String> unblock(ModalityUserPrincipal approver, Object approverAccountId, Object targetArgument, String note) {
        Object target = TotpCredentialStore.normaliseId(targetArgument);
        String checkedNote = checkedNote(note);
        if (target == null || checkedNote == null || isOwn(target, approverAccountId))
            return failure();
        return AccountSignInRestrictionStore.unblockAccount(target, approver.getUserPersonId(), checkedNote)
            .compose(lifted -> Boolean.TRUE.equals(lifted) ? Future.succeededFuture(replyWith("unblocked", true)) : failure());
    }

    // ===== plumbing ================================================================================================

    private static boolean isOwn(Object target, Object approverAccountId) {
        boolean own = Objects.equals(target, TotpCredentialStore.normaliseId(approverAccountId));
        if (own)
            Console.log(LOG_PREFIX + "Sign-in security action refused: a super administrator targeted their own account");
        return own;
    }

    /**
     * The note every write records: required, trimmed, at most the column's 256 characters, and without control or
     * invisible formatting characters (bidi overrides could make an audit line read as something it is not).
     */
    private static String checkedNote(String note) {
        String trimmed = note == null ? "" : note.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        return trimmed.isEmpty() || trimmed.length() > 256 ? null : trimmed;
    }

    private static String replyWith(String key, Object value) {
        AstObject reply = AST.createObject();
        reply.set(key, value);
        return Json.formatObject(reply);
    }

    private static <T> Future<T> failure() {
        return Future.failedFuture("[%s] The second-factor change could not be completed".formatted(
            ModalityAuthenticationI18nKeys.AuthnTotpEnrolmentError));
    }

    private static void setIfPresent(AstObject json, String key, Object value) {
        if (value != null)
            json.set(key, value);
    }

    private static String string(QueryResult result, int row, int column) {
        Object value = result.getValue(row, column);
        return value == null ? null : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static long longValue(Object value) {
        Long number = Numbers.toLong(value);
        return number == null ? 0L : number;
    }
}

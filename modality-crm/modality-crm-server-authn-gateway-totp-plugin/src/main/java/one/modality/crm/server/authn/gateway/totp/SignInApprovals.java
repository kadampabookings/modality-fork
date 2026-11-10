package one.modality.crm.server.authn.gateway.totp;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.json.Json;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.Strings;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import dev.webfx.stack.session.token.RevokedFamilies;
import dev.webfx.stack.session.token.SessionFamilyStore;
import one.modality.crm.server.authn.gateway.shared.AccountSignInRestrictionStore;
import one.modality.crm.server.authn.gateway.shared.FactorApprovalNotice;
import one.modality.crm.server.authn.gateway.shared.FactorRequest;
import one.modality.crm.server.authn.gateway.shared.IpLocator;
import one.modality.crm.server.authn.gateway.shared.SecondFactorAccess;
import one.modality.crm.server.authn.gateway.shared.SecondFactorPolicy;
import one.modality.crm.shared.services.authn.ModalityAuthenticationI18nKeys;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static one.modality.crm.server.authn.gateway.totp.TotpCredentialStore.executeRawQuery;
import static one.modality.crm.server.authn.gateway.totp.TotpCredentialStore.executeRawSubmit;
import static one.modality.crm.server.authn.gateway.totp.TotpCredentialStore.returnedARow;

/**
 * The approval queue for BOTH second factors, and the standing of an account under "setup first" — the server half of
 * the back office's Sign-in approvals page, its dashboard cards, and each staff member's own setup screen.
 *
 * <p>Lives in the TOTP plugin because this is where the second-factor super-administrator operations already are
 * (lookup, reset) and where the by-person session revoke is; it reads and decides the passkey rows by raw SQL, with
 * the same fences {@code WebAuthnCredentialStore} applies to its own: PENDING only, never the approver's own account,
 * only a live back-office account's.
 *
 * <p>Every method that decides or reveals something about another account is reached only after
 * {@code requireSuperAdmin} (a back-office-audience session of a super administrator, re-checked on every call). The
 * status call is the caller's own, on any session. Logs carry ids only.
 *
 * @author Claude Code
 */
final class SignInApprovals {

    private static final String LOG_PREFIX = "[2fa] ";
    static final String KIND_PASSKEY = "passkey";
    static final String KIND_TOTP = "totp";
    private static final int CONTEXT_RETENTION_DAYS = 90;

    // ---- the queue ------------------------------------------------------------------------------------------------

    /**
     * A {@code timestamp without time zone} column (written by {@code now()} in the session's zone) as an ISO instant —
     * {@code 2026-11-06T14:22:00Z}. Sent as an instant because the approver's browser is not in the server's zone: a
     * zone-less time read as the viewer's local time put "1 hour ago" on a request made minutes earlier.
     */
    private static String utcInstant(String column) {
        return "to_char((" + column + " AT TIME ZONE current_setting('TimeZone')) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')";
    }

    // Both tables, PENDING rows of live back-office accounts other than the approver's; a TOTP only once confirmed (an
    // unconfirmed one is a setup still in progress, not a request). host() renders the inet without its mask.
    private static final String PENDING_SQL =
        "SELECT 'passkey', c.id, c.frontend_account_id, a.username, c.label, c.aaguid, c.transports, " + utcInstant("c.created_at") + "," +
        "       host(c.request_ip), c.request_location, c.request_user_agent, c.request_sign_in_method" +
        "  FROM webauthn_credential c JOIN frontend_account a ON a.id = c.frontend_account_id" +
        " WHERE c.status = 'PENDING' AND a.backoffice AND a.disabled IS NOT TRUE AND c.frontend_account_id <> $1" +
        " UNION ALL " +
        "SELECT 'totp', t.id, t.frontend_account_id, a.username, t.label, NULL, NULL, " + utcInstant("t.created_at") + "," +
        "       host(t.request_ip), t.request_location, t.request_user_agent, t.request_sign_in_method" +
        "  FROM totp_credential t JOIN frontend_account a ON a.id = t.frontend_account_id" +
        " WHERE t.status = 'PENDING' AND t.confirmed_at IS NOT NULL AND a.backoffice AND a.disabled IS NOT TRUE" +
        "   AND t.frontend_account_id <> $1" +
        " ORDER BY 8, 2";
    private static final int Q_KIND = 0, Q_ID = 1, Q_ACCOUNT = 2, Q_USERNAME = 3, Q_LABEL = 4, Q_AAGUID = 5,
        Q_TRANSPORTS = 6, Q_CREATED = 7, Q_IP = 8, Q_LOCATION = 9, Q_AGENT = 10, Q_METHOD = 11;

    // Per account in the queue: who it is (the owner person: name, phone, home organisation), how many factors it
    // already has approved, where its approved devices were added from, and whether an emailed sign-in link or code was
    // redeemed for its address in the last 7 days (the sign of a password reset). $1 = account ids.
    private static final String ACCOUNT_FACTS_SQL =
        "SELECT a.id," +
        "       (SELECT concat_ws(' ', p.first_name, p.last_name) FROM person p WHERE p.frontend_account_id = a.id" +
        "         ORDER BY p.removed, p.owner DESC, p.id LIMIT 1)," +
        "       (SELECT p.phone FROM person p WHERE p.frontend_account_id = a.id ORDER BY p.removed, p.owner DESC, p.id LIMIT 1)," +
        "       (SELECT o.name FROM person p JOIN organization o ON o.id = p.organization_id WHERE p.frontend_account_id = a.id" +
        "         ORDER BY p.removed, p.owner DESC, p.id LIMIT 1)," +
        "       (SELECT count(*) FROM webauthn_credential c WHERE c.frontend_account_id = a.id AND c.status = 'APPROVED')" +
        "     + (SELECT count(*) FROM totp_credential t WHERE t.frontend_account_id = a.id AND t.status = 'APPROVED'" +
        "                                                 AND t.confirmed_at IS NOT NULL)," +
        "       (SELECT string_agg(DISTINCT l, '|') FROM (" +
        "           SELECT c.request_location l FROM webauthn_credential c WHERE c.frontend_account_id = a.id AND c.status = 'APPROVED'" +
        "           UNION SELECT t.request_location FROM totp_credential t WHERE t.frontend_account_id = a.id AND t.status = 'APPROVED'" +
        "       ) locations WHERE l IS NOT NULL)," +
        "       EXISTS (SELECT 1 FROM magic_link m WHERE lower(m.email) = lower(a.username) AND m.link_type = 'LOGIN'" +
        "                AND m.usage_date > now() - interval '7 days')" +
        "  FROM frontend_account a WHERE a.id IN "; // + one placeholder per account, see accountFactsSql
    private static final int F_ID = 0, F_NAME = 1, F_PHONE = 2, F_ORGANIZATION = 3, F_APPROVED = 4, F_LOCATIONS = 5, F_RECENT_EMAIL = 6;

    // ---- decisions --------------------------------------------------------------------------------------------------

    // A decision lands only on a PENDING row of a live back-office account other than the approver's ($5) — the
    // passkey store's DECIDE_PENDING_SQL fences, for both tables. approval_kind records that a PERSON decided.
    private static final String DECIDE_PASSKEY_SQL =
        "UPDATE webauthn_credential SET status = $1, decided_by_person_id = $2, decided_at = now()," +
        " approval_kind = CASE WHEN $1::varchar = 'APPROVED' THEN 'PERSON' END" +
        " WHERE id = $3 AND status = $4 AND frontend_account_id <> $5" +
        " AND EXISTS (SELECT 1 FROM frontend_account a WHERE a.id = webauthn_credential.frontend_account_id" +
        "             AND a.backoffice = true AND a.disabled IS NOT TRUE)" +
        " returning frontend_account_id";
    private static final String DECIDE_TOTP_SQL =
        "UPDATE totp_credential SET status = $1, decided_by_person_id = $2, decided_at = now()," +
        " approval_kind = CASE WHEN $1::varchar = 'APPROVED' THEN 'PERSON' END" +
        " WHERE id = $3 AND status = $4 AND frontend_account_id <> $5 AND confirmed_at IS NOT NULL" +
        " AND EXISTS (SELECT 1 FROM frontend_account a WHERE a.id = totp_credential.frontend_account_id" +
        "             AND a.backoffice = true AND a.disabled IS NOT TRUE)" +
        " returning frontend_account_id";
    private static final String ACCOUNT_OF_PASSKEY_SQL = "SELECT frontend_account_id FROM webauthn_credential WHERE id = $1";
    private static final String ACCOUNT_OF_TOTP_SQL = "SELECT frontend_account_id FROM totp_credential WHERE id = $1";
    private static final String PERSONS_OF_ACCOUNT_SQL = "SELECT id FROM person WHERE frontend_account_id = $1";

    // The request context serves the decision, not a history: cleared 90 days after it, by every decision (no
    // scheduler needed — decisions are frequent enough while this matters, and rare rows age out at the next one).
    private static final String CLEAR_OLD_CONTEXT_PASSKEY_SQL =
        "UPDATE webauthn_credential SET request_ip = NULL, request_location = NULL, request_user_agent = NULL" +
        " WHERE coalesce(decided_at, created_at) < now() - interval '" + CONTEXT_RETENTION_DAYS + " days'" +
        "   AND (request_ip IS NOT NULL OR request_location IS NOT NULL OR request_user_agent IS NOT NULL)";
    private static final String CLEAR_OLD_CONTEXT_TOTP_SQL =
        "UPDATE totp_credential SET request_ip = NULL, request_location = NULL, request_user_agent = NULL" +
        " WHERE coalesce(decided_at, confirmed_at, created_at) < now() - interval '" + CONTEXT_RETENTION_DAYS + " days'" +
        "   AND (request_ip IS NOT NULL OR request_location IS NOT NULL OR request_user_agent IS NOT NULL)";

    // The approver emails carry the same personal context (name, phone, IP, place, browser) into the `mail` table, so
    // they age out with it: subject reduced to its prefix, body replaced. What is in the super administrators' own
    // mailboxes is outside this system's reach (flagged in docs/security/backoffice-second-factor.md).
    // The lower bound is the day approver emails started: no older mail can match, and without it the date range
    // covers nearly all of `mail`, so the unindexed LIKE turned every boot and decision into a full scan (6 s in prod).
    private static final String CLEAR_OLD_APPROVER_MAIL_SQL =
        "UPDATE mail SET subject = $1, content = $2" +
        " WHERE subject LIKE $3 AND date >= '2026-11-06' AND date < now() - interval '" + CONTEXT_RETENTION_DAYS + " days'";

    // ---- coverage and status ---------------------------------------------------------------------------------------

    // The approved-factor rule, as every query below applies it: $1/$2 = SecondFactorAccess.approvedStatuses(), the
    // statuses that count under the approval switch
    private static final String APPROVED_FACTORS_OF_ACCOUNT = SecondFactorAccess.approvedFactorCountSql("a.id", "$1, $2");

    // Every live back-office account, with its approved and pending factor counts
    private static final String COVERAGE_SQL =
        "SELECT count(*) FILTER (WHERE approved > 0), count(*) FILTER (WHERE approved = 0 AND pending > 0)," +
        "       count(*) FILTER (WHERE approved = 0 AND pending = 0), count(*) FROM (" +
        "  SELECT a.id, " + APPROVED_FACTORS_OF_ACCOUNT + " approved," +
        "    (SELECT count(*) FROM webauthn_credential c WHERE c.frontend_account_id = a.id AND c.status = 'PENDING')" +
        "  + (SELECT count(*) FROM totp_credential t WHERE t.frontend_account_id = a.id AND t.confirmed_at IS NOT NULL" +
        "                                             AND t.status = 'PENDING') pending" +
        "    FROM frontend_account a WHERE a.backoffice AND a.disabled IS NOT TRUE) accounts";

    // Live super administrators with no approved factor (named on the dashboard and at boot), and how many hold one
    // (the approver's bootstrap test)
    private static final String SUPER_ADMINS_WITHOUT_SQL =
        "SELECT DISTINCT concat_ws(' ', p.first_name, p.last_name), a.username FROM authorization_super_admin s" +
        "  JOIN person p ON p.id = s.super_admin_id JOIN frontend_account a ON a.id = p.frontend_account_id" +
        " WHERE p.removed IS NOT TRUE AND a.disabled IS NOT TRUE AND " + APPROVED_FACTORS_OF_ACCOUNT + " = 0";
    private static final String SUPER_ADMINS_WITH_FACTOR_SQL =
        "SELECT count(DISTINCT a.id) FROM authorization_super_admin s" +
        "  JOIN person p ON p.id = s.super_admin_id JOIN frontend_account a ON a.id = p.frontend_account_id" +
        " WHERE p.removed IS NOT TRUE AND a.disabled IS NOT TRUE AND " + APPROVED_FACTORS_OF_ACCOUNT + " > 0";

    // The caller's own factors that are not APPROVED: pending and rejected, as the setup screen shows them.
    private static final String OWN_UNAPPROVED_SQL =
        "SELECT 'passkey', id, label, created_at, status FROM webauthn_credential WHERE frontend_account_id = $1 AND status <> 'APPROVED'" +
        " UNION ALL " +
        "SELECT 'totp', id, label, created_at, status FROM totp_credential WHERE frontend_account_id = $1 AND status <> 'APPROVED'" +
        "   AND confirmed_at IS NOT NULL ORDER BY 4";

    private static final String INSERT_WINDOW_SQL =
        "INSERT INTO second_factor_reset (frontend_account_id, what, reset_by_person_id, note, sessions_revoked, window_until)" +
        " VALUES ($1, 'ENROLMENT_WINDOW', $2, $3, 0, now() + interval '" + SecondFactorAccess.ENROLMENT_WINDOW_HOURS + " hours')" +
        " returning id";

    private final TotpCredentialStore credentialStore;

    SignInApprovals(TotpCredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    // ===== the queue ===============================================================================================

    /** The approval queue with each request's context and warnings — see the wire contract. */
    Future<String> listJson(Object approverAccountId) {
        return executeRawQuery(PENDING_SQL, approverAccountId).compose(pending -> {
            Set<Long> accountIds = new LinkedHashSet<>();
            for (int row = 0; row < pending.getRowCount(); row++)
                accountIds.add(longValue(pending.getValue(row, Q_ACCOUNT)));
            Future<QueryResult> facts = accountIds.isEmpty() ? Future.succeededFuture(null)
                : executeRawQuery(accountFactsSql(accountIds.size()), accountIds.toArray());
            return facts.map(accountFacts -> {
                Map<Long, Integer> factsRow = new LinkedHashMap<>();
                if (accountFacts != null)
                    for (int row = 0; row < accountFacts.getRowCount(); row++)
                        factsRow.put(longValue(accountFacts.getValue(row, F_ID)), row);
                Map<Long, Integer> pendingPerAccount = new LinkedHashMap<>();
                for (int row = 0; row < pending.getRowCount(); row++)
                    pendingPerAccount.merge(longValue(pending.getValue(row, Q_ACCOUNT)), 1, Integer::sum);
                AstArray requests = AST.createArray();
                for (int row = 0; row < pending.getRowCount(); row++) {
                    long accountId = longValue(pending.getValue(row, Q_ACCOUNT));
                    Integer factRow = factsRow.get(accountId);
                    requests.push(requestJson(pending, row, accountFacts, factRow, pendingPerAccount.getOrDefault(accountId, 1)));
                }
                AstObject reply = AST.createObject();
                reply.set("approvalEnabled", SecondFactorPolicy.get().isFactorApprovalRequired());
                if (IpLocator.isEnabled())
                    reply.set("ipLocationAttribution", "IP geolocation by DB-IP (db-ip.com)");
                reply.setArray("pending", requests);
                return Json.formatObject(reply);
            });
        });
    }

    private static AstObject requestJson(QueryResult pending, int row, QueryResult facts, Integer factRow, int pendingForAccount) {
        AstObject json = AST.createObject();
        json.set("kind", string(pending, row, Q_KIND));
        json.set("id", longValue(pending.getValue(row, Q_ID)));
        json.set("accountId", longValue(pending.getValue(row, Q_ACCOUNT)));
        setIfPresent(json, "username", string(pending, row, Q_USERNAME));
        setIfPresent(json, "label", string(pending, row, Q_LABEL));
        setIfPresent(json, "aaguid", string(pending, row, Q_AAGUID));
        setIfPresent(json, "transports", string(pending, row, Q_TRANSPORTS));
        setIfPresent(json, "createdAt", string(pending, row, Q_CREATED));
        String location = string(pending, row, Q_LOCATION);
        String method = string(pending, row, Q_METHOD);
        setIfPresent(json, "requestIp", string(pending, row, Q_IP));
        setIfPresent(json, "requestLocation", location);
        setIfPresent(json, "requestUserAgent", string(pending, row, Q_AGENT));
        setIfPresent(json, "requestSignInMethod", primaryMethod(method));
        AstArray phones = AST.createArray();
        AstArray organizations = AST.createArray();
        AstArray previousLocations = AST.createArray();
        AstArray warnings = AST.createArray();
        boolean firstFactor = true;
        if (facts != null && factRow != null) {
            setIfPresent(json, "personName", blankToNull(string(facts, factRow, F_NAME)));
            String phone = blankToNull(string(facts, factRow, F_PHONE));
            if (phone != null)
                phones.push(phone);
            String organization = blankToNull(string(facts, factRow, F_ORGANIZATION));
            if (organization != null)
                organizations.push(organization);
            firstFactor = longValue(facts.getValue(factRow, F_APPROVED)) == 0;
            String locations = string(facts, factRow, F_LOCATIONS);
            List<String> previous = locations == null ? List.of() : List.of(locations.split("\\|"));
            previous.forEach(previousLocations::push);
            if (location != null && !previous.isEmpty() && !previous.contains(location))
                warnings.push("NEW_LOCATION");
            if (Boolean.TRUE.equals(facts.getValue(factRow, F_RECENT_EMAIL)))
                warnings.push("PASSWORD_RECENTLY_RESET");
        }
        if (method != null && (method.contains("ml") || method.contains("code")))
            warnings.push("EMAILED_SIGN_IN");
        if (pendingForAccount > 1)
            warnings.push("SEVERAL_PENDING");
        json.setArray("phones", phones);
        json.setArray("organizations", organizations);
        json.setArray("previousLocations", previousLocations);
        json.set("firstFactor", firstFactor);
        json.setArray("warnings", warnings);
        return json;
    }

    /** ACCOUNT_FACTS_SQL with {@code ($1, …, $n)} — ids bound one by one rather than as an array. */
    private static String accountFactsSql(int count) {
        StringBuilder placeholders = new StringBuilder("(");
        for (int i = 1; i <= count; i++)
            placeholders.append(i == 1 ? "" : ", ").append('$').append(i);
        return ACCOUNT_FACTS_SQL + placeholders.append(')');
    }

    /** The one sign-in method to show: an emailed one if any (that is the warning), else the strongest. */
    private static String primaryMethod(String methods) {
        if (methods == null)
            return null;
        for (String candidate : new String[]{"ml", "code", "pk", "totp", "pwd"})
            for (String method : methods.split(","))
                if (candidate.equals(method.trim()))
                    return candidate;
        return null;
    }

    // ===== the approver ============================================================================================


    // ===== a decision ==============================================================================================

    /**
     * Approves or rejects one pending factor. Approving an account's FIRST factor ends every session of the account:
     * a session opened before it — setup-only, perhaps the attacker's who triggered the request — must not inherit the
     * new access; the owner signs in again with the device. Rejecting with {@code lockAccount} also ends the sessions
     * and closes the account's password sign-in (and with it emailed recovery), for a request the approver judged an
     * attack after phoning the person.
     */
    Future<String> decide(ModalityUserPrincipal approver, Object approverAccountId, SecondFactorAccess.ApproverStanding standing,
                          String kind, Object idArgument, boolean approve, boolean lockAccount) {
        Long id = Numbers.toLong(idArgument);
        boolean passkey = KIND_PASSKEY.equals(kind);
        if (id == null || (!passkey && !KIND_TOTP.equals(kind)))
            return failure();
        Object approverPersonId = TotpCredentialStore.normaliseId(approver.getUserPersonId());
        String accountSql = passkey ? ACCOUNT_OF_PASSKEY_SQL : ACCOUNT_OF_TOTP_SQL;
        return executeRawQuery(accountSql, id).compose(owner -> {
            if (owner.getRowCount() != 1)
                return failure();
            Long accountId = longValue(owner.getValue(0, 0));
            if (Objects.equals(accountId, TotpCredentialStore.normaliseId(approverAccountId))) {
                Console.log(LOG_PREFIX + "Decision refused: person " + approverPersonId + " targeted their own account");
                return failure();
            }
            // A BOOTSTRAP approver (a password session while no super administrator holds a factor) may only bring the
            // first super administrators in — never decide a staff member's device on a password
            Future<Boolean> bootstrapAllowed = standing != SecondFactorAccess.ApproverStanding.BOOTSTRAP
                ? Future.succeededFuture(true) : SecondFactorAccess.isSuperAdminAccount(accountId);
            // Counted BEFORE the decision, so "first" means first: approving makes it one
            return bootstrapAllowed.compose(allowed -> {
                if (!Boolean.TRUE.equals(allowed)) {
                    Console.log(LOG_PREFIX + "Decision refused: person " + approverPersonId + " is on a bootstrap (password)"
                                + " session and account " + accountId + " is not a super administrator's");
                    return SignInApprovals.<Integer>failure();
                }
                return approve ? SecondFactorAccess.approvedFactorCount(accountId) : Future.succeededFuture(1);
            }).compose(before -> executeRawSubmit(passkey ? DECIDE_PASSKEY_SQL : DECIDE_TOTP_SQL,
                    approve ? "APPROVED" : "REJECTED", approverPersonId, id, "PENDING", approverAccountId)
                .compose(result -> {
                    if (!returnedARow(result))
                        return failure();
                    Console.log(LOG_PREFIX + (approve ? "Approved " : "Rejected ") + kind + " row " + id + " of account "
                                + accountId + " by person " + approverPersonId);
                    clearOldContext();
                    boolean endSessions = approve ? before == 0 : lockAccount;
                    // Sessions FIRST, and whatever happens to the password close after: the decision has already
                    // committed (it cannot be retried — the row is no longer PENDING), so a failed close must not leave
                    // a judged-hostile session running. A failure of either is logged and reported, never swallowed.
                    Future<Integer> revoked = endSessions ? revokeAccountSessions(accountId) : Future.succeededFuture(0);
                    return revoked.compose(count -> {
                        if (approve || !lockAccount)
                            return Future.succeededFuture(count);
                        return AccountSignInRestrictionStore.closePassword(accountId, approverPersonId,
                                "Rejected sign-in device judged an attack")
                            .map(closed -> count)
                            .recover(e -> {
                                Console.log(LOG_PREFIX + "🛑 Account " + accountId + " was rejected and signed out, but its"
                                            + " password sign-in could not be closed (" + e.getMessage() + ")");
                                return Future.failedFuture(e);
                            });
                    }).map(count -> {
                        AstObject reply = AST.createObject();
                        reply.set("sessionsRevoked", count);
                        return Json.formatObject(reply);
                    });
                }));
        });
    }

    /** Ends every session of every person on the account — the reset's own revoke, per person. */
    Future<Integer> revokeAccountSessions(Object accountId) {
        return executeRawQuery(PERSONS_OF_ACCOUNT_SQL, accountId).compose(persons -> {
            Future<Integer> total = Future.succeededFuture(0);
            for (int row = 0; row < persons.getRowCount(); row++) {
                Object personId = TotpCredentialStore.normaliseId(persons.getValue(row, 0));
                total = total.compose(soFar -> credentialStore.revokeLiveSessions(personId).map(revoked -> soFar + revoked));
            }
            return total;
        });
    }

    void clearOldContext() {
        executeRawSubmit(CLEAR_OLD_CONTEXT_PASSKEY_SQL).compose(ignored -> executeRawSubmit(CLEAR_OLD_CONTEXT_TOTP_SQL))
            .compose(ignored -> executeRawSubmit(CLEAR_OLD_APPROVER_MAIL_SQL, FactorApprovalNotice.APPROVER_SUBJECT_PREFIX,
                "<p>This sign-in approval request was cleared after " + CONTEXT_RETENTION_DAYS + " days.</p>",
                FactorApprovalNotice.APPROVER_SUBJECT_PREFIX + ":%"))
            .onFailure(e -> Console.log(LOG_PREFIX + "⚠️ Old request context could not be cleared (" + e.getMessage() + ")"));
    }

    // ===== coverage (dashboard) ====================================================================================

    Future<String> coverageJson() {
        SecondFactorPolicy policy = SecondFactorPolicy.get();
        Object[] approvedStatuses = SecondFactorAccess.approvedStatuses();
        return Future.all(executeRawQuery(COVERAGE_SQL, approvedStatuses),
                executeRawQuery(SUPER_ADMINS_WITHOUT_SQL, approvedStatuses))
            .map(results -> {
                QueryResult coverage = results.resultAt(0);
                QueryResult without = results.resultAt(1);
                AstObject reply = policyJson(policy);
                AstObject accounts = AST.createObject();
                boolean known = coverage.getRowCount() == 1;
                accounts.set("approved", known ? longValue(coverage.getValue(0, 0)) : 0);
                accounts.set("pending", known ? longValue(coverage.getValue(0, 1)) : 0);
                accounts.set("none", known ? longValue(coverage.getValue(0, 2)) : 0);
                accounts.set("total", known ? longValue(coverage.getValue(0, 3)) : 0);
                reply.setObject("accounts", accounts);
                AstArray admins = AST.createArray();
                for (int row = 0; row < without.getRowCount(); row++) {
                    AstObject admin = AST.createObject();
                    setIfPresent(admin, "personName", blankToNull(string(without, row, 0)));
                    setIfPresent(admin, "username", string(without, row, 1));
                    admins.push(admin);
                }
                reply.setArray("superAdminsWithout", admins);
                return Json.formatObject(reply);
            });
    }

    // ===== the caller's own status =================================================================================

    /**
     * The caller's standing. {@code setupOnly} and {@code recoveryOnly} describe THIS session: a session whose token
     * was not opened for the back office ($aud) on a back-office client under a policy that asks for factors is one
     * of the two narrowed sessions — setup-only when the account has no approved factor under setup-first, recovery-only
     * otherwise (an emailed sign-in of an account that must present a factor). MUST be called on the caller's thread.
     */
    Future<String> statusJson(Object accountId) {
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        boolean backofficeClient = ThreadLocalStateHolder.isBackoffice();
        boolean openedForBackoffice = Boolean.TRUE.equals(StateAccessor.getVerifiedBackoffice(state));
        String sessionFamilyId = StateAccessor.getSessionFamilyId(state);
        SecondFactorPolicy policy = SecondFactorPolicy.get();
        return Future.all(SecondFactorAccess.approvedFactorCount(accountId), executeRawQuery(OWN_UNAPPROVED_SQL, accountId),
                SecondFactorAccess.openEnrolmentWindowUntil(accountId), FactorRequest.sessionMethods(sessionFamilyId))
            .map(results -> {
                Integer approvedCount = results.resultAt(0);
                int approved = approvedCount == null ? 0 : approvedCount;
                QueryResult unapproved = results.resultAt(1);
                Instant windowUntil = results.resultAt(2);
                String methods = results.resultAt(3);
                boolean narrowed = backofficeClient && !openedForBackoffice && policy.asksForFactor(true);
                // Which narrowed session this is comes from how it was OPENED, not from the factor count: an emailed
                // link or code is a recovery (choose a password, then sign in), a password is setup — so somebody who
                // forgot their password is always offered the password form first, and a count that changed under a
                // live session cannot flip one screen into the other.
                boolean emailed = FactorRequest.isEmailedSignIn(methods);
                boolean setupOnly = narrowed && !emailed && policy.isSetupFirst() && approved == 0;
                AstObject reply = policyJson(policy);
                reply.set("approvalRequired", policy.isFactorApprovalRequired());
                reply.set("setupOnly", setupOnly);
                reply.set("recoveryOnly", narrowed && emailed);
                reply.set("approvedFactors", approved);
                AstArray pending = AST.createArray();
                AstArray rejected = AST.createArray();
                for (int row = 0; row < unapproved.getRowCount(); row++) {
                    AstObject factor = AST.createObject();
                    factor.set("kind", string(unapproved, row, 0));
                    factor.set("id", longValue(unapproved.getValue(row, 1)));
                    setIfPresent(factor, "label", string(unapproved, row, 2));
                    setIfPresent(factor, "createdAt", string(unapproved, row, 3));
                    ("REJECTED".equals(string(unapproved, row, 4)) ? rejected : pending).push(factor);
                }
                reply.setArray("pending", pending);
                reply.setArray("rejected", rejected);
                if (windowUntil != null)
                    reply.set("windowUntil", windowUntil.toString());
                return Json.formatObject(reply);
            });
    }

    private static AstObject policyJson(SecondFactorPolicy policy) {
        AstObject reply = AST.createObject();
        reply.set("mode", policy.modeName());
        LocalDate requiredFrom = policy.isSetupFirst() ? policy.getRequiredFrom() : null;
        if (requiredFrom != null) // a DAY, sent as its UTC midnight so a client in any zone reads the same date back (in UTC)
            reply.set("requiredFrom", requiredFrom.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        reply.set("requiredNow", policy.isRequiredNow());
        return reply;
    }

    // ===== the enrolment window ====================================================================================

    /** Reopens setup for 48 hours for another account, after an out-of-band check recorded in the note. */
    Future<String> openWindow(ModalityUserPrincipal approver, Object approverAccountId, Object targetArgument, String note) {
        Object target = TotpCredentialStore.normaliseId(targetArgument);
        String sanitizedNote = Strings.isEmpty(note) ? null : note.trim().substring(0, Math.min(256, note.trim().length()));
        if (target == null || sanitizedNote == null || sanitizedNote.isEmpty())
            return failure();
        if (Objects.equals(target, TotpCredentialStore.normaliseId(approverAccountId))) {
            Console.log(LOG_PREFIX + "Enrolment window refused: person " + approver.getUserPersonId() + " targeted their own account");
            return failure();
        }
        return executeRawSubmit(INSERT_WINDOW_SQL, target, TotpCredentialStore.normaliseId(approver.getUserPersonId()), sanitizedNote)
            .compose(ignored -> SecondFactorAccess.openEnrolmentWindowUntil(target))
            .map(until -> {
                Console.log(LOG_PREFIX + "Enrolment window opened on account " + target + " by person " + approver.getUserPersonId());
                AstObject reply = AST.createObject();
                // Always present: the window was just written, so a failed read-back still means "48 hours from now"
                reply.set("windowUntil", (until != null ? until
                    : Instant.now().plus(Duration.ofHours(SecondFactorAccess.ENROLMENT_WINDOW_HOURS))).toString());
                return Json.formatObject(reply);
            });
    }

    // ===== the boot-time sweep (setup first) =========================================================================

    // Live back-office-audience sessions that proved no second factor: neither pk nor totp in their methods, and not a
    // view pass (sv/bov follow the agent's own session). Under `required` no such session is legitimate any more — a
    // password-only login now gets a front-office-audience setup session, an emailed one a recovery-only session — so
    // those opened before the switch are ended. Idempotent: rerun at every boot it finds nothing new. A session with
    // NULL methods predates the claims (M0) and is ended too: "unknown" is not "proved".
    private static final String UNPROVEN_BACKOFFICE_SESSIONS_SQL =
        "SELECT id FROM auth_session WHERE revoked IS NULL AND absolute_expiry > now()" +
        "   AND (audience = 'bo' OR (audience IS NULL AND tier = 'bo'))" +
        "   AND (methods IS NULL OR methods !~ '(^|,)(pk|totp|sv|bov)(,|$)')";
    private static final String REVOKE_FAMILY_SQL =
        "UPDATE auth_session SET revoked = now(), revoked_reason = 'second-factor-required' WHERE id = $1 AND revoked IS NULL";

    void sweepUnprovenBackofficeSessions() {
        executeRawQuery(UNPROVEN_BACKOFFICE_SESSIONS_SQL).compose(sessions -> {
            List<SessionFamilyStore.Revocation> families = new ArrayList<>();
            Future<Void> chain = Future.succeededFuture();
            long now = System.currentTimeMillis();
            for (int row = 0; row < sessions.getRowCount(); row++) {
                String familyId = string(sessions, row, 0);
                if (familyId == null)
                    continue;
                families.add(new SessionFamilyStore.Revocation(familyId, now));
                chain = chain.compose(ignored -> executeRawSubmit(REVOKE_FAMILY_SQL, familyId).mapEmpty());
            }
            return chain.map(ignored -> {
                RevokedFamilies.noteAll(families);
                Console.log(LOG_PREFIX + "Setup first: ended " + families.size()
                            + " back-office session(s) opened without a second factor");
                return null;
            });
        }).onFailure(e -> Console.log(LOG_PREFIX + "🛑 Setup first: the boot-time session sweep failed (" + e.getMessage()
                                      + ") — back-office sessions opened without a second factor may still be live"));
        // Super administrators with no approved factor: under `required` they are locked into setup like everybody else,
        // and only another super administrator can approve them — said loudly at boot so it is not found out at 9am
        executeRawQuery(SUPER_ADMINS_WITHOUT_SQL, SecondFactorAccess.approvedStatuses()).onSuccess(without -> {
            if (without.getRowCount() > 0)
                Console.log(LOG_PREFIX + "⚠️ Setup first is in force and " + without.getRowCount()
                            + " super administrator(s) hold no approved second factor — they are in setup like everyone"
                            + " else, and only another super administrator can approve them");
        });
    }

    // ===== plumbing ================================================================================================

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

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

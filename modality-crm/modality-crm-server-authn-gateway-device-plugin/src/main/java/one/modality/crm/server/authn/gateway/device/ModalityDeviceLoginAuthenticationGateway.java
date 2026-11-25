package one.modality.crm.server.authn.gateway.device;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.json.Json;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.scheduler.Scheduler;
import dev.webfx.stack.authn.UserClaims;
import dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.push.server.PushServerService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.session.token.AuthenticatedState;
import dev.webfx.stack.session.token.IdentityToken;
import dev.webfx.stack.session.token.PrincipalToken;
import dev.webfx.stack.session.token.SessionAudience;
import dev.webfx.stack.session.token.SessionMethod;
import one.modality.crm.server.authn.gateway.shared.CredentialChangeProof;
import one.modality.crm.server.authn.gateway.shared.FactorRequest;
import one.modality.crm.server.authn.gateway.shared.PendingDeviceLoginStore;
import one.modality.crm.server.authn.gateway.shared.SecondFactorAccess;
import one.modality.crm.shared.services.authn.ApproveDeviceLoginCredentials;
import one.modality.crm.shared.services.authn.DescribeDeviceLoginCredentials;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.crm.shared.services.authn.RequestDeviceLoginCredentials;

/**
 * Signs a device in by having a person approve it in a browser — the server half of
 * {@code docs/security/cli-device-login-design.md}, reviewed by David on 2026-11-25.
 *
 * <h3>The shape, in one paragraph</h3>
 *
 * <p>A client with no session asks ({@link RequestDeviceLoginCredentials}) and is given a short code to
 * print. The operator opens the back office, signs in as they always do, types that code and approves
 * ({@link ApproveDeviceLoginCredentials}). The server mints a <b>separate</b> session and pushes it to the
 * waiting client. Nothing is copied: the browser's own session never leaves the browser.
 *
 * <h3>Everything is checked at APPROVAL, and that is deliberate</h3>
 *
 * <p>The request has no identity in it — so there is nothing to check, nothing to refuse, and nothing an
 * attacker can learn by asking. It follows that this flow cannot be used to find out which accounts are
 * enabled for it. Every gate therefore lives in {@link #approve}, in an order chosen so that a failing
 * check never costs a bystander their pending request: the approver is judged first, from their own
 * session, and only then is a code consumed.
 *
 * <h3>What is minted</h3>
 *
 * <p>A principal marked {@link ModalityUserPrincipal#isDeviceLogin()}, which makes it <b>restricted</b> —
 * read-only at the one gate every write passes through, refused by {@code endOtherSessions}, and given the
 * thirty-minute tier, idle and absolute. Its methods are {@link SessionMethod#DEVICE_APPROVAL} alone and
 * never the approver's, so {@code FactorRequest.includesSecondFactor} answers false for it and a
 * command-line session can neither approve a factor nor approve another device login.
 *
 * <p>The audience comes from the approving session. <b>This is the rule that makes the flow a login rather
 * than a bypass</b>: an account that must present a factor, approving from the recovery-only session its
 * mailbox buys, mints a front-office device session — exactly what that account can already do, and nothing
 * more.
 *
 * <h3>No URL is returned, on purpose</h3>
 *
 * <p>The reply carries the code and how long it is good for, and no link. The client knows which server it
 * connected to and composes its own; a URL from the server would be a value the client could not check
 * anyway, and Modality's other link flow takes the origin FROM the client for the same reason.
 *
 * @author Claude Code
 */
public final class ModalityDeviceLoginAuthenticationGateway implements ServerAuthenticationGateway {

    private static final String LOG_PREFIX = "🔑 Device login: ";

    /** Outcome codes, which must match V0181's {@code device_login_attempt_outcome_check}. */
    static final String APPROVED = "APPROVED";
    static final String REFUSED_NOT_ENABLED = "REFUSED_NOT_ENABLED";
    static final String REFUSED_APPROVER = "REFUSED_APPROVER";
    static final String REFUSED_CODE = "REFUSED_CODE";
    static final String REFUSED_EXPIRED = "REFUSED_EXPIRED";

    /**
     * One message for every refusal a caller sees, whatever actually happened.
     *
     * <p>The reasons are recorded and not reported: "that code expired" and "there is no such code" told
     * apart would confirm to a guesser that a code was once real, and "this account is not enabled" would
     * turn the page into a way to test accounts. The operator who typed their own code correctly is not
     * helped by the distinction; everybody else is.
     */
    /**
     * The key a client maps to its own translated sentence, in the convention the rest of this system uses
     * ({@code hasServerErrorKey} matches on the brackets). The English after it is for a log and for a
     * caller with no translation, never for a page: the back office shows its own wording in the
     * operator's language, and a server that answered in English only would have no way to.
     */
    static final String REFUSAL =
        "[DeviceLoginRefused] That code cannot be approved. Check the code shown by the device, and try again.";

    /**
     * NOT a refusal, and deliberately distinguishable from one: the session was minted and recorded, and
     * only its delivery failed. Telling the approver "that code cannot be approved" would be false, and
     * would leave a live session nobody had been told about.
     */
    static final String UNDELIVERED =
        "[DeviceLoginUndelivered] The session was created but could not be delivered to the device. End it"
        + " from the sign-in security page, and have the device ask for a new code.";

    // The account's flag, and how many live people share it. Both in one round trip because the second is
    // what makes the first meaningful: the flag is per ACCOUNT and a device session is minted as ONE
    // person, so a shared account cannot say who it means. V0181's trigger refuses ENABLING on a shared
    // account; this catches a person attached afterwards, which the trigger cannot see.
    private static final String ACCOUNT_SQL =
        "select a.device_login," +
        " (select count(*) from person p where p.frontend_account_id = a.id and p.removed is not true)" +
        " from frontend_account a where a.id = $1 and a.disabled is not true";

    // Two statements rather than one rewritten at runtime, because the rewrite was a defect: it dropped
    // $1 from the SQL and went on binding a six-element array with a leading null, so PostgreSQL saw a
    // parameter nothing referenced and failed the INSERT with 42P18 - and record()'s own "never fail the
    // caller" swallowed it. Every refusal row was lost, silently, which is the one case the audit is for.
    // Separate constants cannot drift from their parameter arrays the way a string rewrite can.
    static final String INSERT_COLUMNS =
        "insert into device_login_attempt (requested_at, decided_at, frontend_account_id, outcome, audience,"
        + " session_family_id, approver_sign_in_method) values ";
    /** With a known request time, as an explicit interval back from now - no arithmetic, no inferred casts. */
    static final String INSERT_WITH_AGE_SQL =
        INSERT_COLUMNS + "(now() - cast($1 as interval), now(), $2, $3, $4, $5, $6)";
    /** Without one: a refusal that never reached a pending entry has no request to date. */
    static final String INSERT_NO_AGE_SQL =
        INSERT_COLUMNS + "(now(), now(), $1, $2, $3, $4, $5)";

    /** How long a decision is kept. Tighter than the Activity tab's 90-day filter, on purpose: see V0181. */
    private static final int RETENTION_DAYS = 30;
    private static final long PURGE_INTERVAL_MILLIS = 60 * 60 * 1000L;
    private static final String PURGE_SQL =
        "delete from device_login_attempt where requested_at < now() - interval '" + RETENTION_DAYS + " days'";

    /**
     * Starts the retention purge.
     *
     * <p>V0181 and the table's own COMMENT both promise 30 days, and a migration cannot keep that promise —
     * so until this existed the promise was simply untrue and the table grew without bound. The pattern is
     * {@code ModalityAuthSessionStoreInitializer.purge}: a scheduled loop, best-effort, silent when it
     * removes nothing, because this deletes rows nothing reads any more.
     *
     * <p><b>It cannot be the only thing standing between the table and a retention breach.</b> It runs only
     * where this gateway is deployed, exactly as V0155's context clearing runs only where the factor
     * gateways are. That coupling is recorded in V0181's header rather than left for somebody to discover
     * from a table that is older than it should be.
     */
    @Override
    public void boot() {
        Scheduler.schedulePeriodic(PURGE_INTERVAL_MILLIS, this::purgeOldAttempts);
    }

    private void purgeOldAttempts() {
        executeRawSubmit(PURGE_SQL)
            .onSuccess(result -> {
                // Silence is the normal case, and the only honest report of it.
                if (result != null && result.getRowCount() > 0)
                    Console.log("\ud83e\uddf9 " + LOG_PREFIX + "removed " + result.getRowCount()
                                + " device-login decision(s) older than " + RETENTION_DAYS + " days");
            })
            // Best effort by design: retrying harder would load the same pool that just refused.
            .onFailure(e -> Console.log("\u26a0\ufe0f " + LOG_PREFIX + "could not purge old decisions: " + e));
    }

    @Override
    public boolean acceptsUserCredentials(Object userCredentials) {
        return userCredentials instanceof RequestDeviceLoginCredentials
               || userCredentials instanceof DescribeDeviceLoginCredentials
               || userCredentials instanceof ApproveDeviceLoginCredentials;
    }

    @Override
    public Future<?> authenticate(Object userCredentials) {
        if (userCredentials instanceof RequestDeviceLoginCredentials request)
            return request(request);
        if (userCredentials instanceof DescribeDeviceLoginCredentials describe)
            return describe(describe);
        if (userCredentials instanceof ApproveDeviceLoginCredentials approve)
            return approve(approve);
        return Future.failedFuture(getClass().getSimpleName() + ".authenticate() requires device-login credentials");
    }

    // ===== 1. the request =========================================================================

    /**
     * Registers a waiting device and answers with the code to print.
     *
     * <p>Unauthenticated, and it must stay that way: the client has no session to offer. What bounds it is
     * that the entry holds nothing, names nobody, and the store refuses rather than evicts when full.
     *
     * <p><b>Ownership is checked HERE as well as at approval, and the reason is kindness rather than
     * security.</b> The approval check is the one that matters; this one refuses a client that cannot be
     * delivered to at the only moment when refusing is free — before a person has read a code and decided.
     */
    private Future<?> request(RequestDeviceLoginCredentials credentials) {
        // Captured synchronously: ThreadLocalStateHolder is restored the moment the synchronous part of the
        // call returns, so anything read inside a compose() below would be absent.
        String runId = ThreadLocalStateHolder.getRunId();
        String serverSessionId = ThreadLocalStateHolder.getServerSessionId();

        if (runId == null || serverSessionId == null)
            return Future.failedFuture("A device login needs a connection this server can push to");
        if (!PushServerService.isClientOwnedBySession(runId, serverSessionId))
            // Fails closed, including for a client that has connected but not yet completed a live tick:
            // asking again in a moment is the right answer, and minting for an unowned run id is not.
            return Future.failedFuture("This connection is not yet registered for server pushes; try again in a moment");

        long now = System.currentTimeMillis();
        String code = PendingDeviceLoginStore.getInstance()
            .register(runId, serverSessionId, credentials.backoffice(), now);
        if (code == null)
            return Future.failedFuture("Too many device logins are waiting; try again shortly");

        // The code is NOT logged. This line says a device is waiting, not which one or for whom.
        Console.log(LOG_PREFIX + "a device is waiting for approval (" + credentials.backoffice() + " requested)");

        AstObject reply = AST.createObject();
        reply.set("code", PendingDeviceLoginStore.format(code));
        reply.set("expiresInSeconds", PendingDeviceLoginStore.TTL_MILLIS / 1000);
        return Future.succeededFuture(Json.formatObject(reply));
    }

    // ===== 2. the description shown before approving ==============================================

    /** What the device is asking for, for the page to show. Consumes nothing, and never names anybody. */
    private Future<?> describe(DescribeDeviceLoginCredentials credentials) {
        // The same standing approve requires. Not a hole if it were missing - the pending entry names
        // nobody, so there is nothing about an account to leak - but a session that may not approve has no
        // business reading what it would be approving, and the two gates agreeing is one less thing to
        // reason about later.
        if (!(ThreadLocalStateHolder.getUserId() instanceof ModalityUserPrincipal principal)
            || principal.isSupportView() || principal.isDeviceLogin())
            return Future.failedFuture(REFUSAL);
        PendingDeviceLoginStore.Pending entry =
            PendingDeviceLoginStore.getInstance().peek(credentials.code(), System.currentTimeMillis());
        if (entry == null)
            return Future.failedFuture(REFUSAL);
        AstObject reply = AST.createObject();
        // Only what the device ASKED for and how long is left. Not the run id, which is the address the
        // session would be pushed to, and not the owning session: neither is the approver's business and
        // both are exactly what an attacker would want out of this call.
        reply.set("backofficeRequested", entry.backofficeRequested());
        reply.set("expiresInSeconds", Math.max(0, (entry.expiresAtMillis() - System.currentTimeMillis()) / 1000));
        return Future.succeededFuture(Json.formatObject(reply));
    }

    // ===== 3. the approval ========================================================================

    /**
     * Mints a device session for the approver's own account, if every gate holds.
     *
     * <p>Order matters twice over. The approver is judged first, from their own session alone, so a refused
     * approval never consumes somebody else's pending entry. The code is consumed next, atomically, so one
     * approval can mint exactly once. Only then is the account consulted — which is also why this flow
     * cannot enumerate accounts: by the time an account is named, the caller has already proved they are
     * that account.
     */
    private Future<Object> approve(ApproveDeviceLoginCredentials credentials) {
        // Everything the thread-local holds, read before the first asynchronous hop.
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        String approverRunId = ThreadLocalStateHolder.getRunId();
        Object principal = ThreadLocalStateHolder.getUserId();
        String sessionFamilyId = StateAccessor.getSessionFamilyId(state);
        boolean approverBackoffice = SecondFactorAccess.callerOpenedForBackoffice();

        if (!(principal instanceof ModalityUserPrincipal approver))
            return Future.failedFuture(REFUSAL);

        // (a) The approving session must be the person's own, not a borrowed one. A back-office view pass
        // produces a `bo` session AS THE TARGET PERSON, so without this a super administrator viewing as
        // somebody could mint that person a command-line session — the delegated approval the design note
        // says must reopen the whole question, arriving through a side door instead.
        if (approver.isSupportView() || approver.isDeviceLogin())
            return refuse(REFUSED_APPROVER, approver, null, null, null);

        Object accountId = Numbers.toLong(approver.getUserAccountId());

        return FactorRequest.sessionMethods(sessionFamilyId).compose((String methods) -> {
            // (b) The same rule read off the token, because a pass's principal is not the only way in.
            //
            // NULL IS REFUSED. sessionMethods answers null for a database error and for a token minted
            // before the claim existed, and this is the gate that catches a back-office view pass — a
            // session AS THE TARGET PERSON. Its sibling, requiresFreshFactorProof, already fails closed on
            // an unanswerable question; this one did not, so a transient database error turned the one
            // check standing between "viewing as somebody" and "minting them a command-line session" into
            // a no-op. The cost of refusing legacy tokens instead is nil: a back-office session is capped
            // at a day, so none survives.
            if (mayNotApprove(methods))
                return refuse(REFUSED_APPROVER, approver, null, null, methods);

            // (c) The account: the flag, and that it is still exactly one person's.
            //
            // BEFORE the code is consumed, with (d) below. Consuming first — as this did — meant a failure
            // here burned the operator's code, so the honest retry (fix the account, approve again) needed
            // a fresh code from a CLI that had not been told to ask for one. Nothing here consumes
            // anything, so running these first costs only their two queries.
            return executeRawQuery(ACCOUNT_SQL, new Object[]{accountId}).compose((QueryResult accountResult) -> {
                // Exactly one live person, not "no more than one": a count of zero would otherwise pass,
                // and an account whose only person is removed is not an account anybody should be minted for.
                if (accountResult.getRowCount() != 1
                    || !Boolean.TRUE.equals(accountResult.getValue(0, 0))
                    || longValue(accountResult.getValue(0, 1)) != 1)
                    return refuse(REFUSED_NOT_ENABLED, approver, null, null, methods);

                // (d) Where the account holds a factor, it must have been proved IN THIS TAB within five
                // minutes — so an already-open back-office tab is not a one-click second session.
                return requiresFreshFactorProof(accountId, approverRunId, approverBackoffice)
                    .compose((Boolean refused) -> {
                        if (refused)
                            return refuse(REFUSED_APPROVER, approver, null, null, methods);

                        // (e) Only now consume the code. Atomic, so exactly one approval may ever mint.
                        long now = System.currentTimeMillis();
                        PendingDeviceLoginStore.Consumed consumed =
                            PendingDeviceLoginStore.getInstance().consume(credentials.code(), now);
                        if (consumed.outcome() == PendingDeviceLoginStore.Outcome.EXPIRED)
                            return refuse(REFUSED_EXPIRED, approver, null, null, methods);
                        if (consumed.outcome() != PendingDeviceLoginStore.Outcome.READY)
                            return refuse(REFUSED_CODE, approver, null, null, methods);
                        PendingDeviceLoginStore.Pending entry = consumed.entry();

                        // (f) The device must still be the client that asked. The run id is chosen by the
                        // client and authenticated by nothing; over five minutes that is not good enough.
                        if (!PushServerService.isClientOwnedBySession(entry.runId(), entry.ownerSessionId()))
                            return refuse(REFUSED_CODE, approver, entry, null, methods);

                        return mintAndPush(approver, accountId, entry, approverBackoffice, methods);
                    });
            // A database failure here is not a refusal and must not reach the caller as a raw driver
            // message. recover(), not otherwise(): this answers with a FAILED future, and otherwise() would
            // have wrapped one inside a succeeded one, so the caller would have been handed a Future as a
            // result instead of an error.
            }).recover(e -> {
                Console.log("\u26a0\ufe0f " + LOG_PREFIX + "could not read the approving account: " + e);
                return Future.failedFuture(REFUSAL);
            });
        });
    }

    /**
     * Whether a session with these methods is barred from approving — the gate's own decision, named so it
     * can be tested rather than inferred from a lambda.
     *
     * <p><b>Null is barred.</b> {@code sessionMethods} answers null for a database error and for a token
     * minted before the claim existed, and this is the gate that catches a back-office view pass — a
     * session AS THE TARGET PERSON. Left as "null carries no pass", a transient database error turned the
     * one check between "viewing as somebody" and "minting them a command-line session" into a no-op,
     * while its sibling gate failed closed on the same kind of error.
     */
    static boolean mayNotApprove(String methods) {
        return methods == null || carriesViewPass(methods);
    }

    /** A session carrying a view pass is a borrowed one, whatever its principal looks like. */
    static boolean carriesViewPass(String methods) {
        if (methods == null)
            return false;
        return methods.matches("(^|.*,)(" + SessionMethod.SUPPORT_VIEW_PASS.code()
                               + "|" + SessionMethod.BACKOFFICE_VIEW_PASS.code()
                               + "|" + SessionMethod.DEVICE_APPROVAL.code() + ")(,.*|$)");
    }

    /**
     * Whether this approval must be refused for want of a recent factor proof. False when none is held.
     *
     * <p><b>The proof counts only on a session opened FOR the back office</b>, which is the pairing
     * {@code FactorRequest.capture} makes and names its reason for: the run id a proof is filed under is
     * chosen by the client, so a proof noted for a tab could otherwise be borrowed by a front-office,
     * setup-only or recovery-only session of the same account — all {@code fo} — that learned it. Dropping
     * the pairing left this one control deep rather than two, saved only by the audience rule minting such
     * a session {@code fo} anyway. David's review asked for the pairing in terms; here it is.
     */
    private Future<Boolean> requiresFreshFactorProof(Object accountId, String approverRunId,
                                                     boolean openedForBackoffice) {
        Object[] statuses = SecondFactorAccess.approvedStatuses();
        String sql = "select " + SecondFactorAccess.approvedFactorCountSql("$1", "$2, $3");
        return executeRawQuery(sql, new Object[]{accountId, statuses[0], statuses[1]})
            .map(result -> {
                boolean holdsFactor = result.getRowCount() == 1 && longValue(result.getValue(0, 0)) > 0;
                boolean proved = openedForBackoffice
                                 && CredentialChangeProof.strongFactorProvedRecently(approverRunId, accountId);
                return holdsFactor && !proved;
            })
            // An unanswerable question refuses: this gate exists precisely for accounts that hold a factor.
            .otherwise(e -> true);
    }

    /**
     * Mints the device session, records it, then delivers it.
     *
     * <p><b>The row is written BEFORE the push and independently of it.</b> A session that has been minted
     * exists — it is in the family store and its token is signed — whether or not delivery worked, so
     * recording it only on a successful push meant a live session could exist with no trace of how it came
     * to be, which is precisely what the audit is for.
     *
     * <p>A delivery failure therefore gets its own message, and that is deliberate rather than a breach of
     * the one-message rule: the uniform refusal exists so a caller cannot tell REFUSALS apart, and this is
     * not a refusal. The approver needs to know a session was created, because it was.
     */
    private Future<Object> mintAndPush(ModalityUserPrincipal approver, Object accountId,
                                       PendingDeviceLoginStore.Pending entry,
                                       boolean approverBackoffice, String approverMethods) {
        SessionAudience audience = SessionAudience.ofBackofficeFlag(approverBackoffice);
        ModalityUserPrincipal devicePrincipal = ModalityUserPrincipal.forDeviceLogin(
            approver.getUserPersonId(), approver.getUserAccountId());
        // DEVICE_APPROVAL alone. Never the approver's methods: inheriting pk or totp would make this
        // session answer USED_FACTOR, and a command-line token could then reset other people's factors.
        return AuthenticatedState.createFor(devicePrincipal, approverBackoffice, audience,
                                            SessionMethod.of(SessionMethod.DEVICE_APPROVAL))
            .compose((Object deviceState) -> record(APPROVED, accountId, entry, audience.code(),
                                                    familyIdOf(deviceState), approverMethods)
                .compose(ignored -> PushServerService.pushState(deviceState, entry.runId()))
                .map(ignored -> (Object) null)
                .recover(e -> {
                    Console.log("\u26a0\ufe0f " + LOG_PREFIX + "minted a session but could not deliver it: " + e);
                    return Future.failedFuture(UNDELIVERED);
                }));
    }

    /**
     * The family of a session just minted, read back off its own token.
     *
     * <p>It cannot be read off the state: {@code AuthenticatedState.createFor} sets the principal and the
     * token and nothing else, and {@code StateAccessor.setSessionFamilyId} is called in exactly one place
     * in the stack — the inbound syncer, from a token that has just verified. Asking the state for it, as
     * this did, answered null every time, so {@code device_login_attempt.session_family_id} was NULL on
     * every row while both V0181 and the erasure manifest said it was how a super administrator ties the
     * row to a live session and revokes it. Nothing failed; the column was simply dead.
     *
     * <p>Verifying what we have just signed is the same discipline the CLI applies to its own audience:
     * asking for something is not getting it. Null on an unreadable token rather than throwing — a missing
     * family costs a less useful audit row, and must not cost an approval that otherwise succeeded.
     */
    private static String familyIdOf(Object deviceState) {
        try {
            String token = StateAccessor.getUserToken(deviceState);
            if (token == null)
                return null;
            IdentityToken identity = PrincipalToken.verify(token, System.currentTimeMillis());
            return identity == null ? null : identity.familyId();
        } catch (RuntimeException e) {
            Console.log("\u26a0\ufe0f " + LOG_PREFIX + "could not read the minted session's family: " + e);
            return null;
        }
    }

    // ===== the audit row ==========================================================================

    /** Records the refusal, then fails with the one message every refusal shares. */
    private Future<Object> refuse(String outcome, ModalityUserPrincipal approver,
                                  PendingDeviceLoginStore.Pending entry, String audience, String approverMethods) {
        Console.log(LOG_PREFIX + "refused (" + outcome + ")");
        return record(outcome, approver == null ? null : approver.getUserAccountId(), entry, audience, null,
                      approverMethods)
            .compose(ignored -> Future.failedFuture(REFUSAL));
    }

    /**
     * Appends the decision. Never fails the caller: an approval that worked must not be reported as broken
     * because its audit row could not be written, and a refusal is already a refusal. A row that did not
     * land is logged loudly instead, because this table is the only durable trace of the flow.
     *
     * <p>{@code request_ip}, {@code request_location} and {@code request_user_agent} are left NULL — see
     * V0181's header: whether staff location may be retained is a data-protection question still open.
     */
    private Future<Void> record(String outcome, Object accountId, PendingDeviceLoginStore.Pending entry,
                                String audience, String sessionFamilyId, String approverMethods) {
        String sql;
        Object[] parameters;
        if (entry == null) {
            sql = INSERT_NO_AGE_SQL;
            parameters = new Object[]{accountId, outcome, audience, sessionFamilyId, approverMethods};
        } else {
            long ageMillis = Math.max(0, System.currentTimeMillis()
                                         - (entry.expiresAtMillis() - PendingDeviceLoginStore.TTL_MILLIS));
            sql = INSERT_WITH_AGE_SQL;
            parameters = new Object[]{(ageMillis / 1000) + " seconds",
                                      accountId, outcome, audience, sessionFamilyId, approverMethods};
        }
        return executeRawSubmit(sql, parameters)
            .<Void>mapEmpty()
            .otherwise(e -> {
                Console.log("\u26a0\ufe0f " + LOG_PREFIX + "could not record a " + outcome + " decision: " + e);
                return null;
            });
    }

    // ===== raw SQL, as WebAuthnCredentialStore does it ============================================

    private Future<QueryResult> executeRawQuery(String sql, Object[] parameters) {
        // language = null ⇒ raw SQL (no DQL compilation); sendMetadata = false — read by position
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

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    // ===== the rest of the SPI, which stays with the password gateway =============================

    /**
     * False, and it is load-bearing — <b>this gateway is FIRST in the shaded services list</b>.
     *
     * <p>{@code ServerAuthenticationPortalProvider} dispatches first-gateway-wins. {@code authenticate} picks
     * by credential type, so order cannot matter there; {@code verifyAuthenticated}, {@code getUserClaims}
     * and {@code logout} all pick on THIS boolean. Returning true here would hand all three to this class
     * for every caller in the system, purely because of where the class name sorts.
     *
     * <p>Nothing is lost by it: a device session's principal is an ordinary {@link ModalityUserPrincipal},
     * so the password gateway recognises it and does the real work for all three.
     */
    @Override
    public boolean acceptsUserId() {
        return false;
    }

    @Override
    public Future<?> verifyAuthenticated() {
        return Future.failedFuture("%s.verifyAuthenticated() is not supported".formatted(getClass().getSimpleName()));
    }

    @Override
    public Future<UserClaims> getUserClaims() {
        return Future.failedFuture("%s.getUserClaims() is not supported".formatted(getClass().getSimpleName()));
    }

    @Override
    public boolean acceptsUpdateCredentialsArgument(Object updateCredentialsArgument) {
        return false;
    }

    @Override
    public Future<?> updateCredentials(Object updateCredentialsArgument) {
        return Future.failedFuture("%s.updateCredentials() is not supported".formatted(getClass().getSimpleName()));
    }

    /**
     * Unreachable through the portal, because {@link #acceptsUserId()} is false — and a bare success is the
     * right thing to leave here anyway.
     *
     * <p>If that boolean ever flipped, this would become the logout for every caller, this gateway being
     * first in the services list. It would still not be a disaster, which is worth knowing rather than
     * guessing: {@code logoutWith} revokes the session family BEFORE it reaches any gateway, so the
     * enforcement has already happened and the gateway half is only the courtesy of telling the device.
     * A failure here, by contrast, would break logouts that had in fact succeeded — the exact bug that
     * method's comments say it exists to prevent.
     */
    @Override
    public Future<Void> logout() {
        return Future.succeededFuture();
    }
}

package one.modality.crm.server.person;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may say that one person, in somebody else's account, is the human behind another.
 *
 * <p>{@code person.accountPerson} is the most consequential column a member can write. A link
 * {@code P.accountPerson = Q} makes P's bookings, orders and recordings visible to Q's account, and
 * takes the recordings out of P's own — the media screens read
 * {@code person.(frontendAccount=$1 and accountPerson=null)} for the account's own people and
 * {@code person.accountPerson.frontendAccount=$1} for its linked ones. Until this service, any client
 * could set it on any row: linking a stranger's person to yourself handed you their bookings and took
 * their recordings away from them.
 *
 * <h3>Why approval is the operation, rather than "link"</h3>
 *
 * A generic {@code link(target, to)} would have to be told, by the caller, why it is allowed — and a
 * reason a caller supplies is not a reason. The operations here are the four things that actually happen,
 * each carrying its own proof:
 *
 * <ul>
 *   <li><b>Approving an invitation</b> — the caller IS the invitee, so the proof is the row. <b>That
 *       proof is only worth what the row is worth</b>, and today an invitation is an ordinary client
 *       write: anyone can insert one naming somebody else as inviter, approve it as themselves, and pull
 *       that person's bookings into their own account. Approval therefore rests on a creation rule that
 *       does not exist yet, which is why {@code ApproveInvitationEndpoint} is written and NOT registered.
 *       It covers both directions once it is safe to switch on, because an invitation's
 *       {@code inviterPayer} says which way the link runs.</li>
 *   <li><b>Revoking</b> — the caller owns the account the link was granted from, or is the person it was
 *       granted to.</li>
 * </ul>
 *
 * <p>Claiming a person by a matching sign-in address is the third, and is deliberately NOT here yet:
 * its proof is {@code person.email} on the TARGET, which any client can still write on a non-owner, so
 * it cannot be relied on before the table closes. See the plan's <i>Sequencing</i> note.
 *
 * <h3>Conditions ride with the writes</h3>
 *
 * Every rule below is re-asserted in the WHERE of the statement that acts on it, not merely checked
 * beforehand. A check that ran in an earlier transaction lets anything committed in between through, and
 * {@code SubmitResult.getRowCount()} counts result sets rather than rows, so it cannot be used to tell
 * whether a guarded update matched. Each operation therefore reads back what it did and reports the
 * truth of that.
 *
 * @author Claude Code
 */
final class PersonLinkRules {

    private PersonLinkRules() {
    }

    /** Refusals, each named so the screen can say which in the reader's language. */
    /**
     * One answer for "there is no such invitation" AND "it is not addressed to you".
     *
     * <p>Two answers would let a caller walk the invitation ids and learn which exist and who they were
     * sent to. A member can only be told about an invitation that is theirs, which is also all a screen
     * needs to say.
     */
    static final String INVITATION_NOT_YOURS_KEY = "InvitationNotYoursError";
    static final String INVITATION_ALREADY_USED_KEY = "InvitationAlreadyUsedError";
    static final String LINK_NOT_ESTABLISHED_KEY = "LinkNotEstablishedError";
    static final String NOT_YOUR_LINK_KEY = "NotYourLinkError";

    /**
     * The invitation, with both parties resolved.
     *
     * <p>{@code invitee_id} and {@code inviter_id} are both person ids — despite one client call site
     * naming its variable after an account, the value it passes is the account holder's PERSON.
     */
    private static final String INVITATION_SQL =
        "select i.inviter_id, i.invitee_id, i.inviter_payer, i.pending, i.accepted," +
        "       inviter.frontend_account_id, invitee.frontend_account_id, lower(invitee.email)," +
        "       i.alias_first_name, i.alias_last_name, inviter.first_name, inviter.last_name" +
        " from invitation i" +
        " join person inviter on inviter.id = i.inviter_id" +
        " join person invitee on invitee.id = i.invitee_id" +
        " where i.id = $1";

    /**
     * The member of the inviter's account this approval is about, linked to the approver.
     *
     * <p>Matched on the invitee's own address, which is what says that row is this human. Re-links a row
     * withdrawn earlier by clearing the revoked date, which is what makes "ask again" work — and accepts a
     * row already linked TO THIS CALLER so that approving twice is harmless, while leaving a row linked to
     * anybody else alone.
     */
    private static final String LINK_EXISTING_MEMBER_SQL =
        "update person set account_person_id = $1, account_person_revoked_date = null" +
        " where frontend_account_id = $2 and lower(email) = $3" +
        "   and owner = false and removed = false" +
        "   and (account_person_id is null or account_person_id = $1)";

    /**
     * The same member, when the inviter's account has no row for them yet.
     *
     * <p>{@code insert … select … where not exists} rather than a read followed by an insert: the guard is
     * inside the statement, so two approvals racing cannot both insert, and a row created between a check
     * and a write cannot be missed.
     */
    private static final String CREATE_LINKED_MEMBER_SQL =
        "insert into person (first_name, last_name, frontend_account_id, account_person_id, owner)" +
        " select $4, $5, $2, $1, false" +
        " where not exists (select 1 from person where frontend_account_id = $2" +
        "                   and account_person_id = $1 and removed = false)" +
        "   and not exists (select 1 from person where frontend_account_id = $2" +
        "                   and lower(email) = $3 and owner = false and removed = false)";

    /**
     * The manager's view of the person who invited them.
     *
     * <p>The other direction: accepting a booking-manager invitation puts a row in the APPROVER's account
     * standing for the inviter, linked to them, which is what lets the manager see their bookings.
     */
    /**
     * Re-linking the manager's view of an inviter they were withdrawn from.
     *
     * <p>Without this the insert below is skipped as a duplicate (the row exists), the read-back finds it
     * withdrawn, and approving again refuses forever. The validation direction has always cleared the
     * date; the two halves have to agree or "ask again" works one way round only.
     */
    private static final String RELINK_MANAGER_VIEW_SQL =
        "update person set account_person_revoked_date = null" +
        " where frontend_account_id = $2 and account_person_id = $1 and removed = false";

    private static final String CREATE_MANAGER_VIEW_SQL =
        "insert into person (first_name, last_name, frontend_account_id, account_person_id, owner)" +
        " select $3, $4, $2, $1, false" +
        " where not exists (select 1 from person where frontend_account_id = $2" +
        "                   and account_person_id = $1 and removed = false)";

    /** Spent, and only from pending — so a replay of the same call changes nothing the second time. */
    private static final String USE_INVITATION_SQL =
        "update invitation set pending = false, accepted = true, usage_date = now()" +
        " where id = $1 and pending = true";

    /** Whether the link this approval was for is now there. */
    private static final String LINK_PRESENT_SQL =
        "select count(*) from person where frontend_account_id = $2 and account_person_id = $1" +
        "  and removed = false and account_person_revoked_date is null";

    /**
     * Withdrawing a link, without confiscating what it gave.
     *
     * <p>{@code account_person_id} is deliberately left alone: the bookings were made for that person and
     * the recordings are theirs, so every access query keeps matching. What stops is booking on the link
     * again. This mirrors what the screen did, and the reason is worth keeping in front of whoever changes
     * it — clearing the link instead would silently take away materials somebody already has.
     *
     * <p>Either party may do it: the account the link was granted FROM (its owner is the caller), or the
     * person it was granted TO. Both are expressed in the WHERE, so a caller who is neither changes
     * nothing and is told so by the read-back.
     */
    private static final String REVOKE_LINK_SQL =
        "update person set account_person_revoked_date = now()" +
        " where id = $1 and account_person_id is not null and account_person_revoked_date is null" +
        "   and (frontend_account_id = $2 or account_person_id = $3)";

    /**
     * Whether that row is now withdrawn AND was the caller's to withdraw — the read-back, since a row
     * count cannot answer it.
     *
     * <p>The caller predicate is not decoration. Asking only whether the row is withdrawn reports success
     * to somebody who is neither party whenever the link was already withdrawn by its real owner: a wrong
     * answer, and a yes/no oracle over sequential person ids for anyone who cares to walk them.
     */
    private static final String REVOKED_SQL =
        "select count(*) from person where id = $1 and account_person_revoked_date is not null" +
        "  and (frontend_account_id = $2 or account_person_id = $3)";

    /**
     * Approves an invitation addressed to the caller, establishing the link it stands for.
     *
     * @param callerPersonId  the caller's own person, from the principal — never from the argument
     * @param callerAccountId the caller's own account, likewise
     */
    static Future<Boolean> approveInvitation(Object rawInvitationId, Object callerPersonId, Object callerAccountId,
                                             Object callerUserId) {
        Object invitationId = Numbers.toLong(rawInvitationId);
        if (invitationId == null)
            return MemberSessionGuard.refused();
        return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(INVITATION_SQL)
                .setParameters(invitationId)
                .build()))
            .compose(result -> {
                if (result == null || result.getRowCount() == 0)
                    return refusal(INVITATION_NOT_YOURS_KEY);
                Object inviterId = result.getValue(0, 0);
                Object inviteeId = result.getValue(0, 1);
                boolean inviterPays = isTrue(result.getValue(0, 2));
                boolean pending = isTrue(result.getValue(0, 3));
                Object inviterAccountId = result.getValue(0, 5);
                Object inviteeEmail = result.getValue(0, 7);
                // THE PROOF: the caller is the person this was sent to. Not "knows its token", which is
                // what the email-link page relied on — a string anybody holding the mail can read, and
                // which approved the link with nobody signed in at all.
                if (!sameId(inviteeId, callerPersonId))
                    return refusal(INVITATION_NOT_YOURS_KEY);
                if (!pending)
                    return refusal(INVITATION_ALREADY_USED_KEY);
                return inviterPays
                    ? approveValidationRequest(invitationId, callerPersonId, inviterAccountId, inviteeEmail,
                        result.getValue(0, 8), result.getValue(0, 9), callerUserId)
                    : approveManagerInvitation(invitationId, inviterId, callerAccountId,
                        result.getValue(0, 10), result.getValue(0, 11), callerUserId);
            });
    }

    /**
     * The inviter asked to book for this member: the member's row in the INVITER's account is linked to
     * the approver.
     */
    private static Future<Boolean> approveValidationRequest(Object invitationId, Object callerPersonId,
                                                            Object inviterAccountId, Object inviteeEmail,
                                                            Object aliasFirstName, Object aliasLastName,
                                                            Object callerUserId) {
        if (inviterAccountId == null) // an inviter with no account has no member row to link
            return refusal(LINK_NOT_ESTABLISHED_KEY);
        // A member with no address of their own cannot be identified by one. Both guards below key on
        // lower(email), and against NULL they stop selecting anybody — the update matches nothing and the
        // insert's "no row with this address" is vacuously true, so a SECOND person would be created for
        // a human who already has a row. Refused instead, as the screen this replaces refused it.
        if (inviteeEmail == null)
            return refusal(LINK_NOT_ESTABLISHED_KEY);
        List<SubmitArgument> batch = new ArrayList<>();
        batch.add(statement(LINK_EXISTING_MEMBER_SQL, callerPersonId, inviterAccountId, inviteeEmail));
        batch.add(statement(CREATE_LINKED_MEMBER_SQL, callerPersonId, inviterAccountId, inviteeEmail,
            aliasFirstName, aliasLastName));
        return runAndConfirmLink(batch, invitationId, callerPersonId, inviterAccountId, callerUserId);
    }

    /** The inviter asked the caller to manage their bookings: the caller's account gains their row. */
    private static Future<Boolean> approveManagerInvitation(Object invitationId, Object inviterId,
                                                            Object callerAccountId, Object inviterFirstName,
                                                            Object inviterLastName, Object callerUserId) {
        List<SubmitArgument> batch = new ArrayList<>();
        batch.add(statement(RELINK_MANAGER_VIEW_SQL, inviterId, callerAccountId));
        batch.add(statement(CREATE_MANAGER_VIEW_SQL, inviterId, callerAccountId, inviterFirstName, inviterLastName));
        return runAndConfirmLink(batch, invitationId, inviterId, callerAccountId, callerUserId);
    }

    /**
     * Runs the link statements, asks whether the link is actually there, and only then spends the
     * invitation.
     *
     * <p><b>Spending it in the same transaction was wrong.</b> Every link statement is guarded, so an
     * approval can legitimately establish nothing — the row belongs to somebody else, or was withdrawn in
     * a way this cannot undo. Marked used regardless, the member's retry would be told the invitation was
     * already used and they would be stuck with no link and no way to ask again. So the invitation is
     * spent by a second write, which only runs on the answer the read-back gives.
     *
     * <p>The two are therefore not atomic, and the direction of that is deliberate: interrupted between
     * them leaves a link made and an invitation still pending, which approves to the same state a second
     * time (every statement here is idempotent). The reverse — spent with no link — is the one nobody can
     * recover from.
     */
    private static Future<Boolean> runAndConfirmLink(List<SubmitArgument> batch, Object invitationId,
                                                     Object linkedPersonId, Object accountId, Object callerUserId) {
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(batch.toArray(new SubmitArgument[0]))))
            .compose(ignored -> ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(LINK_PRESENT_SQL)
                .setParameters(linkedPersonId, accountId)
                .build())))
            .compose(after -> {
                if (countAt(after) == 0) {
                    Console.log("🔗 Invitation " + invitationId + " approved but no link resulted for person "
                                + linkedPersonId + " in account " + accountId + " — left pending, so it can be retried");
                    return refusal(LINK_NOT_ESTABLISHED_KEY);
                }
                return ServerWrite.asServerActingFor(callerUserId,
                        () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] {
                            statement(USE_INVITATION_SQL, invitationId) })))
                    .map(ignored -> true);
            });
    }

    /**
     * Withdraws a link, from either side.
     *
     * @param targetPersonId the person whose link is withdrawn
     */
    static Future<Boolean> revokeLink(Object rawTargetPersonId, Object callerPersonId, Object callerAccountId,
                                      Object callerUserId) {
        Object targetId = Numbers.toLong(rawTargetPersonId);
        if (targetId == null)
            return MemberSessionGuard.refused();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] {
                    statement(REVOKE_LINK_SQL, targetId, callerAccountId, callerPersonId) })))
            .compose(ignored -> ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(REVOKED_SQL)
                .setParameters(targetId, callerAccountId, callerPersonId)
                .build())))
            .compose(after -> countAt(after) > 0
                ? Future.succeededFuture(true)
                // Either the caller is neither party, or there was no live link to withdraw. One answer
                // for both, so a caller cannot learn who is linked to whom by asking.
                : refusal(NOT_YOUR_LINK_KEY));
    }

    /**
     * Every statement that CHANGES something, for a check to read without a database.
     *
     * <p>What matters about these is textual and therefore testable: that no write to
     * {@code account_person_id} is unguarded, that withdrawing never clears it, and that an invitation is
     * only ever spent from pending. Package-private for that reason alone.
     */
    static List<String> writeStatements() {
        return List.of(LINK_EXISTING_MEMBER_SQL, CREATE_LINKED_MEMBER_SQL, CREATE_MANAGER_VIEW_SQL,
            USE_INVITATION_SQL, REVOKE_LINK_SQL);
    }

    private static SubmitArgument statement(String sql, Object... parameters) {
        return new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql) // a constant; only the parameters below come from anywhere else
            .setParameters(parameters)
            .build();
    }

    private static Future<Boolean> refusal(String key) {
        return Future.failedFuture("[%s] This link was not changed".formatted(key));
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    /** Ids arrive as whatever the driver produced, so they are compared as numbers, not as objects. */
    static boolean sameId(Object a, Object b) {
        Long left = Numbers.toLong(a), right = Numbers.toLong(b);
        return left != null && left.equals(right);
    }

    private static long countAt(QueryResult result) {
        if (result == null || result.getRowCount() == 0)
            return 0;
        Object value = result.getValue(0, 0);
        return value instanceof Number number ? number.longValue() : 0;
    }
}

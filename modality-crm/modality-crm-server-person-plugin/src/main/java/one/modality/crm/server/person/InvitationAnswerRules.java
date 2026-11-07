package one.modality.crm.server.person;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.util.List;

/**
 * Closing an invitation without a link: declining it, cancelling it, and the inviter seeing the answer.
 *
 * <p>These were client writes — {@code update Invitation set pending=false, accepted=false} — and nothing
 * on the server asked whose invitation it was. Any signed-in member could close anybody's request by id
 * (ids are sequential). That granted no link, which is why it was left open; it became worth closing
 * when a decline started being SHOWN to the inviter ("Kiran declined on 7 Nov", V0157): a forged one
 * would then be a false statement about a real person, put in front of somebody else.
 *
 * <p>So each operation now carries its proof, as {@link PersonLinkRules}' do:
 * <ul>
 *   <li><b>Declining</b> — the caller is the invitee. It dates the answer ({@code usage_date}), which is
 *       what tells a decline from a cancellation (V0157's header has the three states).</li>
 *   <li><b>Cancelling</b> — the caller is the inviter. Undated, so it never reads as the other person's
 *       answer. It replaces both client paths, the soft cancel and the hard delete the manager direction
 *       used: a deleted row took the record of who invited whom with it.</li>
 *   <li><b>Seeing an answer</b> — the caller is the inviter, and the invitation has an answer to see.</li>
 * </ul>
 *
 * <p>Every condition is repeated in the WHERE of the write, and decline and cancel read back what they
 * did, because {@code SubmitResult.getRowCount()} counts result sets, not rows. A caller who is not the
 * right party, or names an invitation that does not exist, gets one answer for both — the ids cannot be
 * walked to learn who invited whom.
 *
 * @author Claude Code
 */
final class InvitationAnswerRules {

    private InvitationAnswerRules() {
    }

    /** One refusal for "no such invitation", "not yours" and "already answered" — see the class comment. */
    static final String INVITATION_NOT_CLOSED_KEY = "InvitationNotClosedError";

    /** Only a pending invitation addressed to the caller; dated, which is what makes it a decline. */
    private static final String DECLINE_SQL =
        "update invitation set pending = false, accepted = false, usage_date = now()" +
        " where id = $1 and invitee_id = $2 and pending = true";

    /**
     * The read-back: declined, and the caller's. A repeat of the same decline answers yes again, which is
     * harmless; an invitation the caller ACCEPTED answers no, and so does one that was cancelled.
     */
    private static final String DECLINED_SQL =
        "select count(*) from invitation where id = $1 and invitee_id = $2" +
        "  and pending = false and accepted = false and usage_date is not null";

    /** Only a pending invitation the caller sent; NOT dated, so it is never mistaken for an answer. */
    private static final String CANCEL_SQL =
        "update invitation set pending = false, accepted = false" +
        " where id = $1 and inviter_id = $2 and pending = true";

    /** The read-back: closed without a link, and the caller's. */
    private static final String CANCELLED_SQL =
        "select count(*) from invitation where id = $1 and inviter_id = $2" +
        "  and pending = false and accepted = false";

    /**
     * The inviter saw the answer. Only once (a later sighting does not move the date), and only an answer:
     * {@code usage_date} is set by approving and declining, never by cancelling.
     */
    private static final String SEE_ANSWER_SQL =
        "update invitation set answer_seen_date = now()" +
        " where id = $1 and inviter_id = $2 and pending = false and usage_date is not null" +
        "   and answer_seen_date is null";

    /** Declines an invitation addressed to the caller. */
    static Future<Boolean> decline(Object rawInvitationId, Object callerPersonId, Object callerUserId) {
        return writeAndConfirm(rawInvitationId, callerPersonId, callerUserId, DECLINE_SQL, DECLINED_SQL);
    }

    /** Cancels an invitation the caller sent, in either direction. */
    static Future<Boolean> cancel(Object rawInvitationId, Object callerPersonId, Object callerUserId) {
        return writeAndConfirm(rawInvitationId, callerPersonId, callerUserId, CANCEL_SQL, CANCELLED_SQL);
    }

    /**
     * Records that the inviter saw the answer. Always answers yes: it changes nothing anybody else can see,
     * and a yes/no here would tell a caller which invitation ids are answered and theirs.
     */
    static Future<Boolean> seeAnswer(Object rawInvitationId, Object callerPersonId, Object callerUserId) {
        Object invitationId = Numbers.toLong(rawInvitationId);
        if (invitationId == null)
            return MemberSessionGuard.refused();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] {
                    PersonLinkRules.statement(SEE_ANSWER_SQL, invitationId, callerPersonId) })))
            .map(ignored -> true);
    }

    private static Future<Boolean> writeAndConfirm(Object rawInvitationId, Object callerPersonId,
                                                   Object callerUserId, String writeSql, String readBackSql) {
        Object invitationId = Numbers.toLong(rawInvitationId);
        if (invitationId == null)
            return MemberSessionGuard.refused();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] {
                    PersonLinkRules.statement(writeSql, invitationId, callerPersonId) })))
            .compose(ignored -> ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(readBackSql)
                .setParameters(invitationId, callerPersonId)
                .build())))
            .compose(after -> PersonLinkRules.countAt(after) > 0
                ? Future.succeededFuture(true)
                : Future.failedFuture("[%s] This invitation was not changed".formatted(INVITATION_NOT_CLOSED_KEY)));
    }

    /** Every statement that changes something, for a check to read without a database. */
    static List<String> writeStatements() {
        return List.of(DECLINE_SQL, CANCEL_SQL, SEE_ANSWER_SQL);
    }
}

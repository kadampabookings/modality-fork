package one.modality.crm.server.person;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creating an invitation — as yourself, with a token you do not choose.
 *
 * <p>This is what makes approving one mean anything. An invitation says "this person asked for that link",
 * and {@link PersonLinkRules} approves on the strength of it; but while a client could insert the row, it
 * could name anybody as the inviter, approve it as itself, and take that person's bookings. The proof was
 * a row the attacker wrote. Here the inviter is the caller's own person, from the principal the server
 * minted, and nothing in the argument can say otherwise.
 *
 * <h3>The token</h3>
 *
 * <p>An invitation's token is the capability its email carries: whoever holds it holds the invitation. The
 * client used to pick it and write it into the row — the same code choosing the secret and storing it —
 * which is the shape of thing that is wrong even when the generator happens to be sound (a browser's
 * {@code crypto.randomUUID()} is). It is minted here, from {@link SecureRandom}, and returned once so the
 * inviter's email can carry it. Deliberately NOT from a UUID helper: the one in the platform is
 * {@code Math.random()} on both sides, which made an earlier generation of booking links predictable from
 * a couple of observed ones.
 *
 * <h3>What is refused</h3>
 *
 * <p>Inviting yourself, inviting somebody who is not there, and asking twice — the last because a second
 * pending invitation for the same pair and direction is not a second request, it is the same one with two
 * tokens, and both of them work.
 *
 * @author Claude Code
 */
final class InvitationRules {

    private InvitationRules() {
    }

    /** Refusals, named so the screen says which in the reader's language. */
    static final String CANNOT_INVITE_YOURSELF_KEY = "InvitationSelfError";
    /** An alias longer than the column, which is a caller sending something unusable. */
    static final String ALIAS_TOO_LONG_KEY = "InvitationAliasTooLongError";
    static final String NOT_CREATED_KEY = "InvitationNotCreatedError";

    /**
     * 32 bytes, URL-safe and unpadded, so it survives being pasted into a link.
     *
     * <p>One instance, seeded once: {@link SecureRandom} is thread-safe and reseeds itself, and creating
     * one per call is the reliable way to make token generation slow enough to notice.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String INVITEE_SQL =
        "select count(*) from person where id = $1 and removed = false";


    /**
     * The row, with the duplicate guard inside it.
     *
     * <p>{@code where not exists} rather than acting on the read above: two clicks racing would otherwise
     * both pass the check and both insert, leaving one request with two live tokens.
     */
    private static final String CREATE_SQL =
        "insert into invitation (creation_date, inviter_id, invitee_id, inviter_payer," +
        "                        alias_first_name, alias_last_name, pending, accepted, token)" +
        " select now(), $1, $2, $3, $4, $5, true, false, $6" +
        " where not exists (select 1 from invitation where inviter_id = $1 and invitee_id = $2" +
        "                   and inviter_payer = $3 and pending = true)";

    /** The token of the request that is now pending, which is what the inviter's email needs. */
    private static final String TOKEN_SQL =
        "select token from invitation where inviter_id = $1 and invitee_id = $2" +
        "  and inviter_payer = $3 and pending = true order by creation_date desc limit 1";

    /**
     * Creates an invitation from the caller to {@code rawInviteeId}, and returns its token.
     *
     * @param inviterPays true for "let me book for you" (the invitee approves the caller seeing them),
     *                    false for "please manage my bookings" (the invitee approves seeing the caller)
     * @param callerPersonId the caller's own person, from the principal — the inviter, always
     */
    static Future<Object> createInvitation(Object rawInviteeId, boolean inviterPays,
                                            Object aliasFirstName, Object aliasLastName,
                                            Object subject, Object body,
                                            Object callerPersonId, Object callerUserId) {
        Object inviteeId = Numbers.toLong(rawInviteeId);
        if (inviteeId == null)
            return MemberSessionGuard.refused();
        if (PersonLinkRules.sameId(inviteeId, callerPersonId))
            return refusal(CANNOT_INVITE_YOURSELF_KEY);
        // alias_first_name and alias_last_name are varchar(45) NOT NULL. Left to the database, an
        // over-long one surfaces as a raw Postgres error rather than something a screen can say.
        if (tooLong(aliasFirstName) || tooLong(aliasLastName))
            return refusal(ALIAS_TOO_LONG_KEY);
        return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(INVITEE_SQL)
                .setParameters(inviteeId)
                .build()))
            .compose(invitee -> {
                // Generic, not a named "no such person": a named one answers "does person N exist" for
                // any id a member cares to walk, and they are sequential. A screen never reaches this —
                // it invites somebody it just listed.
                if (countAt(invitee) == 0)
                    return MemberSessionGuard.refused();
                // No "already pending" refusal. The insert below cannot create a second one (its guard
                // says so) and the read-back returns whichever is pending — so asking twice returns the
                // SAME token rather than failing. That matters because the invitation and the email it
                // needs are no longer one transaction: the row commits here, the mail is flushed by the
                // caller, and a mail that failed used to leave the inviter permanently unable to ask
                // again. The screens keep their own "already pending" message; this is about retries.
                return createAndReadBackToken(inviteeId, inviterPays, aliasFirstName, aliasLastName,
                        callerPersonId, callerUserId)
                    // The token no longer leaves this server. It was returned so the browser could
                    // build the approve/decline links; the mail is composed here now, so the only
                    // thing that ever holds the capability is the invitee's own inbox.
                    .compose(token -> MemberMail.sendToPerson(inviteeId, subject, body, token, callerUserId)
                        .map(ignored -> (Object) Boolean.TRUE));
            });
    }

    private static Future<String> createAndReadBackToken(Object inviteeId, boolean inviterPays,
                                                         Object aliasFirstName, Object aliasLastName,
                                                         Object callerPersonId, Object callerUserId) {
        String token = mintToken();
        SubmitArgument insert = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(CREATE_SQL)
            .setParameters(callerPersonId, inviteeId, inviterPays,
                aliasFirstName == null ? "" : aliasFirstName, aliasLastName == null ? "" : aliasLastName, token)
            .build();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { insert })))
            // Read back rather than returning the minted token: the insert is guarded, so it may have
            // changed nothing, and the token that matters is whichever row is actually pending.
            .compose(ignored -> ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(TOKEN_SQL)
                .setParameters(callerPersonId, inviteeId, inviterPays)
                .build())))
            .compose(after -> {
                // Through an Object local, and it MUST stay that way. QueryResult.getValue is
                // `<T> T`, so String.valueOf(result.getValue(...)) lets javac infer T from the most
                // specific applicable overload — String.valueOf(char[]) — and emit a checkcast to
                // [C. The column is a String, so every call threw
                // "class java.lang.String cannot be cast to class [C" at runtime while compiling
                // and passing every source-text check. This endpoint was unusable from the day it
                // was built (2026-09-21) until the first person tried it (2026-09-26).
                Object rawToken = after == null || after.getRowCount() == 0 ? null : after.getValue(0, 0);
                String pendingToken = rawToken == null ? null : String.valueOf(rawToken);
                return pendingToken == null ? refusal(NOT_CREATED_KEY) : Future.succeededFuture(pendingToken);
            });
    }

    /** The statement that creates one, for a check to read without a database. */
    static String createStatement() {
        return CREATE_SQL;
    }

    /** The alias columns are varchar(45); anything longer is refused before it reaches them. */
    static boolean tooLong(Object alias) {
        return alias != null && String.valueOf(alias).length() > 45;
    }

    /** A token nobody can guess, and nobody but this method chooses. */
    static String mintToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static <T> Future<T> refusal(String key) {
        return Future.failedFuture("[%s] This invitation was not created".formatted(key));
    }

    private static long countAt(QueryResult result) {
        if (result == null || result.getRowCount() == 0)
            return 0;
        Object value = result.getValue(0, 0);
        return value instanceof Number number ? number.longValue() : 0;
    }
}

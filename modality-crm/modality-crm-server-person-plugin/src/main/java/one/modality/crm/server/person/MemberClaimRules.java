package one.modality.crm.server.person;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The claim: member rows somebody else booked for MY address, linked to me.
 *
 * <p>Before a person has an account, a centre may book for them and record their address on a member
 * row of the booker's account. When they sign up, those rows are theirs to see — so signing up links
 * them. Without it the signup creates the duplicate the /members merge scripts exist to clean up, and
 * the person still cannot see what was booked for them.
 *
 * <h3>The proof, and why the client stops naming anything</h3>
 *
 * <p>The browser used to run the query, read the rows and write
 * {@code update Person set accountPerson = me} for each — so the set of rows claimed was chosen by the
 * side making the claim. A modified client could name any person id and take that person's bookings
 * and media (linking TRANSFERS media and SHARES orders). The server derives the set from the caller's
 * own {@code frontend_account.username} — the address they proved control of when they signed in, and
 * one only the server ever writes.
 *
 * <p>A caller may pass ids to NARROW that set, and only to narrow it: the two are intersected, so an
 * id outside the derived set matches nothing. Screens need it because they do not always offer
 * everything that matches — see {@link #narrowedTo}.
 *
 * <p>The same five conditions the client's query carried, now enforced where they are decided:
 * a live row, not an account OWNER (another holder with the same address is a duplicate signup, which
 * is a merge, not a claim), not already linked to anybody, on an account, and not on the caller's own.
 *
 * <h3>What the proof rests on, and how it was forgeable until 2026-09-28</h3>
 *
 * <p>The proof is {@code person.email} on the target, and that column was <b>writable by an ORDINARY
 * client write</b> — not a raw or hand-crafted one, the same shape a change set produces. The owner
 * rule that stood there guarded only an OWNER's email, and every row this claims is a non-owner. So a
 * signed-in member could send {@code update Person set email=$1 where id=$2} against a stranger's
 * member row, point it at their own address, and then claim it legitimately through here — set-based,
 * over any number of strangers in one batch.
 *
 * <p><b>Closed by {@code denyTable("person")}</b>: no client writes that column, or any other on the
 * row, by any statement shape. The proof is now unforgeable from a client rather than merely narrow.
 * It still rests on the SERVER paths that write an email being right about whose row they are on,
 * which is {@code PersonDetailsRules}' and {@code StaffPersonRules}' business rather than this file's.
 *
 * @author Claude Code
 */
final class MemberClaimRules {

    private MemberClaimRules() {
    }

    /** The caller's account has no sign-in address to match on — nothing can be claimed for it. */
    static final String NO_ADDRESS_KEY = "MemberClaimNoAddressError";

    /** The address this caller proved control of. Written by the server alone; never by a client. */
    private static final String CALLER_ADDRESS_SQL =
        "select username from frontend_account where id = $1";

    /**
     * The rows that are this human, by the address they carry.
     *
     * <p>Read before the write so the caller can be told WHICH rows were claimed — the notices their
     * screen sends go to the owners of those accounts, and it needs to know which.
     */
    /**
     * What makes a row claimable — written once, because two statements have to agree about it.
     *
     * <p>{@link #offers} shows a member what they may claim and {@link #claimable} decides what
     * {@link #claim} will take. If those two ever disagree, a member is shown a row that is then not
     * claimed, or claims one they were never shown — and the second case is the worse one, because the
     * notice to that account's owner is sent by the screen, from the list the screen was given.
     *
     * <p>Not shared with {@link #CLAIM_SQL}, which spells the same five conditions again on purpose: see
     * its own note. That repetition is between a read and the write that follows it, and exists so a row
     * that changed in between is not claimed on the strength of how it looked a moment earlier.
     *
     * <p>Parameters: {@code $1} the caller's own address, {@code $2} their account.
     */
    private static final String CLAIMABLE_WHERE =
        " where lower(email) = lower($1) and owner = false and removed = false" +
        " and account_person_id is null and frontend_account_id is not null" +
        " and frontend_account_id <> $2";

    private static final String CLAIMABLE_SQL = "select id from person" + CLAIMABLE_WHERE;

    /**
     * The same rows, with what a screen needs to name them. No email: the row's own address is the
     * proof this derivation runs on, never something a caller is told — and no screen reads it. The
     * only address in the reply is the OWNER's, which is where the claim notice goes.
     */
    private static final String OFFER_SQL =
        "select id, first_name, last_name, frontend_account_id from person" + CLAIMABLE_WHERE;

    /**
     * Who holds the accounts those rows sit in — the name a member is shown and the address the notice
     * goes to.
     *
     * <p>The accounts are derived from {@link #OFFER_SQL}'s own result, never taken from the caller.
     * Taking them as an argument would be an oracle on any account id: "who owns 23147?" is the question
     * {@code PersonReadScope} was written to stop a member asking, and an endpoint does not close an
     * oracle while an argument reopens it.
     *
     * <p>All owner rows, not one per account: an account can carry more than one {@code owner = true}
     * row, and which of them is the holder is the caller's tie-break to make — lowest person id wins,
     * where it is already tested.
     */
    private static final String OFFER_OWNERS_SQL =
        "select id, first_name, last_name, email, frontend_account_id from person" +
        " where owner = true and removed = false and frontend_account_id in (";

    /**
     * The caller may NARROW the set to rows it asked about — never widen it.
     *
     * <p>Needed because a screen does not always offer everything that matches. The media pages list
     * only the rows with something that will still play, so claiming from one of them used to link
     * exactly those; deriving the set alone linked every row carrying the address, including ones the
     * member was never shown — and whose account owners then got no notice, because the screen did not
     * know they existed.
     *
     * <p>Safe to accept from a client precisely because it intersects: an id outside the derived set
     * matches nothing, so naming somebody else's row claims nothing. Sending no ids means "everything
     * that matches", which is what signup wants.
     */
    private static String narrowedTo(int count, int firstParameter) {
        return count == 0 ? "" : " and id in (" + AccountMergeRules.placeholders(firstParameter, count) + ")";
    }

    /**
     * Links them, repeating every condition the read applied.
     *
     * <p>Repeated rather than trusted: the read and the write are two statements, and a row that gained
     * an account owner, a link or a deletion in between must not be claimed on the strength of how it
     * looked a moment earlier. The set is derived either way, so the repetition costs nothing but says
     * the rule where it is enforced.
     *
     * <p>{@code account_person_revoked_date = null} travels with the link, as it does everywhere else:
     * a row withdrawn earlier and claimed again is linked, not left half-revoked.
     */
    private static final String CLAIM_SQL =
        "update person set account_person_id = $1, account_person_revoked_date = null" +
        " where lower(email) = lower($2) and owner = false and removed = false" +
        " and account_person_id is null and frontend_account_id is not null" +
        " and frontend_account_id <> $3";

    /**
     * Claims every member row carrying the caller's own sign-in address.
     *
     * @param callerPersonId  the caller's person, from the principal — what the rows are linked TO
     * @param callerAccountId the caller's account, from the principal — whose address is the proof, and
     *                        whose own rows are excluded
     * @param onlyIds         rows the caller is asking about, intersected with the derived set; empty
     *                        or null means everything that matches
     * @param callerUserId    the caller's principal, READ ON THE CALLER'S THREAD. {@code person} has
     *                        audit triggers, and {@code person_link_change} rows naming nobody are what
     *                        the first version of the merge produced.
     * @return the ids linked, for the notices the caller's screen sends
     */
    static Future<Object> claim(Object callerPersonId, Object callerAccountId, List<Object> onlyIds,
                                Object callerUserId) {
        List<Object> narrowing = new ArrayList<>();
        if (onlyIds != null)
            for (Object id : onlyIds) {
                Object number = Numbers.toLong(id);
                if (number != null && !narrowing.contains(number))
                    narrowing.add(number);
            }
        return address(callerAccountId)
            .compose(address -> address == null || address.isBlank() ? refusal(NO_ADDRESS_KEY)
                : claimable(address, callerAccountId, narrowing)
                    .compose(ids -> ids.isEmpty() ? Future.succeededFuture((Object) new Object[0])
                        : run(callerPersonId, address, callerAccountId, narrowing, callerUserId, ids)));
    }

    /**
     * What this caller may claim, and who they would be claiming it from.
     *
     * <p>The read half of {@link #claim}, and the reason it exists is the same reason the write half
     * stopped trusting the client: the browser used to FIND these rows itself, with
     * {@code select … from Person where lower(email)=$1 and frontendAccount!=$2}. That statement reaches
     * across accounts by an address the caller supplies, which is an enumeration oracle on the whole
     * {@code person} table — ask it about any address and learn whether somebody booked for it, and in
     * whose account. {@code PersonReadScope} closed it, and closing it is what left the screens with
     * nothing to show. So the question moves here, where the address is not an argument: it is read
     * from the caller's own {@code frontend_account.username}, which only the server ever writes.
     *
     * <p>Takes no argument at all, deliberately. There is one set a caller may be offered and it is
     * fully determined by who they are, so there is nothing for a caller to choose and no narrowing to
     * accept — unlike {@link #claim}, which takes ids because a screen must be able to claim fewer rows
     * than match, having shown fewer.
     *
     * <p>Reply: {@code {members: [{id, firstName, lastName, accountId}], owners: [{id, firstName,
     * lastName, email, accountId}]}}. Two lists rather than rows already joined, because the join has a
     * rule in it — an account may hold several owner rows and the lowest id is the holder — and that
     * rule is already written and tested on the client that consumes this.
     *
     * <p>The owners query runs only when something matched, so an ordinary signup, which matches
     * nothing, costs one statement rather than two.
     *
     * @param callerAccountId the caller's account, from the principal — never from the argument
     */
    static Future<Object> offers(Object callerAccountId) {
        return address(callerAccountId)
            .compose(address -> address == null || address.isBlank() ? refusal(NO_ADDRESS_KEY)
                : query(OFFER_SQL, address, callerAccountId)
                    .compose(members -> owners(members)
                        .map(owners -> reply(members, owners))));
    }

    /** The owner rows of the accounts {@link #OFFER_SQL} just found — derived, never asked for. */
    private static Future<QueryResult> owners(QueryResult members) {
        List<Object> accountIds = new ArrayList<>();
        if (members != null)
            for (int row = 0; row < members.getRowCount(); row++) {
                Object accountId = Numbers.toLong(members.getValue(row, 3));
                if (accountId != null && !accountIds.contains(accountId))
                    accountIds.add(accountId);
            }
        if (accountIds.isEmpty())
            return Future.succeededFuture(null);
        return query(OFFER_OWNERS_SQL + AccountMergeRules.placeholders(1, accountIds.size()) + ")",
            accountIds.toArray());
    }

    /**
     * The two lists, as the bus carries them.
     *
     * <p>{@code setScalar} throughout rather than a typed overload: the ids are whatever width the
     * driver returned, and casting to pick an overload would decide the wire type by which cast was
     * written — the trap {@code MatchingAccountsEndpoint} records.
     */
    private static Object reply(QueryResult members, QueryResult owners) {
        AstArray memberArray = AST.createArray();
        if (members != null)
            for (int row = 0; row < members.getRowCount(); row++) {
                AstObject member = AST.createObject();
                member.setScalar("id", members.getValue(row, 0));
                member.setScalar("firstName", members.getValue(row, 1));
                member.setScalar("lastName", members.getValue(row, 2));
                member.setScalar("accountId", members.getValue(row, 3));
                memberArray.push(member);
            }
        AstArray ownerArray = AST.createArray();
        if (owners != null)
            for (int row = 0; row < owners.getRowCount(); row++) {
                AstObject owner = AST.createObject();
                owner.setScalar("id", owners.getValue(row, 0));
                owner.setScalar("firstName", owners.getValue(row, 1));
                owner.setScalar("lastName", owners.getValue(row, 2));
                owner.setScalar("email", owners.getValue(row, 3));
                owner.setScalar("accountId", owners.getValue(row, 4));
                ownerArray.push(owner);
            }
        AstObject reply = AST.createObject();
        reply.setArray("members", memberArray);
        reply.setArray("owners", ownerArray);
        return reply;
    }

    private static Future<String> address(Object callerAccountId) {
        return query(CALLER_ADDRESS_SQL, callerAccountId)
            .map(result -> result == null || result.getRowCount() == 0 ? null
                : (String) result.getValue(0, 0));
    }

    private static Future<List<Object>> claimable(String address, Object callerAccountId,
                                                  List<Object> narrowing) {
        Object[] parameters = new Object[2 + narrowing.size()];
        parameters[0] = address;
        parameters[1] = callerAccountId;
        for (int i = 0; i < narrowing.size(); i++)
            parameters[i + 2] = narrowing.get(i);
        return query(CLAIMABLE_SQL + narrowedTo(narrowing.size(), 3), parameters)
            .map(result -> {
                List<Object> ids = new ArrayList<>();
                if (result != null)
                    for (int row = 0; row < result.getRowCount(); row++)
                        ids.add(Numbers.toLong(result.getValue(row, 0)));
                return ids;
            });
    }

    /**
     * The write, then a read of what it actually linked.
     *
     * <p>Read back rather than counted from the write: {@code getGeneratedKeys()} carries one value per
     * result set and not per row, so it cannot say which rows a set-based update touched — the same
     * trap as {@code SubmitResult.getRowCount()}. And the answer here is not a count but a LIST, since
     * the screen sends a notice per claimed row.
     */
    private static Future<Object> run(Object callerPersonId, String address, Object callerAccountId,
                                      List<Object> narrowing, Object callerUserId, List<Object> expected) {
        Object[] parameters = new Object[3 + narrowing.size()];
        parameters[0] = callerPersonId;
        parameters[1] = address;
        parameters[2] = callerAccountId;
        for (int i = 0; i < narrowing.size(); i++)
            parameters[i + 3] = narrowing.get(i);
        SubmitArgument claim = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(CLAIM_SQL + narrowedTo(narrowing.size(), 4))
            .setParameters(parameters)
            .build();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { claim })))
            .compose(ignored -> linkedAmong(callerPersonId, expected));
    }

    /** Which of the rows we expected to claim really are linked to this caller now. */
    private static Future<Object> linkedAmong(Object callerPersonId, List<Object> expected) {
        String in = AccountMergeRules.placeholders(2, expected.size());
        Object[] parameters = new Object[expected.size() + 1];
        parameters[0] = callerPersonId;
        for (int i = 0; i < expected.size(); i++)
            parameters[i + 1] = expected.get(i);
        return query("select id from person where account_person_id = $1 and id in (" + in + ")", parameters)
            .map(result -> {
                List<Object> linked = new ArrayList<>();
                if (result != null)
                    for (int row = 0; row < result.getRowCount(); row++)
                        linked.add(Numbers.toLong(result.getValue(row, 0)));
                return (Object) linked.toArray();
            });
    }

    private static Future<QueryResult> query(String sql, Object... parameters) {
        return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters)
            .build()));
    }

    private static <T> Future<T> refusal(String key) {
        return Future.failedFuture("[" + key + "] This operation is not available to you");
    }
}

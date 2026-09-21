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
 * <h3>What this does NOT yet close, stated plainly</h3>
 *
 * <p>The proof is {@code person.email} on the target, and <b>that column is still client-writable by
 * an ORDINARY client write</b> — not a raw or hand-crafted one, the same shape a change set produces.
 * {@code OwnerLoginWritePolicy} guards only an OWNER's email, and every row this claims is a
 * non-owner. So a signed-in member can send {@code update Person set email=$1 where id=$2} against a
 * stranger's member row, point it at their own address, and then claim it legitimately through here.
 *
 * <p>That is two steps where it used to be one, and much narrower — but <b>it is not closed, and must
 * not be described as though it were</b>. Closing it needs {@code person.email} denied to clients,
 * which today breaks the live legacy back office (its customers view edits an existing customer's
 * address), or the person-ownership rule the plan lists as item C.
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
    private static final String CLAIMABLE_SQL =
        "select id from person" +
        " where lower(email) = lower($1) and owner = false and removed = false" +
        " and account_person_id is null and frontend_account_id is not null" +
        " and frontend_account_id <> $2";

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

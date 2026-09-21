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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Consolidating people onto one account — the last thing the back office did with a change set.
 *
 * <p>Two dialogs sent it. {@code MergeAccountsDialog} reads every person of the accounts being merged
 * and moves them; {@code MergeCustomersDialog} moves the customers a member of staff selected as
 * duplicates, and links them to the destination's owner. Both then deleted the source
 * {@code frontend_account} rows. One operation, named two ways — so this takes both namings and
 * derives everything else.
 *
 * <h3>What the client no longer decides</h3>
 *
 * <ul>
 *   <li><b>Which accounts get deleted.</b> The client computed "accounts that are now empty" and asked
 *       for those ids. Emptiness is a fact about the database after the move, and it is now checked
 *       there, in the same transaction and inside the DELETE's own WHERE — a client that asked for a
 *       populated account is refused by the statement rather than believed.</li>
 *   <li><b>Whether a moved person may be moved.</b> {@code PersonAccountMovePolicy} refuses moving
 *       somebody who holds authorizations unless a super administrator asks, because a person moved
 *       into an account becomes somebody that account can sign in as — with their grants. These
 *       endpoints run "as server" and so are NOT seen by that policy; the rule is re-asked here, over
 *       the whole moving set at once.</li>
 *   <li><b>Who the moved rows are linked to.</b> {@code accountPerson} said "this row is the human
 *       Q", and the client named Q. Q must now be an OWNER of the destination account, which is the
 *       only shape the dialog ever legitimately sends.</li>
 * </ul>
 *
 * <p><b>{@code owner} is cleared on every moved row</b>, as the policy requires: two owners on one
 * account is the state that already breaks sign-in resolution, and an account that keeps an owner it
 * no longer holds is worse.
 *
 * @author Claude Code
 */
final class AccountMergeRules {

    private AccountMergeRules() {
    }

    /** The caller may move people, but one of these holds authorizations — a super admin's call only. */
    static final String GRANT_HOLDER_KEY = "AccountMergeGrantHolderError";
    /** The destination, a source, or a link target that is not what it was said to be. */
    static final String TARGET_KEY = "AccountMergeTargetError";
    /** Nothing named, or nothing that could be moved. */
    static final String NOTHING_KEY = "AccountMergeNothingError";

    /** Whether the destination account exists. A merge onto a deleted account would strand everybody. */
    private static final String ACCOUNT_SQL = "select id from frontend_account where id = $1";

    /**
     * Whether this person is an owner of that account — the only rows {@code accountPerson} may name.
     *
     * <p>A live one: {@code removed = false}. Linking rows to a deleted owner says they are somebody
     * the system no longer holds.
     */
    private static final String DESTINATION_OWNER_SQL =
        "select id from person where id = $1 and frontend_account_id = $2 and owner = true and removed = false";

    /**
     * How many of the moving people hold any authorization, by the three tables the grant push reads.
     *
     * <p>A count rather than a list, deliberately: the caller is being told whether their merge may
     * proceed, not which of these people hold what.
     */
    private static String grantHoldersSql(int count) {
        return "select count(1) as n from person p where p.id in (" + placeholders(1, count) + ")"
               + " and (exists (select 1 from authorization_super_admin sa where sa.super_admin_id = p.id)"
               + " or exists (select 1 from authorization_organization_admin oa where oa.admin_id = p.id)"
               + " or exists (select 1 from authorization_organization_user_access ua where ua.user_id = p.id))";
    }

    /** Everyone on these accounts, REMOVED ROWS INCLUDED — a row left behind would block the delete. */
    private static String personsOfAccountsSql(int count) {
        return "select id, frontend_account_id from person where frontend_account_id in ("
               + placeholders(1, count) + ")";
    }

    /** Where those people came from, read before the move so the empty-account test has candidates. */
    private static String accountsOfPersonsSql(int count) {
        return "select id, frontend_account_id from person where id in (" + placeholders(1, count) + ")";
    }

    /**
     * Moves the named people onto the destination account.
     *
     * @param destinationAccountId the account everybody lands on
     * @param sourceAccountIds     accounts whose every person moves (the whole-account merge)
     * @param personIds            individual people who move (the duplicate-customer merge)
     * @param linkToPersonId       set as {@code accountPerson} on each moved row, or null for no link
     * @param callerIsSuperAdmin   decided by the endpoint before this runs; only a super admin may move
     *                             somebody who holds authorizations
     * @param callerUserId         the caller's principal, READ ON THE CALLER'S THREAD — {@code person}'s
     *                             audit triggers stamp {@code changed_by_person_id} from it, and a merge
     *                             recorded as nobody's is what the first version of the customer merge did
     * @return the number of people moved
     */
    static Future<Object> merge(Object destinationAccountId, List<Object> sourceAccountIds,
                                List<Object> personIds, Object linkToPersonId,
                                boolean callerIsSuperAdmin, Object callerUserId) {
        Object destination = Numbers.toLong(destinationAccountId);
        if (destination == null)
            return refusal(TARGET_KEY);
        List<Object> sources = numbers(sourceAccountIds);
        List<Object> named = numbers(personIds);
        if (sources.isEmpty() && named.isEmpty())
            return refusal(NOTHING_KEY);
        Object link = linkToPersonId == null ? null : Numbers.toLong(linkToPersonId);
        if (linkToPersonId != null && link == null)
            return refusal(TARGET_KEY);
        // A source that IS the destination would move everybody onto the account they are already on and
        // then try to delete it. Dropped rather than refused: the dialogs exclude it themselves, and a
        // merge that names it means the same thing without it.
        sources.remove(destination);
        return exists(ACCOUNT_SQL, destination)
            .compose(destinationExists -> !destinationExists ? refusal(TARGET_KEY)
                : linkOk(link, destination)
                    .compose(ok -> !ok ? AccountMergeRules.<Object>refusal(TARGET_KEY)
                        : movingSet(sources, named)
                            .compose(moving -> moving.isEmpty() ? AccountMergeRules.<Object>refusal(NOTHING_KEY)
                                : refuseGrantHolders(moving.keySet(), callerIsSuperAdmin)
                                    .compose(refusal -> refusal != null ? AccountMergeRules.<Object>refusal(refusal)
                                        : runMerge(destination, moving, sources, link, callerUserId)))));
    }

    /** No link is fine; a link must name a live owner of the destination. */
    private static Future<Boolean> linkOk(Object link, Object destination) {
        return link == null ? Future.succeededFuture(true)
            : exists(DESTINATION_OWNER_SQL, link, destination);
    }

    /**
     * Everybody who will move, and the account each of them is on.
     *
     * <p>Both halves in one read. It used to take the ids here and ask a second statement which
     * accounts those ids were on — two readings of "who is on which account", taken at two instants,
     * that had to agree.
     */
    private static Future<java.util.Map<Object, Object>> movingSet(List<Object> sources, List<Object> named) {
        java.util.Map<Object, Object> moving = new java.util.LinkedHashMap<>();
        List<Object> lookFor = new ArrayList<>(named);
        Future<QueryResult> byAccount = sources.isEmpty() ? Future.succeededFuture(null)
            : query(personsOfAccountsSql(sources.size()), sources.toArray());
        return byAccount.compose(result -> {
            if (result != null)
                for (int row = 0; row < result.getRowCount(); row++)
                    moving.put(Numbers.toLong(result.getValue(row, 0)), Numbers.toLong(result.getValue(row, 1)));
            // The named people may be anywhere, including on no account at all.
            lookFor.removeAll(moving.keySet());
            if (lookFor.isEmpty())
                return Future.succeededFuture(moving);
            return query(accountsOfPersonsSql(lookFor.size()), lookFor.toArray())
                .map(named2 -> {
                    for (int row = 0; row < named2.getRowCount(); row++)
                        moving.put(Numbers.toLong(named2.getValue(row, 0)), Numbers.toLong(named2.getValue(row, 1)));
                    return moving;
                });
        });
    }

    /** The refusal key when somebody in the set holds authorizations and the caller is not a super admin. */
    private static Future<String> refuseGrantHolders(java.util.Set<Object> moving, boolean callerIsSuperAdmin) {
        if (callerIsSuperAdmin)
            return Future.succeededFuture(null);
        return query(grantHoldersSql(moving.size()), moving.toArray())
            // FAILS CLOSED. A result this cannot read is not "nobody holds a grant" — it is "the one
            // rule protecting grant holders could not be evaluated", and the difference matters because
            // every other branch of this method is a refusal. `select count(1)` always returns a row
            // today; a later `limit`, `where` or union over it would silently turn the rule off.
            .map(result -> {
                Integer holders = result == null || result.getRowCount() == 0 ? null
                    : Numbers.toInteger(result.getValue(0, 0));
                return holders == null || holders > 0 ? GRANT_HOLDER_KEY : null;
            })
            .otherwise(GRANT_HOLDER_KEY);
    }

    /**
     * The move and the deletes, in one transaction.
     *
     * <p>Order matters and is the whole of it: the accounts are read BEFORE the move (afterwards nobody
     * points at them), the move empties them, and the delete then tests emptiness for itself. A client
     * that named a populated account deletes nothing.
     */
    private static Future<Object> runMerge(Object destination, java.util.Map<Object, Object> moving,
                                           List<Object> namedSources, Object link, Object callerUserId) {
        Object[] movingIds = moving.keySet().toArray();
        // Every account somebody is leaving, plus every account the CALLER named as a source. The named
        // ones matter for the case the derived list misses: a source whose people were all moved or
        // deleted by somebody else between the dialog loading and the merge contributes nobody here, so
        // its now-orphaned row would have survived. Adding them is safe precisely because the delete
        // tests emptiness for itself.
        java.util.Set<Object> candidates = new LinkedHashSet<>(namedSources);
        candidates.addAll(moving.values());
        candidates.remove(destination); // it is about to hold everybody
        candidates.remove(null);        // somebody on no account at all leaves no account behind
        List<SubmitArgument> statements = new ArrayList<>();
        statements.add(submit(moveSql(movingIds.length, link != null),
            parameters(destination, link, movingIds)));
        if (!candidates.isEmpty())
            statements.add(submit(deleteEmptyAccountsSql(candidates.size()), candidates.toArray()));
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(
                    new Batch<>(statements.toArray(new SubmitArgument[0]))))
            // Read back rather than counted from the statement: generated keys carry ONE value per
            // result set and not per row (VertxSqlUtil takes iterator.next() once), so the array's
            // length is 1 for any non-empty update however many rows it matched. The same trap as
            // SubmitResult.getRowCount, one layer along.
            .compose(ignored -> countOnAccount(destination, movingIds));
    }

    /** How many of the moving people are actually on the destination now — the honest count. */
    private static Future<Object> countOnAccount(Object destination, Object[] movingIds) {
        Object[] parameters = new Object[movingIds.length + 1];
        parameters[0] = destination;
        System.arraycopy(movingIds, 0, parameters, 1, movingIds.length);
        return query("select count(1) as n from person where frontend_account_id = $1 and id in ("
                     + placeholders(2, movingIds.length) + ")", parameters)
            .map(result -> result == null || result.getRowCount() == 0 ? (Object) 0
                : (Object) Numbers.toInteger(result.getValue(0, 0)));
    }

    /** {@code owner} is always cleared; {@code account_person_id} only when a link was named and checked. */
    static String moveSql(int personCount, boolean withLink) {
        int firstPerson = withLink ? 3 : 2;
        return "update person set frontend_account_id = $1, owner = false"
               // The revoked date goes with the link, always — PersonLinkRules writes the two together
               // for the same reason. A moved person carrying a previously revoked link would otherwise
               // keep a stale revocation beside a fresh link. It fails safe (a non-null revoked date
               // reads as no link), but a pair that is only sometimes written is a pair that will be
               // read inconsistently one day.
               + (withLink ? ", account_person_id = $2, account_person_revoked_date = null" : "")
               + " where id in (" + placeholders(firstPerson, personCount) + ")"
               // NOT the people already there. Without it, naming the destination's own owner among
               // the movers cleared their owner flag and left that account with nobody speaking for
               // it — and the delete would not remove it either, since it still holds people. A
               // merge should never be able to un-own the account it merges ONTO.
               + " and frontend_account_id is distinct from $1"
               + " returning id";
    }

    /**
     * Deletes only the accounts that really are empty now.
     *
     * <p>The {@code not exists} is the point: the client used to name the accounts it believed had been
     * emptied, and an account named wrongly — or named deliberately — took its remaining people's
     * sign-in with it.
     */
    static String deleteEmptyAccountsSql(int accountCount) {
        return "delete from frontend_account where id in (" + placeholders(1, accountCount) + ")"
               + " and not exists (select 1 from person p where p.frontend_account_id = frontend_account.id)";
    }

    private static Object[] parameters(Object destination, Object link, Object[] movingIds) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(destination);
        if (link != null)
            parameters.add(link);
        for (Object id : movingIds)
            parameters.add(id);
        return parameters.toArray();
    }

    /**
     * Soft-deletes duplicate customers, refusing an account owner.
     *
     * <p>One statement for all of them: they are duplicates of one another, and half a merge cleaned up
     * is a worse state than none.
     *
     * @return the ids that ARE removed afterwards — not the ids asked about. An account owner among
     *         them is skipped by the statement, and a screen told "all of them" would drop a row from
     *         its table that still exists. See {@link #removedAmong} for why the write cannot say.
     */
    static Future<Object> removeCustomers(List<Object> personIds, Object callerUserId) {
        List<Object> ids = numbers(personIds);
        if (ids.isEmpty())
            return refusal(NOTHING_KEY);
        SubmitArgument remove = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(removeCustomersSql(ids.size()))
            .setParameters(ids.toArray())
            .build();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { remove })))
            .compose(ignored -> removedAmong(ids));
    }

    /**
     * Which of them really are removed now — read back, because the write cannot say.
     *
     * <p>{@code getGeneratedKeys()} collects ONE value per result set and not per row
     * ({@code VertxSqlUtil} calls {@code iterator.next()} once), so its length is 1 for any non-empty
     * update however many rows matched — the same trap as {@code SubmitResult.getRowCount}, one layer
     * along. Counting from it would have told the screen that an account owner it named was removed
     * when the statement's {@code owner = false} had skipped them.
     *
     * @return the ids that are removed, which the screen uses instead of the ids it asked about
     */
    private static Future<Object> removedAmong(List<Object> ids) {
        return query("select id from person where id in (" + placeholders(1, ids.size()) + ")"
                     + " and removed = true", ids.toArray())
            .map(result -> {
                List<Object> removed = new ArrayList<>();
                if (result != null)
                    for (int row = 0; row < result.getRowCount(); row++)
                        removed.add(Numbers.toLong(result.getValue(row, 0)));
                return (Object) removed.toArray();
            });
    }

    /**
     * {@code owner = false} for the reason every other person statement carries it: removing the row a
     * sign-in resolves to leaves the account unable to reach itself.
     */
    static String removeCustomersSql(int count) {
        return "update person set removed = true where id in (" + placeholders(1, count) + ")"
               + " and owner = false";
    }

    /** {@code $from, $from+1, …} — generated, never interpolated, so every id stays a parameter. */
    static String placeholders(int from, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++)
            sb.append(i == 0 ? "$" : ", $").append(from + i);
        return sb.toString();
    }

    /** The ids that really are numbers, in order, without duplicates. */
    private static List<Object> numbers(List<Object> raw) {
        List<Object> out = new ArrayList<>();
        if (raw != null)
            for (Object value : raw) {
                Object number = Numbers.toLong(value);
                if (number != null && !out.contains(number))
                    out.add(number);
            }
        return out;
    }

    private static Future<Boolean> exists(String sql, Object... parameters) {
        return query(sql, parameters).map(result -> result != null && result.getRowCount() > 0);
    }

    private static Future<QueryResult> query(String sql, Object... parameters) {
        return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters)
            .build()));
    }

    private static SubmitArgument submit(String sql, Object[] parameters) {
        return new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters)
            .build();
    }

    private static <T> Future<T> refusal(String key) {
        return Future.failedFuture("[" + key + "] This operation is not available to you");
    }
}

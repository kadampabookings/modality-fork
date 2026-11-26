package one.modality.crm.server.person;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

/**
 * "Who are the people who can book for me?" — the account holders behind this caller's managers.
 *
 * <p>Takes no argument. Reply: {@code {owners: [{id, firstName, lastName, email, accountId}]}}.
 *
 * <h3>Why the members page cannot answer this itself</h3>
 *
 * <p>Its own {@code MANAGERS_QUERY} still works and still must: it asks for rows whose
 * {@code accountPerson} is the caller, which is exactly what {@code PersonReadScope}'s second arm
 * permits a member to read. But those rows are the caller's OWN row inside the manager's account —
 * their name on it is the caller's name, not the manager's. The page has never displayed it as the
 * manager; it displays the account's owner, falling back to that row only when the owner is unknown.
 *
 * <p>So without this, every manager card reads back the caller's own name: "Bruno can book for you",
 * shown to Bruno. The enrichment query that used to supply the real name —
 * {@code select id,firstName,lastName,email,frontendAccount from Person where frontendAccount in $1 and
 * owner=true} — reaches into accounts that are not the caller's, which the scope now refuses, and
 * rightly: with the account ids coming from the caller it answers "who owns account 23147?" for any
 * number the caller cares to send.
 *
 * <h3>What makes this version safe</h3>
 *
 * <p>The accounts are not an argument. They are derived from the same relationship the page is about —
 * an account holding a live, unrevoked row pointing at the caller — so the only accounts named in the
 * reply are ones that already hold a booking permission over the caller. A caller who sends ids gets
 * nothing for them, because there is nowhere to send them.
 *
 * <p>Deriving rather than trusting also keeps the two sides honest: the conditions below are the ones
 * {@code MANAGERS_QUERY} applies, so the owners returned are the owners of exactly the accounts whose
 * manager cards the page is about to draw. Were the client to pass its list instead, a stale or edited
 * list would name an account the relationship no longer justifies.
 *
 * <p>All owner rows are returned, not one per account: an account can carry several {@code owner = true}
 * rows — five do on staging, from bad merges and KBS2 imports. Which one is the holder is the caller's
 * tie-break: lowest person id, since the holder's row is created with the account. <b>Returning them
 * unordered is only safe because the caller applies that rule</b>, and on the managers path it did not
 * until this endpoint was written — it kept whichever row arrived last, which Postgres is free to
 * change after a VACUUM. If a future caller drops the tie-break, add {@code order by
 * frontend_account_id, id} here and have it keep the first.
 *
 * <p>Behind {@link MemberSessionGuard#whenCallerIsVerifiedMember}: this hands out a name and an address
 * for each person who holds a permission over the caller, so it asks for a session this server
 * established. A support view is refused there, since its principal carries the viewed member's person
 * id and every test below would be asked about the wrong person.
 *
 * @author Claude Code
 */
public final class ManagerAccountOwnersEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.MANAGER_ACCOUNT_OWNERS. */
    public static final String MANAGER_ACCOUNT_OWNERS_ADDRESS =
        "modality/service/person/managerAccountOwners";

    /**
     * The owners of the accounts that hold a live link to this caller.
     *
     * <p>The inner select is {@code MANAGERS_QUERY}, server-side: a row in another account whose
     * {@code account_person_id} is the caller, still a member rather than a holder, not deleted and not
     * revoked. {@code account_person_revoked_date is null} is what separates a live permission from a
     * withdrawn one — the link itself is KEPT when revoked (V0065), so past bookings and their
     * recordings stay reachable, and the date is the only thing that says it no longer grants anything.
     *
     * <p>Parameters: {@code $1} the caller's person, {@code $2} their account — both from the principal.
     */
    private static final String MANAGER_OWNERS_SQL =
        "select id, first_name, last_name, email, frontend_account_id from person" +
        " where owner = true and removed = false and frontend_account_id in (" +
        "   select frontend_account_id from person" +
        "    where account_person_id = $1 and owner = false and removed = false" +
        "      and account_person_revoked_date is null and frontend_account_id is not null" +
        "      and frontend_account_id <> $2)";

    public ManagerAccountOwnersEndpoint() {
        super(MANAGER_ACCOUNT_OWNERS_ADDRESS, argument ->
            MemberSessionGuard.whenCallerIsVerifiedMember((callerPersonId, callerAccountId) ->
                ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                    .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                    .setStatement(MANAGER_OWNERS_SQL)
                    .setParameters(callerPersonId, callerAccountId)
                    .build())
                ).map(ManagerAccountOwnersEndpoint::toReply)));
    }

    /**
     * The owner rows as {@code {owners: [...]}}.
     *
     * <p>Wrapped in an object rather than returned as a bare array so the reply can gain a field without
     * changing shape — and so it reads the same way as {@code claimableMembers}, whose two lists could
     * never have been a bare array.
     *
     * <p>{@code setScalar} throughout: the ids are whatever width the driver returned, and casting to
     * choose a typed overload would decide the wire type by which cast was written.
     */
    private static Object toReply(QueryResult result) {
        AstArray owners = AST.createArray();
        if (result != null)
            for (int row = 0; row < result.getRowCount(); row++) {
                AstObject owner = AST.createObject();
                owner.setScalar("id", result.getValue(row, 0));
                owner.setScalar("firstName", result.getValue(row, 1));
                owner.setScalar("lastName", result.getValue(row, 2));
                owner.setScalar("email", result.getValue(row, 3));
                owner.setScalar("accountId", result.getValue(row, 4));
                owners.push(owner);
            }
        AstObject reply = AST.createObject();
        reply.setArray("owners", owners);
        return reply;
    }
}

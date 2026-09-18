package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RoleOperationMembership;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;

import java.util.Arrays;

/**
 * Stops a client moving a person into another account: the account-side road to somebody's bookings, and to their
 * grants.
 *
 * <h3>The road</h3>
 *
 * <p>A sign-in picks its person among the account's persons (a password sign-in takes the first owner). Person rows are
 * not yet guarded by ownership, so a client could set somebody's person's {@code frontendAccount} to its own account,
 * clear its own owner flag, and sign in again: its account would now resolve to them. That means their bookings, orders,
 * media and profile, and their grants if they hold any, while their own account, left without a person, no longer
 * signs in at all. Giving a person who has no account yet an account is the same write, and adopts a guest's bookings.
 * A bulk move ({@code where frontendAccount=$2}) is the same road, wider.
 *
 * <h3>The rule, for client updates of a person that write {@code frontendAccount}</h3>
 *
 * <ul>
 *   <li>Moving people is staff work: a super admin, or a role holding {@code RouteToCustomers}, the operation behind
 *       the customer screens. Their merges are the only client writes that move anybody; the front office only ever
 *       inserts a person into an account. (A RouteTo code is safe to ask here although {@code operation:*} does not
 *       match one: super admins are recognised by membership first, never through that wildcard.)</li>
 *   <li>A person who holds a grant is moved by a super admin only.</li>
 *   <li>A move that does not name its one person by id is a super admin's to make: it may carry people this cannot
 *       see. The back office's account merge names each person instead.</li>
 *   <li>A moved person stops being an owner: the move must write {@code owner=false}, as both merges do. Otherwise an
 *       owner moved into another account with a lower id than its owner becomes who that account signs in as.</li>
 * </ul>
 *
 * <p>A caller who may not move people gets the same refusal whoever the target is, and no lookup of the target is made:
 * a refusal that differed for grant holders would tell anybody who they are.
 *
 * <p>Inserts are left alone: a new person has no bookings to take and holds no grant. Person ownership, when it comes,
 * subsumes this.
 *
 * @author Claude Code
 */
final class PersonAccountMovePolicy implements ClientSubmitGuard.WritePolicy {

    /** The operation that lets a role move people: the one that opens the customer screens, whose merges move them. */
    static final String MOVE_PEOPLE_OPERATION = "RouteToCustomers";

    static final String STAFF_REFUSED = "Only staff can move a person to another account";
    static final String HOLDER_REFUSED = "A person who holds authorizations can only be moved to another account by a super admin";
    static final String UNNAMED_REFUSED = "People are moved to another account one at a time, by id";
    static final String OWNER_REFUSED = "A person moved to another account stops being an owner: the move must clear owner";

    /** Whether this write moves a person to another account. */
    static boolean isAccountMove(ProtectedEntityWriteRegistry.WriteRequest write) {
        return write != null
               && "Person".equals(write.entityName())
               && write.verb() == ProtectedEntityWriteRegistry.WriteVerb.UPDATE
               && write.writtenFields() != null
               && Arrays.asList(write.writtenFields()).contains("frontendAccount");
    }

    /** Whether the move also clears the owner flag, as a move must. */
    static boolean clearsOwner(ProtectedEntityWriteRegistry.WriteRequest write) {
        return write.writtenValues() != null && Boolean.FALSE.equals(write.writtenValues().get("owner"));
    }

    /** Whether the move names its one person by id; otherwise nobody can say who it moves. */
    static boolean namesItsPerson(ProtectedEntityWriteRegistry.WriteRequest write) {
        return Numbers.toLong(write.targetId()) != null;
    }

    /**
     * The refusal decided without a lookup: any move by a caller with no person of their own (anonymous, guest, support
     * view). Null otherwise. Package-private, like the rest of the decision, so scripts/checks/client-write-guard can
     * check it without a running server.
     */
    static String refusalWithoutLookup(ProtectedEntityWriteRegistry.WriteRequest write, Object userId) {
        if (!isAccountMove(write) || GrantTableWritePolicy.callerPersonId(userId) != null)
            return null;
        return namesItsPerson(write) ? STAFF_REFUSED : UNNAMED_REFUSED;
    }

    /**
     * The decision once the lookups are in: whether the moved person holds a grant, whether the caller holds the
     * operation that moves people, and whether the caller is a super admin.
     */
    static String refusalAfterLookup(ProtectedEntityWriteRegistry.WriteRequest write, boolean targetHoldsGrant,
                                     boolean callerMovesPeople, boolean callerIsSuperAdmin) {
        if (!isAccountMove(write) || callerIsSuperAdmin)
            return null;
        if (!namesItsPerson(write))
            return UNNAMED_REFUSED;
        // Before the target's grants, so a caller who may not move anybody learns nothing about who holds them
        if (!callerMovesPeople)
            return STAFF_REFUSED;
        if (targetHoldsGrant)
            return HOLDER_REFUSED;
        return clearsOwner(write) ? null : OWNER_REFUSED;
    }

    @Override
    public Future<String> refusalReason(ProtectedEntityWriteRegistry.WriteRequest write) {
        // Read on the caller's thread: the guard asks synchronously, and the state is gone after the first async hop
        Object userId = ThreadLocalStateHolder.getUserId();
        if (!isAccountMove(write))
            return Future.succeededFuture(null);
        String refusal = refusalWithoutLookup(write, userId);
        if (refusal != null)
            return Future.succeededFuture(refusal);
        Object callerPersonId = GrantTableWritePolicy.callerPersonId(userId);
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return Future.all(
            SuperAdminMembership.isSuperAdminPerson(callerPersonId, entityStore),
            RoleOperationMembership.holdsThroughRole(callerPersonId, MOVE_PEOPLE_OPERATION, entityStore)
        ).compose(caller -> {
            boolean superAdmin = Boolean.TRUE.equals(caller.resultAt(0));
            boolean movesPeople = Boolean.TRUE.equals(caller.resultAt(1));
            // The target is looked up only when its grants can change the answer: never for a caller refused anyway
            if (superAdmin || !movesPeople || !namesItsPerson(write))
                return Future.succeededFuture(refusalAfterLookup(write, true, movesPeople, superAdmin));
            return holdsGrant(Numbers.toLong(write.targetId()), entityStore)
                .map(holds -> refusalAfterLookup(write, Boolean.TRUE.equals(holds), true, false));
        });
    }

    /** Whether this person holds any grant — the three tables the grant push reads, by person id. */
    private static Future<Boolean> holdsGrant(Object personId, EntityStore entityStore) {
        return Future.all(
            SuperAdminMembership.isSuperAdminPerson(personId, entityStore),
            entityStore.executeQuery("select AuthorizationOrganizationAdmin where admin=$1 limit 1", personId)
                .map(rows -> !rows.isEmpty()),
            entityStore.executeQuery("select AuthorizationOrganizationUserAccess where user=$1 limit 1", personId)
                .map(rows -> !rows.isEmpty())
        ).map(composite -> Boolean.TRUE.equals(composite.resultAt(0))
                           || Boolean.TRUE.equals(composite.resultAt(1))
                           || Boolean.TRUE.equals(composite.resultAt(2)));
    }
}

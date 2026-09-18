package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;

import java.util.Arrays;

/**
 * Stops a client moving a person who holds grants into another account: the account-side road to somebody's grants.
 *
 * <h3>The road</h3>
 *
 * <p>Grants are matched on the session's person id, and a sign-in picks that person among its account's persons (a
 * password sign-in takes the first owner). Person rows are not yet guarded by ownership, so a client could set a super
 * admin's person's {@code frontendAccount} to its own account and sign in again: its account would now resolve to the
 * super admin's person, grants and all. A bulk move is the same road, wider — {@code update Person set
 * frontendAccount=$1 where frontendAccount=$2} moves every person of somebody else's account, whoever they are.
 *
 * <h3>The rule, for client updates of a person that write {@code frontendAccount}</h3>
 *
 * <ul>
 *   <li>A move of one person, named by id, is allowed unless that person holds a grant: super admin, organization
 *       admin, or a role in an organization. Merging customers moves people this way, and customers hold none.</li>
 *   <li>A grant holder is moved by a super admin or not at all.</li>
 *   <li>A move whose rows the statement does not name by id is a super admin's to make: it may carry grant holders this
 *       cannot see. The back office's account merge names each person instead.</li>
 * </ul>
 *
 * <p>Inserts are left alone: a new person holds no grant. Person ownership, when it comes, subsumes this; it is here
 * now because grants keyed on the person are only as safe as the person a sign-in resolves to.
 *
 * @author Claude Code
 */
final class GrantHolderMovePolicy implements ClientSubmitGuard.WritePolicy {

    static final String HOLDER_REFUSED = "A person who holds authorizations can only be moved to another account by a super admin";
    static final String UNNAMED_REFUSED = "People are moved to another account one at a time, by id";

    /** Whether this write moves a person to another account. */
    static boolean isAccountMove(ProtectedEntityWriteRegistry.WriteRequest write) {
        return write != null
               && "Person".equals(write.entityName())
               && write.verb() == ProtectedEntityWriteRegistry.WriteVerb.UPDATE
               && write.writtenFields() != null
               && Arrays.asList(write.writtenFields()).contains("frontendAccount");
    }

    /** Whether the move names its one person by id; otherwise nobody can say who it moves. */
    static boolean namesItsPerson(ProtectedEntityWriteRegistry.WriteRequest write) {
        return Numbers.toLong(write.targetId()) != null;
    }

    /**
     * The refusal decided without a lookup: a move of unnamed people by a caller with no person of their own. Null
     * otherwise. Package-private, like the rest of the decision, so scripts/checks/client-write-guard can check it
     * without a running server.
     */
    static String refusalWithoutLookup(ProtectedEntityWriteRegistry.WriteRequest write, Object userId) {
        if (!isAccountMove(write) || namesItsPerson(write))
            return null;
        return GrantTableWritePolicy.callerPersonId(userId) == null ? UNNAMED_REFUSED : null;
    }

    /** The decision once the lookups are in: whether the moved person holds a grant, and whether the caller is a super admin. */
    static String refusalAfterLookup(ProtectedEntityWriteRegistry.WriteRequest write, boolean targetHoldsGrant, boolean callerIsSuperAdmin) {
        if (!isAccountMove(write) || callerIsSuperAdmin)
            return null;
        if (!namesItsPerson(write))
            return UNNAMED_REFUSED;
        return targetHoldsGrant ? HOLDER_REFUSED : null;
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
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        // Unnamed people are treated as holding a grant: only a super admin may move them
        Future<Boolean> targetHoldsGrant = namesItsPerson(write)
            ? holdsGrant(Numbers.toLong(write.targetId()), entityStore)
            : Future.succeededFuture(true);
        return targetHoldsGrant.compose(holds -> !holds
            ? Future.succeededFuture(refusalAfterLookup(write, false, false))
            : SuperAdminMembership.isSuperAdminPerson(GrantTableWritePolicy.callerPersonId(userId), entityStore)
                .map(superAdmin -> refusalAfterLookup(write, true, Boolean.TRUE.equals(superAdmin))));
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

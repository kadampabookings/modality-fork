package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Operation;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers "does this person hold that operation through one of their roles?", asked of the database rather than of
 * the grants pushed to a client.
 *
 * <p>Mirrors what {@code ModalityAuthorizationServerServiceProvider} pushes for a role: the operation is granted to the
 * role directly, or through the operation's group. Super-admin membership is deliberately not part of the answer —
 * callers ask {@link SuperAdminMembership} for that, and decide themselves whether it suffices.
 *
 * <p>Why not the pushed grants: a role's grants are pushed under an {@code organizationId} context, so a context-free
 * check against them never matches; and they are cached, where a revoked role should stop at once. Resolving the
 * operation first keeps the second query flat: a correlated {@code exists} is a shape the DQL parser is known to handle,
 * nested ones are not.
 *
 * @author Claude Code
 */
public final class RoleOperationMembership {

    private RoleOperationMembership() {
    }

    /**
     * Whether this person holds the operation through a role, in any organization. Operation codes are not unique (a
     * back-office and a front-office row may share one), so every operation with the code counts, whichever tier it
     * belongs to. No person, or an operation that has not been seeded, means no: fail closed.
     */
    public static Future<Boolean> holdsThroughRole(Object personId, String operationCode, EntityStore entityStore) {
        if (personId == null)
            return Future.succeededFuture(false);
        return entityStore.<Operation>executeQuery("select group.id from Operation where operationCode=$1", operationCode)
            .compose(operations -> {
                if (operations.isEmpty()) // the operation has not been seeded yet => nobody holds it
                    return Future.succeededFuture(false);
                List<Future<Boolean>> answers = new ArrayList<>(operations.size());
                for (Operation operation : operations)
                    answers.add(entityStore.executeQuery(
                            "select AuthorizationRoleOperation ro where (ro.operation=$1 or ro.operationGroup=$2)"
                            + " and exists(select AuthorizationOrganizationUserAccess ua where ua.role=ro.role and ua.user=$3) limit 1",
                            operation.getPrimaryKey(), Entities.getPrimaryKey(operation.getGroupId()), personId)
                        .map(roleOperations -> !roleOperations.isEmpty()));
                return Future.all(new ArrayList<>(answers))
                    .map(composite -> {
                        for (int i = 0; i < answers.size(); i++)
                            if (Boolean.TRUE.equals(composite.resultAt(i)))
                                return true;
                        return false;
                    });
            });
    }
}

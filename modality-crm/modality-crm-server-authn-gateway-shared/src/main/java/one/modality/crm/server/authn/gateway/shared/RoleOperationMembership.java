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
        // Held anywhere: the caller's access row need only exist, with no organization or event to match.
        return anyRoleHolding(operationCode, "", entityStore, personId);
    }

    /**
     * The same question asked about a TARGET: does this person hold the operation in the organization —
     * and, where the row has one, the event — that owns the row being acted on?
     *
     * <p><b>Why an unscoped answer is not good enough for an action on a row.</b> {@link #holdsThroughRole}
     * answers "does this person hold it at all", which is the right question for a privilege that is about
     * the caller (may you view as a customer) and the wrong one for a privilege exercised ON something
     * (may you send THIS booking's letter). Without the scope, a manager granted the operation for one
     * centre holds it over every other centre's rows, which is the ownership check these guards exist to
     * add — the same reason {@link RouteGrantMembership#mayReachRoute} takes a target and its "anywhere"
     * sibling is used only where the target genuinely has no owner.
     *
     * <p>No organization means no: a row whose owner cannot be resolved is not a row anybody holds a
     * scoped grant over, and guessing "then anywhere will do" is how the fence gets dropped quietly.
     *
     * @param organizationId the organization owning the target row — the grant must be held THERE
     * @param eventId        the event owning it, or null when it belongs to none
     */
    public static Future<Boolean> holdsThroughRoleFor(Object personId, String operationCode,
                                                      Object organizationId, Object eventId, EntityStore entityStore) {
        if (personId == null || organizationId == null)
            return Future.succeededFuture(false);
        // The grant's own scope: this organization, and either the whole of it or this event.
        return anyRoleHolding(operationCode,
            " and ua.organization=$4 and (ua.event=null or ua.event=$5)",
            entityStore, personId, organizationId, eventId);
    }

    /**
     * Whether any operation with this code is held by the person under {@code scopeClause}.
     *
     * <p>The two public questions differ ONLY in that clause, and they are written through one body for the
     * reason {@link RouteGrantMembership} records about its own pair: written apart, they drifted, and the
     * unscoped one silently lost a fence. One body, and the difference between them readable in one line.
     *
     * <p>Operation codes are not unique (a back-office and a front-office row may share one), so every
     * operation with the code counts, whichever tier it belongs to. An operation that has not been seeded
     * means no: fail closed.
     */
    private static Future<Boolean> anyRoleHolding(String operationCode, String scopeClause,
                                                  EntityStore entityStore, Object... scopeParameters) {
        return entityStore.<Operation>executeQuery("select group.id from Operation where operationCode=$1", operationCode)
            .compose(operations -> {
                if (operations.isEmpty()) // the operation has not been seeded yet => nobody holds it
                    return Future.succeededFuture(false);
                List<Future<Boolean>> answers = new ArrayList<>(operations.size());
                for (Operation operation : operations) {
                    Object[] parameters = new Object[2 + scopeParameters.length];
                    parameters[0] = operation.getPrimaryKey();
                    parameters[1] = Entities.getPrimaryKey(operation.getGroupId());
                    System.arraycopy(scopeParameters, 0, parameters, 2, scopeParameters.length);
                    answers.add(entityStore.executeQuery(
                            "select AuthorizationRoleOperation ro where (ro.operation=$1 or ro.operationGroup=$2)"
                            + " and exists(select AuthorizationOrganizationUserAccess ua where ua.role=ro.role and ua.user=$3"
                            + scopeClause + ") limit 1",
                            parameters)
                        .map(roleOperations -> !roleOperations.isEmpty()));
                }
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

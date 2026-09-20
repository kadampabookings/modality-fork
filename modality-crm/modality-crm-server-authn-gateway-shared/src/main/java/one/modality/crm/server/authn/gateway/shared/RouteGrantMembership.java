package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Strings;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Operation;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers "may this person reach that back-office screen?", asked of the database rather than of the grants
 * pushed to a client.
 *
 * <p>It exists for the endpoints that replace a client's raw SQL. A back-office screen guards itself with
 * {@code requireRoute('/rooms')}, and that is a client-side check on a pushed grant: it decides what to draw,
 * not what the server will do. An endpoint doing the same work has to ask the same question itself, and the
 * honest server-side form of "may they reach /rooms" is "do they hold an operation whose grant route covers
 * it" — the very rule {@code ModalityAuthorizationServerServiceProvider} turns into the client's
 * {@code grant route:} lines.
 *
 * <p><b>Why a route and not an operation code.</b> Naming a code here would pin the endpoint to one row of a
 * table that differs between production and staging, and that an administrator may rename. The route is what
 * the screen is, and it is what the operation row already carries beside its code.
 *
 * <p>Wildcards are honoured as the client honours them: {@code *} covers everything, and a trailing {@code *}
 * covers a prefix ({@code /admin*} covers {@code /admin/roles}). Nothing else is pattern-matched — an exact
 * route matches exactly, so a new screen cannot be reached by an old grant that merely looks similar.
 *
 * @author Claude Code
 */
public final class RouteGrantMembership {

    private RouteGrantMembership() {
    }

    /**
     * Whether this person may reach {@code route} FOR THIS TARGET — the organization that owns the row
     * being acted on, and the event when the row belongs to one.
     *
     * <p><b>The scope is the point, not a refinement of it.</b> A grant is held per organization
     * ({@code AuthorizationOrganizationUserAccess}), and some are narrowed further to one event. The client
     * honours that — {@code isRouteGranted(path, orgId, eventId)} only applies a section whose context
     * matches what is selected — so a check that asked "may they reach /rooms-setup anywhere" would let a
     * room-setup manager of one centre delete another centre's rooms. Resource ids are small and sequential;
     * that is not a theoretical distance.
     *
     * <p>An event-scoped grant does not cover a row that belongs to no event: {@code eventId} null means
     * only organization-wide rows count. Fails closed on everything — no person, no matching grant, a query
     * that cannot be answered. A super administrator is NOT covered here: callers ask
     * {@link SuperAdminMembership} for that and decide themselves whether it suffices, exactly as they do
     * for {@link RoleOperationMembership}.
     *
     * @param organizationId the organization owning the target row; without one there is nothing to scope
     *                       the grant to, so the answer is no
     * @param eventId        the event owning the target row, or null when it belongs to none
     */
    public static Future<Boolean> mayReachRoute(Object personId, String route, Object organizationId, Object eventId, EntityStore entityStore) {
        if (personId == null || Strings.isEmpty(route) || organizationId == null)
            return Future.succeededFuture(false);
        // Every operation that grants a route at all: a small table, read once, and filtering in Java keeps the
        // wildcard rule in ONE place — the same place the client's is written down — rather than in SQL LIKE
        // patterns that would have to be kept in step with it.
        return entityStore.<Operation>executeQuery("select group.id,grantRoute from Operation where grantRoute!=null")
            .compose(operations -> {
                List<Operation> covering = new ArrayList<>();
                for (Operation operation : operations)
                    if (grantCoversRoute(operation.getGrantRoute(), route))
                        covering.add(operation);
                if (covering.isEmpty()) // no operation grants this screen to anybody
                    return Future.succeededFuture(false);
                List<Future<Boolean>> answers = new ArrayList<>(covering.size());
                for (Operation operation : covering)
                    answers.add(entityStore.executeQuery(
                            "select AuthorizationRoleOperation ro where (ro.operation=$1 or ro.operationGroup=$2)"
                            + " and exists(select AuthorizationOrganizationUserAccess ua where ua.role=ro.role and ua.user=$3"
                            // The grant's own scope: this organization, and either the whole of it or this event
                            + " and ua.organization=$4 and (ua.event=null or ua.event=$5)) limit 1",
                            operation.getPrimaryKey(), Entities.getPrimaryKey(operation.getGroupId()), personId,
                            organizationId, eventId)
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

    /**
     * Whether one {@code grant route:} value covers a route — the client's rule, server-side.
     *
     * <p>Exact, or a trailing {@code *} over a prefix. A bare {@code *} covers everything, which is what a
     * super administrator's grant looks like.
     */
    static boolean grantCoversRoute(String grantRoute, String route) {
        if (Strings.isEmpty(grantRoute))
            return false;
        if ("*".equals(grantRoute))
            return true;
        if (grantRoute.endsWith("*"))
            return route.startsWith(grantRoute.substring(0, grantRoute.length() - 1));
        return grantRoute.equals(route);
    }
}

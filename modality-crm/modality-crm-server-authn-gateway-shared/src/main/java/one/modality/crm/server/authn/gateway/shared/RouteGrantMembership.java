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
        // The grant's own scope: this organization, and either the whole of it or this event.
        String scopeClause =
            " and exists(select AuthorizationOrganizationUserAccess ua where ua.role=ro.role and ua.user=$3"
            + " and ua.organization=$4 and (ua.event=null or ua.event=$5))";
        return anyGrantCovering(route, scopeClause, entityStore, personId, organizationId, eventId);
    }

    /**
     * Whether any operation granting {@code route} is held by this person under {@code scopeClause}.
     *
     * <p>The two public questions differ ONLY in that clause, and they drifted the first time they were
     * written apart: the unscoped one silently dropped the event fence as well as the organization
     * fence. One body, one place to change, and the difference between them readable in one line.
     *
     * <p>Every operation that grants a route at all is read first: a small table, read once, and
     * filtering in Java keeps the wildcard rule in ONE place — the same place the client's is written
     * down — rather than in SQL LIKE patterns that would have to be kept in step with it.
     */
    private static Future<Boolean> anyGrantCovering(String route, String scopeClause,
                                                    EntityStore entityStore, Object... scopeParameters) {
        return entityStore.<Operation>executeQuery("select group.id,grantRoute from Operation where grantRoute!=null")
            .compose(operations -> {
                List<Operation> covering = new ArrayList<>();
                for (Operation operation : operations)
                    if (grantCoversRoute(operation.getGrantRoute(), route))
                        covering.add(operation);
                if (covering.isEmpty()) // no operation grants this screen to anybody
                    return Future.succeededFuture(false);
                return answerFrom(covering, scopeClause, entityStore, scopeParameters);
            });
    }

    /**
     * The same question with no organization to scope it to: may this person reach {@code route}
     * ANYWHERE?
     *
     * <p><b>Weaker than {@link #mayReachRoute}, and only for a target that has no organization to be
     * scoped to.</b> A {@code person} is the case this exists for. Its {@code organization_id} is the
     * Kadampa centre the member picked for themselves — often null, and changed by them at will — so it
     * says nothing about who may edit the row: scoping by it would put a member beyond their own
     * centre's reach the moment they chose another, and put every member with no centre beyond
     * everyone's.
     *
     * <p>Nor is this weaker than the screen it guards. The back office checks
     * {@code isRouteGranted(path, selectedOrgId, selectedEventId)} — the organization picked in the
     * SIDEBAR, not one belonging to the row — and then lets that screen read and write every person in
     * the database. So a caller who holds the grant in any organization can already reach every row
     * through the client; asking for it "anywhere" is the same question the client asks, and refusing
     * more narrowly here would refuse work the screen legitimately does while stopping nothing.
     *
     * <p><b>Use {@link #mayReachRoute} wherever a target HAS an organization</b> — a resource, a room, a
     * resident's centre. The name is deliberately awkward so that choosing it is a decision.
     */
    public static Future<Boolean> mayReachRouteAnywhere(Object personId, String route, EntityStore entityStore) {
        if (personId == null || Strings.isEmpty(route))
            return Future.succeededFuture(false);
        // Organization-WIDE grants only. Dropping the organization clause is the whole point here; the
        // event clause is not, and dropping it too was a bug the comment did not admit to — the client
        // DOES honour event scope (isRouteGranted(path, orgId, eventId)), so somebody granted
        // /customers for one event, who sees the screen only while that event is selected, would have
        // passed this unconditionally and reached every person row there is. `ua.event=null` is also
        // what mayReachRoute means by a null eventId: a row belonging to no event needs a grant that
        // belongs to no event, and a person belongs to none.
        return anyGrantCovering(route,
            " and exists(select AuthorizationOrganizationUserAccess ua where ua.role=ro.role and ua.user=$3"
            + " and ua.event=null)",
            entityStore, personId);
    }

    /**
     * Asks each covering operation whether this person holds it under the scope clause, and ORs them.
     *
     * <p>{@code scopeParameters} is what that clause reads, from {@code $3} on, and each caller passes
     * exactly as many as its own clause mentions — binding a parameter a statement does not reference
     * is not free, it is a driver error.
     */
    private static Future<Boolean> answerFrom(List<Operation> covering, String scopeClause,
                                              EntityStore entityStore, Object... scopeParameters) {
        List<Future<Boolean>> answers = new ArrayList<>(covering.size());
        for (Operation operation : covering) {
            Object[] parameters = new Object[2 + scopeParameters.length];
            parameters[0] = operation.getPrimaryKey();
            parameters[1] = Entities.getPrimaryKey(operation.getGroupId());
            System.arraycopy(scopeParameters, 0, parameters, 2, scopeParameters.length);
            answers.add(entityStore.executeQuery(
                    "select AuthorizationRoleOperation ro where (ro.operation=$1 or ro.operationGroup=$2)"
                    + scopeClause + " limit 1",
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

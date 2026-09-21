package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.function.Supplier;

/**
 * The check an endpoint makes before doing a screen's work: may this caller reach that screen?
 *
 * <p>A screen guards itself with {@code requireRoute('/program')}, and that decides what to DRAW. An
 * endpoint has to decide what to DO, and cannot take the client's word for it — the grants it checked were
 * pushed to it, and a caller that never runs the screen's code can send the same message. So the question is
 * asked again here, of the database ({@link RouteGrantMembership}), and a super administrator passes it the
 * way they pass every other membership check in this codebase.
 *
 * <p><b>Asked about the TARGET, not merely about the caller.</b> The grant is held per organization, and
 * sometimes per event, so the endpoint resolves what the row belongs to and passes it in. Without that, a
 * manager of one centre holds a grant that reaches every other centre's rows — which is the whole of the
 * ownership check these endpoints exist to add.
 *
 * <p><b>And on a session this server established</b>, not an identity a caller merely asserted. Until the
 * identity-token flip is on — it is off in production — a caller with no token may claim any principal, and
 * the only check that runs is that the claimed person and account exist. Existence is not identity. The
 * endpoints behind this delete rows nobody gets back, so they ask for the same fence the account controls
 * ask for.
 *
 * <p>A support view is refused outright: it is staff looking at a customer's account, with the VIEWED
 * member's person id in its principal, so every check below would be asked about the wrong person. A guest
 * carries a different principal type and is refused by the same test.
 *
 * @author Claude Code
 */
public final class RouteAccessGuard {

    private RouteAccessGuard() {
    }

    /** What every refusal here says, whatever its reason — a caller learns that it was refused, and no more. */
    public static <T> Future<T> refused() {
        return Future.failedFuture("This operation is not available to you");
    }

    /**
     * Runs {@code work} when the caller may reach {@code route} for a target owned by this organization
     * (and event, where it has one), and refuses otherwise.
     *
     * <p>MUST be called on the caller's thread: it reads the principal and the session family before its
     * first async step, because {@link ThreadLocalStateHolder} is restored as soon as the synchronous part
     * of the call returns.
     *
     * @param route          the screen's path, as the {@code Operation.grantRoute} column spells it (for
     *                       example {@code /program} or {@code /rooms-setup})
     * @param organizationId the organization owning the row being acted on — the caller's grant must be
     *                       held THERE, not merely somewhere
     * @param eventId        the event owning that row, or null when it belongs to none
     * @param work           what to do once the caller has been allowed — not started otherwise
     */
    public static <T> Future<T> whenCallerMayReach(String route, Object organizationId, Object eventId, Supplier<Future<T>> work) {
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        boolean verifiedSession = StateAccessor.getSessionFamilyId(state) != null;
        if (!(userId instanceof ModalityUserPrincipal principal) || principal.isSupportView() || !verifiedSession)
            return refused();
        Object personId = principal.getUserPersonId();
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return RouteGrantMembership.mayReachRoute(personId, route, organizationId, eventId, entityStore)
            .compose(mayReach -> Boolean.TRUE.equals(mayReach)
                ? Future.succeededFuture(true)
                // A super administrator holds no route grant of their own: the grant provider gives them
                // "route:*" as a branch of its own rather than as an operation row
                : SuperAdminMembership.isSuperAdminPerson(personId, entityStore))
            .compose(allowed -> Boolean.TRUE.equals(allowed) ? work.get() : refused());
    }

    /**
     * The same, for a target that has no organization to be scoped to — see
     * {@link RouteGrantMembership#mayReachRouteAnywhere} for when that is true and why it is not a
     * loophole.
     *
     * <p>In short: a {@code person} has no owning organization, the back office's own guard scopes to
     * the sidebar's selection rather than to the row, and the screen then reads and writes every person
     * there is. Asking "anywhere" is the same question the client asks; asking narrower would refuse
     * legitimate work and stop nothing.
     *
     * <p>Everything else is unchanged, and it is the everything else that carries the weight here: a
     * support view is refused, a guest and an anonymous caller are refused, and the session must be one
     * this server established. Before these endpoints, ANY of them could write any person row directly.
     */
    public static <T> Future<T> whenCallerMayReachAnywhere(String route, Supplier<Future<T>> work) {
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        boolean verifiedSession = StateAccessor.getSessionFamilyId(state) != null;
        if (!(userId instanceof ModalityUserPrincipal principal) || principal.isSupportView() || !verifiedSession)
            return refused();
        Object personId = principal.getUserPersonId();
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return RouteGrantMembership.mayReachRouteAnywhere(personId, route, entityStore)
            .compose(mayReach -> Boolean.TRUE.equals(mayReach)
                ? Future.succeededFuture(true)
                : SuperAdminMembership.isSuperAdminPerson(personId, entityStore))
            .compose(allowed -> Boolean.TRUE.equals(allowed) ? work.get() : refused());
    }
}

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

    /**
     * Whether this session was OPENED for the back office, as its token says.
     *
     * <p>The point is what it refuses: a session that claimed the front office at login — skipping the
     * back-office account check, the three-hour tier and the second factor — and then claimed the back
     * office afterwards to come here. The {@code backoffice} flag is re-sent on every message and can
     * differ between any two of them, so reading it here would ask the caller to mark their own homework.
     * The audience was decided when a credential was checked and signed into the token, so a caller
     * claiming back-office privileges has necessarily accepted the back office's entry conditions.
     *
     * <p>Be exact about what that proves: not that the caller IS the back office — no web client can
     * prove that, see SessionAudience — but that whoever holds this session passed what a back-office
     * login demands. That is the bar worth setting, and the one that can be met.
     *
     * <p>Null refuses. It means nothing verified on this message, which the family check above already
     * refuses; kept explicit so this reads correctly if ever called on its own.
     *
     * <p><b>Both back offices report themselves truthfully today, and one of them does so by accident.</b>
     * The React back office sets it (BusProvider backoffice={true}); the legacy WebFX/GWT one relies on
     * Meta.getBackoffice() falling back to matching "backoffice" in its module name, which
     * kbs-backoffice-application-gwt happens to contain. Rename that module and every legacy session
     * silently becomes a front-office one, refused here, with nothing failing until staff cannot work.
     */
    private static boolean openedForTheBackOffice(Object state) {
        return Boolean.TRUE.equals(StateAccessor.getVerifiedBackoffice(state));
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
    /**
     * The person behind a caller who has cleared every fence that does not depend on the target — or null,
     * which every caller of this must read as a refusal.
     *
     * <p>A real principal, a session this server established, not a support view, and a token whose audience
     * says back office. It is the cheap half of the guards below, split out for two reasons: it was written
     * inline in each of them and four copies of a fence drift, and an endpoint whose scope has to be read
     * from the database needs to refuse an anonymous caller BEFORE doing that read rather than after.
     *
     * <p>A null person id is a refusal here rather than a value passed on, which is what the membership
     * queries already did with it — they answer false for a null person — so nothing downstream changes.
     */
    public static Object backOfficeCallerPersonId(Object state) {
        Object userId = StateAccessor.getUserId(state);
        if (!(userId instanceof ModalityUserPrincipal principal) || principal.isSupportView()
            || StateAccessor.getSessionFamilyId(state) == null || !openedForTheBackOffice(state))
            return null;
        return principal.getUserPersonId();
    }

    public static <T> Future<T> whenCallerMayReach(String route, Object organizationId, Object eventId, Supplier<Future<T>> work) {
        Object personId = backOfficeCallerPersonId(ThreadLocalStateHolder.getThreadLocalState());
        if (personId == null)
            return refused();
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
        Object personId = backOfficeCallerPersonId(ThreadLocalStateHolder.getThreadLocalState());
        if (personId == null)
            return refused();
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return RouteGrantMembership.mayReachRouteAnywhere(personId, route, entityStore)
            .compose(mayReach -> Boolean.TRUE.equals(mayReach)
                ? Future.succeededFuture(true)
                : SuperAdminMembership.isSuperAdminPerson(personId, entityStore))
            .compose(allowed -> Boolean.TRUE.equals(allowed) ? work.get() : refused());
    }

    /**
     * Runs {@code work} when the caller HOLDS an operation for this target, and refuses otherwise — the
     * same fences as the route guards above, asked of an operation code rather than a screen's path.
     *
     * <p><b>For an action that is not a page.</b> Most grants here are "may you open this screen", spelled
     * as {@code Operation.grantRoute}. A few are "may you do this thing": they carry no route and gate a
     * control rather than a page — {@code EditLetterProperties}, {@code ViewAsCustomer} and
     * {@code ViewAsBackOfficeUser} are the precedents, seeded by V0106 with no {@code grant_route}
     * precisely because "they gate a control, not a page". This is the server-side guard for that shape,
     * and the first one: before it, such an operation was checked only by the client that drew the control.
     *
     * <p><b>Scoped to the target, like {@link #whenCallerMayReach} and unlike
     * {@link RoleOperationMembership#holdsThroughRole}.</b> An action performed ON a row has an owner, so
     * the grant must be held THERE. The unscoped question suits a privilege that is about the caller
     * (may you view as a customer); it does not suit one exercised on somebody's booking, where it would
     * let a grant held for one centre act on every other centre's rows.
     *
     * <p>MUST be called on the caller's thread, for the reason {@link #whenCallerMayReach} gives.
     *
     * @param operationCode  the {@code operation.operation_code} the action requires
     * @param organizationId the organization owning the row being acted on
     * @param eventId        the event owning that row, or null when it belongs to none
     */
    public static <T> Future<T> whenCallerHoldsOperation(String operationCode, Object organizationId, Object eventId,
                                                         Supplier<Future<T>> work) {
        return whenCallerHoldsOperation(ThreadLocalStateHolder.getThreadLocalState(), operationCode,
            organizationId, eventId, work);
    }

    /**
     * The same, for a caller whose state was read earlier — and it has to be, for any target whose owner
     * must be looked up before the grant can be scoped to it.
     *
     * <p><b>Why this overload exists.</b> The guards above read the thread-local themselves, which works
     * when the scope arrives in the call (a residency names its centre) and does NOT work when the scope
     * has to be read from the database first: by the time that query answers, the thread-local has been
     * restored and there is no caller left to read. Taking the organization from the CLIENT instead would
     * close the gap in the wrong direction — a caller would name an organization they hold the grant in
     * while acting on a row belonging to another, which is the confused deputy this scoping exists to
     * stop. So the endpoint captures the state synchronously, resolves the row's owner, and hands both
     * here.
     *
     * @param state the caller's state, captured on the caller's own thread before the first async step
     */
    public static <T> Future<T> whenCallerHoldsOperation(Object state, String operationCode,
                                                         Object organizationId, Object eventId,
                                                         Supplier<Future<T>> work) {
        Object personId = backOfficeCallerPersonId(state);
        if (personId == null)
            return refused();
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return RoleOperationMembership.holdsThroughRoleFor(personId, operationCode, organizationId, eventId, entityStore)
            .compose(holds -> Boolean.TRUE.equals(holds)
                ? Future.succeededFuture(true)
                // A super administrator holds no operation row of their own, exactly as above
                : SuperAdminMembership.isSuperAdminPerson(personId, entityStore))
            .compose(allowed -> Boolean.TRUE.equals(allowed) ? work.get() : refused());
    }
}

package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.function.Function;

/**
 * Who may touch the personal-data removal at all: a super administrator, on a back-office session this server
 * established, looking through their own eyes. Asked again on EVERY call of all three endpoints.
 *
 * <p>The removal is irreversible — the only way back is a point-in-time restore of the whole database — and it
 * acts on every booking of an event, whichever organization it belongs to. The lifecycle page sits under the
 * Super Admin tile because only that audience can honestly see all events, and this is the server saying the
 * same thing rather than trusting the page to have said it. The checks, in order:
 *
 * <ol>
 *   <li><b>Not a support view.</b> A support view carries the VIEWED member's person id in its principal, so
 *       every check after this one would be asked about the wrong person; the agent behind it acts through
 *       their own session or not at all. Asked first only so the log can name the agent: the next check
 *       refuses a support view too.</li>
 *   <li><b>A back-office caller</b>, as {@link RouteAccessGuard#backOfficeCallerPersonId} — the fence every
 *       back-office endpoint stands behind — decides it: a registered user (a guest principal and an
 *       anonymous caller are refused), on a session this server established (a session family, not an
 *       identity a caller merely asserted: until the identity-token flip is on, a caller with no token may
 *       claim any principal), whose token says it was opened for the back office (so whoever holds it passed
 *       what a back-office login demands), and naming a person.</li>
 *   <li><b>Super-administrator membership</b>, read from the database for that person
 *       ({@link SuperAdminMembership}). It is NOT an operation code and NOT a route grant, so nothing in the
 *       role and grant tables can delegate it — deliberately: no route grant is accepted here, not even
 *       {@code /superadmin}'s, whose page guard is only what decides what the screen DRAWS.</li>
 * </ol>
 *
 * <p>Every refusal is {@link RouteAccessGuard#refused()}: the caller learns that it was refused, and no more —
 * in particular not which check said no. The log says which, by person id only (never an email): it ships to
 * aggregation.
 *
 * <p>The caller's state is read on the CALLING thread, synchronously, before the first asynchronous step:
 * {@link ThreadLocalStateHolder} is restored as soon as the synchronous part of the bus call returns.
 *
 * @author Claude Code
 */
final class EventErasureAccess {

    private EventErasureAccess() {
    }

    private static final String LOG_PREFIX = "[event-erasure] ";

    /**
     * The super administrator a call was allowed for.
     *
     * @param personId their person id, normalised to an Integer (a principal decoded from the wire may carry a
     *                 Byte or a Long for the same person) — written into the run ledger and the history
     *                 markers, and named in the log
     */
    record Caller(Integer personId) {
    }

    /**
     * Runs {@code work} for a super administrator and refuses everybody else. MUST be called on the caller's
     * thread (see the class comment).
     *
     * @param operation which endpoint is asking, for the log line of a refusal
     * @param work      what to do once allowed — not started otherwise
     */
    static <T> Future<T> whenSuperAdmin(String operation, Function<Caller, Future<T>> work) {
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        ModalityUserPrincipal principal = userId instanceof ModalityUserPrincipal p ? p : null;
        if (principal != null && principal.isSupportView())
            // The agent's id, not the viewed member's: the member did not ask for anything
            return refuse(operation, "support view (agent person " + principal.getSupportAgentPersonId() + ")");
        // The decision is the shared fence's; the log line only says whose call it was
        Object callerPersonId = RouteAccessGuard.backOfficeCallerPersonId(state);
        if (callerPersonId == null || principal == null)
            return refuse(operation, principal == null ? "no signed-in user"
                : "person " + principal.getUserPersonId() + " is not on a verified back-office session");
        Integer personId = Numbers.toInteger(callerPersonId);
        if (personId == null)
            return refuse(operation, "the principal names no person");
        Future<Boolean> membership;
        try {
            membership = SuperAdminMembership.isSuperAdmin(principal, DataSourceModelService.getDefaultDataSourceModel());
        } catch (RuntimeException e) {
            membership = Future.failedFuture(e);
        }
        return membership
            // A lookup that failed is not a yes: fail closed, and keep the database's words in the log
            .recover(cause -> {
                Console.log(LOG_PREFIX + operation + ": the super-administrator check failed for person " + personId
                            + " (" + (cause == null ? null : cause.getMessage()) + ")");
                return Future.succeededFuture(false);
            })
            .compose(isSuperAdmin -> Boolean.TRUE.equals(isSuperAdmin)
                ? work.apply(new Caller(personId))
                : refuse(operation, "person " + personId + " is not a super administrator"));
    }

    private static <T> Future<T> refuse(String operation, String why) {
        Console.log(LOG_PREFIX + operation + " refused: " + why);
        return RouteAccessGuard.refused();
    }
}

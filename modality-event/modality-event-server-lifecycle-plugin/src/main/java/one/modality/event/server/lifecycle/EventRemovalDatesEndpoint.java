package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The date each of an organisation's events had its guests' personal data removed — for the Registration › Event
 * status page, whose audience cannot read the removal record itself.
 *
 * <p><b>Why.</b> {@code event_erasure_run} (V0146) is read only through the super-administrator endpoints. Without
 * these dates, a CLOSED event whose data was removed by date would look "due for removal" to Registration, and its
 * Reopen button would be offered — and then refused by V0147 ({@code EVENT_DATA_REMOVED_STAYS_CLOSED}).
 *
 * <p>The argument is {@code [organizationId]} (the id bare is accepted too), a positive integer or a digit string.
 * The reply is a JSON string,
 * {@code {"retentionMonths":n,"removals":[{"eventId":n,"removedOn":"YYYY-MM-DD","changedSince":bool}]}}: per event of
 * that organisation, the London date of its last succeeded commit, and whether its bookings changed since
 * ({@link EventErasureLedger#REMOVAL_DATES_SQL}). Dates, ids and booleans only — never who ran a removal, its error
 * text or its grid.
 *
 * <p><b>Who.</b> Anybody who may reach {@code /registration} in THAT organisation, on a verified back-office session
 * ({@link RouteAccessGuard#whenCallerMayReach}) — the same audience as the page. Called on the bus thread, because the
 * guard reads the caller from the thread-local. A malformed id is refused like a forbidden one, before anything is
 * read: there is no organisation to scope the grant to.
 *
 * <p><b>Failures.</b> {@link RouteAccessGuard#refused()} for a refusal; {@link EventErasureLedger#REMOVAL_DATES_FAILED}
 * for anything else — a failed read, and a grant lookup that failed rather than answered. The database's words stay
 * in the log. The page treats any failure as "removal history unavailable" and is never blocked by it.
 *
 * @author Claude Code
 */
public final class EventRemovalDatesEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.EVENT_REMOVAL_DATES. */
    public static final String EVENT_REMOVAL_DATES_ADDRESS = "modality/service/event/erasure/removalDates";

    /** The page's route: whoever may open Registration › Event status may read its dates. */
    static final String REGISTRATION_ROUTE = "/registration";

    private static final String LOG_PREFIX = "[event-erasure] ";

    public EventRemovalDatesEndpoint() {
        super(EVENT_REMOVAL_DATES_ADDRESS, argument -> removalDates(argument, EventErasureLedger::removalDatesJson));
    }

    /**
     * How the endpoint asks whether the caller may reach a route in an organisation:
     * {@link RouteAccessGuard#whenCallerMayReach} with no event, in production. A seam so a check can make the
     * lookup fail without a database.
     */
    @FunctionalInterface
    interface RouteGuard {
        Future<String> whenCallerMayReach(String route, Integer organizationId, Supplier<Future<String>> work);
    }

    /** The real guard: organisation-wide, since the dates belong to the organisation and to no one event. */
    static final RouteGuard ROUTE_ACCESS_GUARD =
        (route, organizationId, work) -> RouteAccessGuard.whenCallerMayReach(route, organizationId, null, work);

    /** {@link #removalDates(Object, RouteGuard, Function)} with the real guard. */
    static Future<String> removalDates(Object argument, Function<Integer, Future<String>> read) {
        return removalDates(argument, ROUTE_ACCESS_GUARD, read);
    }

    /**
     * The guard, then the read. MUST run on the caller's thread: {@link RouteAccessGuard#whenCallerMayReach} reads the
     * caller there, synchronously, before its first asynchronous step.
     *
     * @param read the organisation's removal dates as JSON — not started unless the caller is allowed
     */
    static Future<String> removalDates(Object argument, RouteGuard guard, Function<Integer, Future<String>> read) {
        Integer organizationId = organizationIdOf(argument);
        if (organizationId == null) // no organisation to scope the grant to: nothing to be allowed
            return RouteAccessGuard.refused();
        Future<String> answer;
        try {
            answer = guard.whenCallerMayReach(REGISTRATION_ROUTE, organizationId, () -> read.apply(organizationId));
        } catch (RuntimeException e) {
            answer = Future.failedFuture(e);
        }
        if (answer == null)
            answer = Future.failedFuture("No answer");
        return answer.recover(cause -> {
            String message = cause == null ? null : cause.getMessage();
            // A refusal, and the read's own framed failure, pass through as they are
            if (isRefusal(message) || EventErasureLedger.REMOVAL_DATES_FAILED.equals(message))
                return Future.failedFuture(message);
            // The grant lookup itself failed: not a yes, and not the database's words either
            Console.log(LOG_PREFIX + "Removal dates for organisation " + organizationId + ": the " + REGISTRATION_ROUTE
                        + " check failed (" + message + ")");
            return Future.failedFuture(EventErasureLedger.REMOVAL_DATES_FAILED);
        });
    }

    /** A positive {@code int}, bare or as the only element of an array; null for anything else. */
    static Integer organizationIdOf(Object argument) {
        Object value = argument instanceof Object[] array ? (array.length == 1 ? array[0] : null) : argument;
        return EventErasureArguments.positiveId(value);
    }

    private static boolean isRefusal(String message) {
        return RouteAccessGuard.refused().cause().getMessage().equals(message);
    }
}

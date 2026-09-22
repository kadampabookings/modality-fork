package one.modality.event.server.lifecycle;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Creates an event by duplicating an existing one: the new event row, then its programme.
 *
 * <p>Server-side for two reasons. The programme is copied by {@code copy_event_scheduled_items()}, a raw
 * statement, and {@code ClientSubmitGuard} refuses raw statements of client origin — so a back office
 * cannot call it however it is written. And the two halves belong in ONE transaction: an event row whose
 * programme copy then failed is an empty shell carrying the name somebody just typed, sitting in the event
 * list looking like a real event. {@code duplicate_event()} (V0104) does both in one statement.
 *
 * <p>The caller must be able to reach {@code /create-event} IN THE SOURCE EVENT'S organization, checked
 * server-side against the database rather than against the grants pushed to their client, and be on a
 * session this server established. The grant is asked for organization-WIDE (no event scope): a new event
 * belongs to the organization, not to the event it was copied from, so somebody granted the screen for one
 * event only does not thereby get to create others.
 *
 * <p><b>The caller never says where the new event lands.</b> Its organization is read from the source
 * event, which is the same row the grant was checked against — so there is no argument through which one
 * centre's manager could drop an event into another centre.
 *
 * <p>The argument is {@code [sourceEventId, name, startDate]}, the start date as {@code yyyy-MM-dd}. The
 * end date is not taken from the caller: it is derived from the source event's duration, because the
 * programme copy shifts every date by the gap between the two start dates and would otherwise run past or
 * short of an end date that disagreed with it. The reply is the new event's id.
 *
 * @author Claude Code
 */
public final class DuplicateEventEndpoint extends AsyncFunctionBusCallEndpoint<Object, Integer> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.DUPLICATE_EVENT. */
    public static final String DUPLICATE_EVENT_ADDRESS = "modality/service/event/duplicateEvent";

    public DuplicateEventEndpoint() {
        super(DUPLICATE_EVENT_ADDRESS, argument -> {
            // The caller's state is read on THIS thread, before anything async, and carried into the guard
            // below — which runs once the source event's organization is known, because that is what the
            // grant is checked against.
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            Object[] arguments = argumentArray(argument);
            return EventDuplication.duplicateEvent(
                elementAt(arguments, 0), asString(elementAt(arguments, 1)), asString(elementAt(arguments, 2)),
                (organizationId, work) -> ThreadLocalStateHolder.runWithState(state,
                    () -> RouteAccessGuard.whenCallerMayReach("/create-event", organizationId, null, work)));
        });
    }

    /** The three arguments, whether they arrived as an array or (malformed) as a single value. */
    private static Object[] argumentArray(Object argument) {
        return argument instanceof Object[] array ? array : new Object[] { argument };
    }

    private static Object elementAt(Object[] arguments, int index) {
        return Arrays.length(arguments) > index ? arguments[index] : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}

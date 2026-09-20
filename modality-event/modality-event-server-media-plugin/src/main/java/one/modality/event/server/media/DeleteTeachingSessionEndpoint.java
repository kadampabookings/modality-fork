package one.modality.event.server.media;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Deletes a teaching session from the programme, with everything that references it.
 *
 * <p>Replaces the raw statements the back office's programme screen used to send: the children (its audio
 * and video ScheduledItems), their Media, every MediaConsumption of either, and the session itself, in one
 * transaction. Attendance and Mail rows are detached rather than deleted — they record what happened, and
 * removing a session from the programme does not unmake somebody's attendance.
 *
 * <p>One deliberate difference from the client path it replaces: media attached to the SESSION itself, not
 * to one of its children, is deleted too. The client cascade did not remove those, so a session holding one
 * could not be deleted at all — its flush failed on the foreign key. A session being removed cannot leave
 * its own media behind, so they go with it.
 *
 * <p>The caller must be able to reach {@code /program}, checked server-side against the database rather
 * than against the grants pushed to their client, and be on a session this server established.
 *
 * <p>The argument is the session's ScheduledItem id, alone or as the first element of an array — the bus
 * call carries a single argument, and both shapes reach this the same way. The reply is {@code true}; a
 * refusal or a row that cannot go comes back as a failure with the server's own message.
 *
 * @author Claude Code
 */
public final class DeleteTeachingSessionEndpoint extends AsyncFunctionBusCallEndpoint<Object, Boolean> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.DELETE_TEACHING_SESSION. */
    public static final String DELETE_TEACHING_SESSION_ADDRESS = "modality/service/media/deleteTeachingSession";

    public DeleteTeachingSessionEndpoint() {
        super(DELETE_TEACHING_SESSION_ADDRESS, argument -> {
            // The caller's state is read on THIS thread, before anything async, and carried into the guard
            // below — which runs once the target's own event and organization are known, because that is
            // what the grant is checked against.
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            return TeachingSessionCascade.deleteTeachingSession(firstArgument(argument),
                (organizationId, eventId, work) -> ThreadLocalStateHolder.runWithState(state,
                    () -> RouteAccessGuard.whenCallerMayReach("/program", organizationId, eventId, work)));
        });
    }

    /** The id, whether it arrived alone or as {@code [id]}. */
    private static Object firstArgument(Object argument) {
        if (argument instanceof Object[] array)
            return Arrays.length(array) > 0 ? array[0] : null;
        return argument;
    }
}

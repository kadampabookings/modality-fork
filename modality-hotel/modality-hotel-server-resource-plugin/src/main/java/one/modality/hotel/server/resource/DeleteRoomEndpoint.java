package one.modality.hotel.server.resource;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Deletes a room, with its configurations and the allocation rules that name either.
 *
 * <p>Replaces the raw statement the back office's room setup screen used to send. Three things reference a
 * room with no cascade: allocation rules (three different ways), its resource configurations, and the
 * KBS2→KBS3 migration self-reference on another resource — which is nulled, not followed, because that
 * other room is not the one being deleted.
 *
 * <p>The caller must be able to reach {@code /rooms-setup} IN THE ORGANIZATION THAT OWNS THE ROOM, checked
 * server-side, and be on a session this server established. A grant is held per organization, so without
 * that scope a room-setup manager of one centre would reach every other centre's rooms.
 *
 * <p>It also decides whether the room is still in use, because the database cannot: a booking's foreign key
 * to a room or a configuration is ON DELETE SET NULL, so deleting would not fail — it would quietly erase
 * which room that booking had. The condition therefore rides inside the delete statements themselves, and
 * the room is read back afterwards: unchanged means a booking arrived, and that is reported as a refusal
 * rather than as a delete. Residents and volunteer applications keep the usual refusing keys.
 *
 * <p>The argument is the Resource id, alone or as the first element of an array. Its configuration ids are
 * not taken from the caller: they are every configuration of that resource, which is what the screen meant.
 *
 * @author Claude Code
 */
public final class DeleteRoomEndpoint extends AsyncFunctionBusCallEndpoint<Object, Boolean> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.DELETE_ROOM. */
    public static final String DELETE_ROOM_ADDRESS = "modality/service/resource/deleteRoom";

    public DeleteRoomEndpoint() {
        super(DELETE_ROOM_ADDRESS, argument -> {
            // The caller's state is read on THIS thread and carried into the guard, which runs once the
            // room's own organization is known — a grant is held per organization, and a manager of one
            // centre must not reach another centre's rooms.
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            return RoomCascade.deleteRoom(firstArgument(argument),
                (organizationId, eventId, work) -> ThreadLocalStateHolder.runWithState(state,
                    () -> RouteAccessGuard.whenCallerMayReach("/rooms-setup", organizationId, eventId, work)));
        });
    }

    /** The id, whether it arrived alone or as {@code [id]}. */
    private static Object firstArgument(Object argument) {
        if (argument instanceof Object[] array)
            return Arrays.length(array) > 0 ? array[0] : null;
        return argument;
    }
}

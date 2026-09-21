package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Adding somebody to a centre's residents.
 *
 * <p>Argument: {@code [personId, organizationId]}.
 *
 * <p>Separate from {@link UpdateResidentEndpoint} because the STATEMENTS differ in the way that
 * matters: adding writes the centre, editing tests it. Folding them together would mean one statement
 * that sometimes trusts the client's organization and sometimes checks it, decided by a flag — and the
 * flag would be the client's.
 *
 * @author Claude Code
 */
public final class SetResidentEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.ADD_RESIDENT. */
    public static final String ADD_RESIDENT_ADDRESS = "modality/service/person/addResident";

    /** The route the back office's own router requires for these screens. */
    static final String RESIDENTS_ROUTE = "/residents";

    public SetResidentEndpoint() {
        super(ADD_RESIDENT_ADDRESS, argument -> {
            Object[] arguments = argument instanceof Object[] array ? array : null;
            if (arguments == null || arguments.length != 2)
                return RouteAccessGuard.refused();
            Object personId = arguments[0], organizationId = arguments[1];
            // On the caller's thread: person's audit triggers stamp changed_by_person_id from the
            // principal, and by the guard's first async step there is none left to read.
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            // Scoped, unlike the customer and user endpoints: a residency belongs to a centre, so the
            // question "may you reach /residents HERE" has an answer worth asking. No event scope — a
            // residency belongs to no event, and an event-scoped grant deliberately does not cover one.
            return RouteAccessGuard.whenCallerMayReach(RESIDENTS_ROUTE, organizationId, null,
                () -> StaffPersonRules.addResident(personId, organizationId, callerUserId).map(x -> (Object) x));
        });
    }
}

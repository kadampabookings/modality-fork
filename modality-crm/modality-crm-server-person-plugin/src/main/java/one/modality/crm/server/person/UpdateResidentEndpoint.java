package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Editing a resident of a centre — meals, sponsorship, room, rent, or ending the residency.
 *
 * <p>Argument: {@code [personId, organizationId, name, value, …]}.
 *
 * <p>Taking somebody off the list is not a separate operation here: it is {@code resident = false},
 * one field like any other. What makes all of them resident edits rather than person edits is the
 * centre, which the statement tests — see {@code StaffPersonRules.updateResident}.
 *
 * @author Claude Code
 */
public final class UpdateResidentEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.UPDATE_RESIDENT. */
    public static final String UPDATE_RESIDENT_ADDRESS = "modality/service/person/updateResident";

    public UpdateResidentEndpoint() {
        super(UPDATE_RESIDENT_ADDRESS, argument -> {
            Object[] arguments = argument instanceof Object[] array ? array : null;
            // A person, a centre, then an even number of pairs.
            if (arguments == null || arguments.length < 4 || arguments.length % 2 != 0)
                return RouteAccessGuard.refused();
            Object personId = arguments[0], organizationId = arguments[1];
            Object[] namesAndValues = new Object[arguments.length - 2];
            System.arraycopy(arguments, 2, namesAndValues, 0, namesAndValues.length);
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return RouteAccessGuard.whenCallerMayReach(SetResidentEndpoint.RESIDENTS_ROUTE, organizationId, null,
                () -> StaffPersonRules.updateResident(personId, organizationId, namesAndValues, callerUserId)
                    .map(x -> (Object) x));
        });
    }
}

package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * The customers screen editing a person, checked against the grant that screen requires.
 *
 * <p>Argument: {@code [personId, name, value, name, value, …]}.
 *
 * <p>Three call sites share it, because all three are the same operation with different fields: the
 * detail drawer's Save, dismissing a gender-change marker, and deprecating an address or a centre.
 *
 * <p><b>The only one of the four staff endpoints with no target scope</b>, and the one case where that
 * is honest: a person has no owning organization, and the customers list has no organization condition
 * — it is every person in the database, by design. So "may you reach /customers anywhere" is the same
 * question the screen asks. The other three all have a centre to be scoped to, and are.
 *
 * @author Claude Code
 */
public final class UpdateCustomerEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.UPDATE_CUSTOMER. */
    public static final String UPDATE_CUSTOMER_ADDRESS = "modality/service/person/updateCustomer";

    /** The route the back office's own router requires for this screen — asked again here, of the database. */
    static final String CUSTOMERS_ROUTE = "/customers";

    public UpdateCustomerEndpoint() {
        super(UPDATE_CUSTOMER_ADDRESS, argument -> {
            Object[] arguments = argument instanceof Object[] array ? array : null;
            // A person id, then an even number of pairs — one test, and an empty array fails it too.
            if (arguments == null || arguments.length % 2 != 1)
                return RouteAccessGuard.refused();
            Object personId = arguments[0];
            Object[] namesAndValues = new Object[arguments.length - 1];
            System.arraycopy(arguments, 1, namesAndValues, 0, namesAndValues.length);
            // On the caller's thread: person's audit triggers stamp changed_by_person_id from the
            // principal, and by the guard's first async step there is none left to read.
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return RouteAccessGuard.whenCallerMayReachAnywhere(CUSTOMERS_ROUTE,
                () -> StaffPersonRules.updateCustomer(personId, namesAndValues, callerUserId)
                    .map(x -> (Object) x));
        });
    }
}

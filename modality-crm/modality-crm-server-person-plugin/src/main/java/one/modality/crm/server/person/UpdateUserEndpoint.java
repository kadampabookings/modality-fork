package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * The users screen editing somebody's personal details, checked against that screen's grant AND centre.
 *
 * <p>Argument: {@code [personId, organizationId, name, value, …]}.
 *
 * <p>Separate from {@code updateCustomer} because the GRANT is separate — {@code /users} and
 * {@code /customers} are different operations, held by different people, and folding them into one
 * endpoint would let whoever holds either do the other's work.
 *
 * <p><b>And scoped, unlike the customers endpoint</b>, which is the correction a review had to make.
 * The two looked alike and are not: the users screen lists from
 * {@code AuthorizationOrganizationUserAccess where organization = $1}, so the people it reaches are
 * the handful holding a grant row at the selected centre — not every person there is. Asking only
 * "do you hold /users somewhere" turned that handful into the whole customer database.
 *
 * @author Claude Code
 */
public final class UpdateUserEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.UPDATE_USER. */
    public static final String UPDATE_USER_ADDRESS = "modality/service/person/updateUser";

    /** The route the back office's own router requires for this screen. */
    static final String USERS_ROUTE = "/users";

    public UpdateUserEndpoint() {
        super(UPDATE_USER_ADDRESS, argument -> {
            Object[] arguments = argument instanceof Object[] array ? array : null;
            // A person, a centre, then an even number of pairs.
            if (arguments == null || arguments.length < 4 || arguments.length % 2 != 0)
                return RouteAccessGuard.refused();
            Object personId = arguments[0], organizationId = arguments[1];
            Object[] namesAndValues = new Object[arguments.length - 2];
            System.arraycopy(arguments, 2, namesAndValues, 0, namesAndValues.length);
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            // No event scope: a person's grant row may be event-scoped, but the row being edited is a
            // person, which belongs to no event.
            return RouteAccessGuard.whenCallerMayReach(USERS_ROUTE, organizationId, null,
                () -> StaffPersonRules.updateUser(personId, organizationId, namesAndValues, callerUserId)
                    .map(x -> (Object) x));
        });
    }
}

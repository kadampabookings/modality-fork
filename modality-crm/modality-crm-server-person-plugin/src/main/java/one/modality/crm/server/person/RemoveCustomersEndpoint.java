package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.ArrayList;
import java.util.List;

/**
 * Soft-deleting duplicate customers — the merge dialog's other half.
 *
 * <p>Argument: {@code [personId, personId, …]}. One call and one transaction, rather than a call per
 * person: they are duplicates of each other, and half a merge cleaned up is a worse state than none.
 *
 * <p>An account OWNER is refused, which the statement says for itself. It is the same rule the member
 * endpoints carry — removing the row a sign-in resolves to leaves the account unable to reach itself —
 * and the dialog does not hit it in practice, because a merge clears {@code owner} on everything it
 * moves before the duplicates are removed.
 *
 * @author Claude Code
 */
public final class RemoveCustomersEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.REMOVE_CUSTOMERS. */
    public static final String REMOVE_CUSTOMERS_ADDRESS = "modality/service/person/removeCustomers";

    public RemoveCustomersEndpoint() {
        super(REMOVE_CUSTOMERS_ADDRESS, argument -> {
            List<Object> personIds = new ArrayList<>();
            if (argument instanceof Object[] array)
                java.util.Collections.addAll(personIds, array);
            if (personIds.isEmpty())
                return RouteAccessGuard.refused();
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return RouteAccessGuard.whenCallerMayReachAnywhere(UpdateCustomerEndpoint.CUSTOMERS_ROUTE,
                () -> AccountMergeRules.removeCustomers(personIds, callerUserId));
        });
    }
}

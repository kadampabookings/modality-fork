package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.ArrayList;
import java.util.List;

/**
 * Consolidating people onto one account: the two merge dialogs, as one server operation.
 *
 * <p>Argument: {@code [destinationAccountId, sourceAccountIds, personIds, linkToPersonId]}, where the
 * two lists are arrays and either may be empty, and the link may be null.
 *
 * <p>Under {@code /customers}, like the screen both dialogs live on, and unscoped for the same reason
 * {@code updateCustomer} is: that list has no organization condition. <b>But super-admin membership is
 * read separately</b> and passed down, because it decides a second question the guard does not ask —
 * whether somebody who holds authorizations may be moved at all.
 *
 * <p>That second rule belongs to {@code PersonAccountMovePolicy}, which judges the same move on the
 * change-set path and cannot see this one: these endpoints run "as server" and drop the client-origin
 * stamp on purpose. A rule that exists on one path and not the other is not a rule, so it is re-asked
 * here — over the whole moving set at once, rather than row by row as the policy sees it.
 *
 * @author Claude Code
 */
public final class MergeIntoAccountEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.MERGE_INTO_ACCOUNT. */
    public static final String MERGE_INTO_ACCOUNT_ADDRESS = "modality/service/person/mergeIntoAccount";

    public MergeIntoAccountEndpoint() {
        super(MERGE_INTO_ACCOUNT_ADDRESS, argument -> {
            Object[] arguments = argument instanceof Object[] array ? array : null;
            if (arguments == null || arguments.length != 4)
                return RouteAccessGuard.refused();
            Object destination = arguments[0];
            List<Object> sourceAccountIds = list(arguments[1]);
            List<Object> personIds = list(arguments[2]);
            Object linkToPersonId = arguments[3];
            // Both read on the caller's thread, before the guard's first async step: the principal for
            // the audit stamp, and the person id the super-admin lookup needs.
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            Object callerUserId = StateAccessor.getUserId(state);
            ModalityUserPrincipal principal = callerUserId instanceof ModalityUserPrincipal p ? p : null;
            return RouteAccessGuard.whenCallerMayReachAnywhere(UpdateCustomerEndpoint.CUSTOMERS_ROUTE,
                // The principal-taking overload, rather than building an EntityStore here: that pulled
                // webfx-stack-orm-entity into this module's pom and module-info for one line.
                () -> SuperAdminMembership
                    .isSuperAdmin(principal, DataSourceModelService.getDefaultDataSourceModel())
                    .compose(superAdmin -> AccountMergeRules.merge(destination, sourceAccountIds, personIds,
                        linkToPersonId, Boolean.TRUE.equals(superAdmin), callerUserId)));
        });
    }

    /** An argument that should be a list of ids, as whatever the wire produced. */
    private static List<Object> list(Object value) {
        List<Object> out = new ArrayList<>();
        if (value instanceof Object[] array)
            java.util.Collections.addAll(out, array);
        else if (value instanceof Iterable<?> iterable)
            for (Object element : iterable)
                out.add(element);
        return out;
    }
}

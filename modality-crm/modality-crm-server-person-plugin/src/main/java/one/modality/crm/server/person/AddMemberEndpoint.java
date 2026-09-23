package one.modality.crm.server.person;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Adds a person to the caller's own account, and returns their id.
 *
 * <p>The account is the caller's, from the principal, and {@code owner} is written false. Both used to
 * be the client's: an insert could name ANY account — which is the plan's account-creation note — and
 * could have set owner, which is a second way into an account rather than a member of it.
 *
 * <p>Argument: {@code [name, value, name, value, …]}, the same pairs the update takes.
 *
 * @author Claude Code
 */
public final class AddMemberEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.ADD_MEMBER. */
    public static final String ADD_MEMBER_ADDRESS = "modality/service/person/addMember";

    public AddMemberEndpoint() {
        super(ADD_MEMBER_ADDRESS, argument -> {
            Object[] namesAndValues = argument instanceof Object[] array ? array : null;
            if (namesAndValues == null || Arrays.length(namesAndValues) < 2)
                return MemberSessionGuard.refused();
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return MemberSessionGuard.whenCallerIsVerifiedMember((callerPersonId, callerAccountId) ->
                PersonDetailsRules.addMember(namesAndValues, callerAccountId, callerUserId));
        });
    }
}

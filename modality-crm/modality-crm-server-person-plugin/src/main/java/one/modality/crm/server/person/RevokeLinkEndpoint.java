package one.modality.crm.server.person;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Withdraws a link, from either side of it.
 *
 * <p>Withdrawing does NOT clear {@code accountPerson}, and that is the point of the operation rather than
 * an oversight in it: the bookings were made for that person and the recordings are theirs, so the access
 * queries keep matching and nothing already given is taken back. What stops is booking on the link again.
 * An earlier version of this feature used {@code removed = true}, which withdrew the permission by
 * deleting the record — the row vanished, and a change of mind produced a second person for one human.
 *
 * <p>Either party may withdraw: the account the link was granted from, or the person it was granted to.
 * A caller who is neither gets the same refusal as a caller naming a link that does not exist, so this
 * cannot be used to ask who is linked to whom.
 *
 * <p>The argument is the person id whose link is withdrawn, alone or as the first element of an array.
 *
 * @author Claude Code
 */
public final class RevokeLinkEndpoint extends AsyncFunctionBusCallEndpoint<Object, Boolean> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.REVOKE_LINK. */
    public static final String REVOKE_LINK_ADDRESS = "modality/service/person/revokeLink";

    public RevokeLinkEndpoint() {
        super(REVOKE_LINK_ADDRESS, argument -> {
            Object targetPersonId = firstArgument(argument);
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return MemberSessionGuard.whenCallerIsMember((personId, accountId) ->
                PersonLinkRules.revokeLink(targetPersonId, personId, accountId, callerUserId));
        });
    }

    /** The id, whether it arrived alone or as {@code [id]}. */
    private static Object firstArgument(Object argument) {
        if (argument instanceof Object[] array)
            return Arrays.length(array) > 0 ? array[0] : null;
        return argument;
    }
}

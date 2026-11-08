package one.modality.crm.server.person;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Accepts, after all, an invitation the caller declined, and makes the link it stands for.
 *
 * <p>The members page offers it on a request the person declined, for as long as the invitation would
 * still have been valid (seven days from its creation). The rules — caller is the invitee, a dated decline
 * only, the same link as an approval — are in {@link PersonLinkRules#acceptDeclinedInvitation}. A separate
 * address from approval on purpose: approval keeps refusing anything that is not pending, so the email
 * link sent with the invitation can never bring a declined one back.
 *
 * <p>The argument is {@code [invitationId, subject, body]} — the translated "approved" email the inviter
 * receives, as for approval — or the id alone.
 *
 * @author Claude Code
 */
public final class AcceptDeclinedInvitationEndpoint extends AsyncFunctionBusCallEndpoint<Object, Boolean> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.ACCEPT_DECLINED_INVITATION. */
    public static final String ACCEPT_DECLINED_INVITATION_ADDRESS = "modality/service/person/acceptDeclinedInvitation";

    public AcceptDeclinedInvitationEndpoint() {
        super(ACCEPT_DECLINED_INVITATION_ADDRESS, argument -> {
            Object[] all = argument instanceof Object[] array ? array : null;
            Object invitationId = all != null ? (Arrays.length(all) > 0 ? all[0] : null) : argument;
            Object subject = all != null && Arrays.length(all) > 1 ? all[1] : null;
            Object body = all != null && Arrays.length(all) > 2 ? all[2] : null;
            // Read on THIS thread: the thread-local does not survive the first async step.
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return MemberSessionGuard.whenCallerIsVerifiedMember((personId, accountId) ->
                PersonLinkRules.acceptDeclinedInvitation(invitationId, personId, accountId, subject, body,
                    callerUserId));
        });
    }
}

package one.modality.crm.server.person;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Records that the inviter has seen the answer to their invitation (V0157 {@code answer_seen_date}).
 *
 * <p>The rules, and why these are endpoints rather than client writes, are in {@link InvitationAnswerRules}.
 * The caller comes from the session, never from the argument; the argument is the invitation id, alone or
 * as the first element of an array.
 *
 * @author Claude Code
 */
public final class SeeInvitationAnswerEndpoint extends AsyncFunctionBusCallEndpoint<Object, Boolean> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.SEE_INVITATION_ANSWER. */
    public static final String SEE_INVITATION_ANSWER_ADDRESS = "modality/service/person/seeInvitationAnswer";

    public SeeInvitationAnswerEndpoint() {
        super(SEE_INVITATION_ANSWER_ADDRESS, argument -> {
            Object invitationId = argument instanceof Object[] array
                ? (Arrays.length(array) > 0 ? array[0] : null)
                : argument;
            // Read on THIS thread: the thread-local does not survive the first async step.
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return MemberSessionGuard.whenCallerIsVerifiedMember((personId, accountId) ->
                InvitationAnswerRules.seeAnswer(invitationId, personId, callerUserId));
        });
    }
}

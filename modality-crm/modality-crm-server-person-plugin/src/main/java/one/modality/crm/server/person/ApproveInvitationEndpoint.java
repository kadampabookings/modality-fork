package one.modality.crm.server.person;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Approves an invitation addressed to the caller, and makes the link it stands for.
 *
 * <p>Replaces two client paths that both wrote {@code person.accountPerson} directly. One is the members
 * page, which at least had somebody signed in. <b>The other is the email-link page, which had nobody:</b>
 * it approved on the strength of a token minted in a browser and readable by anyone holding the mail, so
 * the server saw an anonymous caller granting one account sight of another's bookings and recordings.
 *
 * <p>Here the proof is not a token but the session: the caller must BE the invitation's invitee. That is
 * requirement 4 of the plan's accountPerson notes, and it is why the email link now has to sign somebody
 * in before it can do anything — the link says which invitation, the session says who.
 *
 * <p>Which direction the link runs is the invitation's to say, not the caller's: {@code inviterPayer}
 * distinguishes "let me book for you" from "please manage my bookings", and they link opposite ways.
 *
 * <p>The argument is the invitation id, alone or as the first element of an array.
 *
 * @author Claude Code
 */
public final class ApproveInvitationEndpoint extends AsyncFunctionBusCallEndpoint<Object, Boolean> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.APPROVE_INVITATION. */
    public static final String APPROVE_INVITATION_ADDRESS = "modality/service/person/approveInvitation";

    public ApproveInvitationEndpoint() {
        super(APPROVE_INVITATION_ADDRESS, argument -> {
            Object invitationId = firstArgument(argument);
            // Read on THIS thread: the principal for the audit trail, and the guard's own reads, both
            // happen before any async step because the thread-local does not survive one.
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return MemberSessionGuard.whenCallerIsMember((personId, accountId) ->
                PersonLinkRules.approveInvitation(invitationId, personId, accountId, callerUserId));
        });
    }

    /** The id, whether it arrived alone or as {@code [id]}. */
    private static Object firstArgument(Object argument) {
        if (argument instanceof Object[] array)
            return Arrays.length(array) > 0 ? array[0] : null;
        return argument;
    }
}

package one.modality.crm.server.person;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Creates an invitation from the caller, and returns the token its email will carry.
 *
 * <p><b>The inviter is not an argument.</b> It is the caller's own person, taken from the principal the
 * server minted, and that single fact is what makes approving an invitation mean something: the row an
 * approval takes for proof can now only have been written by the person it names. While a client could
 * insert one, it could name anybody as inviter, approve it as itself, and take that person's bookings —
 * the proof was a row the attacker wrote.
 *
 * <p>The token is minted here too. The client used to choose it and write it into the row, which is the
 * wrong shape even where the generator is sound: the capability an email carries should not be picked by
 * the code that will later present it. Returned once, because the inviter's email needs it.
 *
 * <p>Argument: {@code [inviteePersonId, inviterPays, aliasFirstName, aliasLastName]}. {@code inviterPays}
 * true means "let me book for you", false means "please manage my bookings"; they link opposite ways, and
 * the invitation is where that direction is recorded.
 *
 * @author Claude Code
 */
public final class CreateInvitationEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.CREATE_INVITATION. */
    public static final String CREATE_INVITATION_ADDRESS = "modality/service/person/createInvitation";

    public CreateInvitationEndpoint() {
        super(CREATE_INVITATION_ADDRESS, argument -> {
            Object[] a = argument instanceof Object[] array ? array : null;
            if (a == null || Arrays.length(a) < 2)
                return MemberSessionGuard.refused();
            Object inviteeId = a[0];
            // Which direction is not a thing to guess at. Boolean.TRUE.equals() reads anything that is
            // not a Boolean as false, which silently turns "let me book for you" into "manage my
            // bookings" — the opposite account gains sight of the other. A non-Boolean is a caller
            // sending something this does not understand, and is refused rather than interpreted.
            if (!(a[1] instanceof Boolean inviterPays))
                return MemberSessionGuard.refused();
            Object aliasFirstName = Arrays.length(a) > 2 ? a[2] : null;
            Object aliasLastName = Arrays.length(a) > 3 ? a[3] : null;
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return MemberSessionGuard.whenCallerIsVerifiedMember((personId, accountId) ->
                InvitationRules.createInvitation(inviteeId, inviterPays, aliasFirstName, aliasLastName,
                    personId, callerUserId));
        });
    }
}

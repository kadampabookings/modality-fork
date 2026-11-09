package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.CallerRateLimit;

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
 * <p>Argument: {@code [invitee, inviterPays, aliasFirstName, aliasLastName, subject, body]}, where
 * {@code invitee} is a person id (a person the caller already lists) or a sign-in address. By address the
 * server finds the account holder and the caller learns nothing about them — not their id, not their
 * name: see {@link InvitationRules#createInvitationByEmail}. {@code inviterPays}
 * true means "let me book for you", false means "please manage my bookings"; they link opposite ways, and
 * the invitation is where that direction is recorded.
 *
 * @author Claude Code
 */
public final class CreateInvitationEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

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
            // The translated subject and body of the email this asks for. The server owns who receives
            // it, which account it leaves from and every url in it; the browser owns only the prose,
            // because these templates exist in every supported language and the server has no i18n for
            // them yet. See MemberMail.
            Object subject = Arrays.length(a) > 4 ? a[4] : null;
            Object body = Arrays.length(a) > 5 ? a[5] : null;
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            if (inviteeId instanceof String text) {
                String email = EmailHasAccountEndpoint.emailOf(new Object[] { text });
                if (email == null)
                    return MemberSessionGuard.refused();
                // By address this answers "is there an account?" — the question emailHasAccount answers,
                // so it is held to the same limit. The address stamp is read on the caller's thread (it is
                // gone after the first async step); the limit is spent only once the caller is known to
                // be a verified member, so anonymous calls cannot use up anyone's budget.
                String clientAddress = ThreadLocalStateHolder.getClientAddress();
                return MemberSessionGuard.whenCallerIsVerifiedMember((personId, accountId) ->
                    !CallerRateLimit.EMAIL_LOOKUP.tryAcquire(clientAddress)
                        ? Future.failedFuture("[" + EmailHasAccountEndpoint.RATE_LIMITED_KEY
                                              + "] Too many address checks, please try again later")
                        : InvitationRules.createInvitationByEmail(email, inviterPays, aliasFirstName, aliasLastName,
                            subject, body, personId, accountId, callerUserId));
            }
            return MemberSessionGuard.whenCallerIsVerifiedMember((personId, accountId) ->
                InvitationRules.createInvitation(inviteeId, inviterPays, aliasFirstName, aliasLastName,
                    subject, body, personId, callerUserId));
        });
    }
}

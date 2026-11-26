package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * "What was booked for me, in somebody else's account?" — the read half of {@link ClaimMembersEndpoint}.
 *
 * <p>Takes no argument. The reply is
 * {@code {members: [{id, firstName, lastName, accountId}], owners: [{id, firstName, lastName, email,
 * accountId}]}} — the member rows carrying this caller's own address, and the owners of the accounts
 * holding them, so the screen can say whose account each row is in and send the claim notice there.
 *
 * <h3>Why it exists, which is not convenience</h3>
 *
 * <p>The browser used to find these rows itself:
 * {@code select id,firstName,lastName,email,frontendAccount.(id,username) from Person where
 * lower(email)=$1 and owner=false and accountPerson=null and frontendAccount!=$2}. The address was the
 * caller's to choose, so that statement answers "did anyone book for <i>any</i> address, and in whose
 * account?" — an enumeration oracle over the whole {@code person} table, asked by every signed-in
 * member, unmetered. {@code PersonReadScope} closed it by scoping {@code Person} to the caller's own
 * account, and closing it is precisely what left the claim screens with nothing to show: the rows they
 * need are, by definition, in other people's accounts.
 *
 * <p>So the question moves to where the address is not an argument. {@link MemberClaimRules#offers}
 * reads it from the caller's own {@code frontend_account.username} — the address they proved control of
 * at sign-in, and one only the server ever writes. The same move, for the same reason, as
 * {@link MatchingAccountsEndpoint}: the read rule can then say "another account's person rows are not
 * readable by a member" with no carve-out, which is a rule nobody has to be told the exception to.
 *
 * <h3>What it deliberately does not do</h3>
 *
 * <p><b>It takes no ids to narrow by</b>, unlike {@code claimMembers}. There is exactly one set a caller
 * may be offered and who they are determines it entirely, so there is nothing to choose. The write half
 * accepts narrowing because a screen must be able to claim fewer rows than match — having shown fewer —
 * and that asymmetry is the point: narrowing can only subtract from a derived set, while an argument
 * here would decide what the set is.
 *
 * <p><b>It returns no member email.</b> The rows' own addresses are what the derivation runs on, never
 * something a caller is told, and no screen reads them. The one address in the reply is the account
 * owner's, which is where the notice goes and which the screen therefore has to have.
 *
 * <p><b>It is not metered</b>, where {@link MatchingAccountsEndpoint} is. That endpoint takes the
 * addresses to ask about, so each call is a fresh question and a budget is the only bound; this one
 * answers the same question every time it is asked, because the caller cannot vary the input. Repeating
 * it reveals nothing a first call did not. Its cost is a sequential scan of {@code person} —
 * {@code email} carries no index — which is what the client statement it replaces already cost, and
 * what {@code claimMembers} still costs on the write path.
 *
 * <p>Behind {@link MemberSessionGuard#whenCallerIsVerifiedMember}, the fenced door, as the write half
 * is: this tells one account that another holds a booking for them, with a name and an address attached.
 * A support view is refused there, because its principal carries the VIEWED member's person id — a
 * support agent must not be handed the claimables of the member they are helping.
 *
 * <p>No {@code callerUserId} is read here, where the write half reads one on the caller's thread for
 * {@code person}'s audit triggers. Nothing is written, so there is nothing to stamp.
 *
 * @author Claude Code
 */
public final class ClaimableMembersEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.CLAIMABLE_MEMBERS. */
    public static final String CLAIMABLE_MEMBERS_ADDRESS = "modality/service/person/claimableMembers";

    public ClaimableMembersEndpoint() {
        super(CLAIMABLE_MEMBERS_ADDRESS, argument ->
            // The argument is ignored rather than validated: there is nothing it could legitimately
            // say. A caller sending one is not refused, because refusing would make the reply depend
            // on an input that does not exist.
            MemberSessionGuard.whenCallerIsVerifiedMember((callerPersonId, callerAccountId) ->
                MemberClaimRules.offers(callerAccountId)));
    }
}

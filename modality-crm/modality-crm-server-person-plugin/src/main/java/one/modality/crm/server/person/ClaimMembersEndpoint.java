package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Claims the member rows somebody else booked for this caller's own address.
 *
 * <p>Argument: {@code [personId, …]}, or an empty list. <b>Those ids can only NARROW</b> what is
 * claimed — the server derives the real set from the caller's {@code frontend_account.username}, the
 * address they proved control of, and intersects. The browser used to run the query, read the rows
 * and write the link for each, so the set claimed was chosen by the side making the claim; now naming
 * somebody else's row claims nothing, and naming none means everything that matches.
 *
 * <p>A screen needs the narrowing because it does not always offer everything: the media pages list
 * only rows with something that will still play, and claiming every matching row from there would
 * link rows the member was never shown — whose account owners would then get no notice, because the
 * screen did not know they existed.
 *
 * <p>Behind {@link MemberSessionGuard#whenCallerIsVerifiedMember}, the fenced door, which is what that
 * door exists for: this hands one account sight of another's bookings and takes their media, so it
 * asks for a session this server established rather than an identity a caller asserted.
 *
 * <p>Returns the ids linked, because the screen sends a notice to the owner of each account a row was
 * claimed from — they did not have to approve, but their account changed.
 *
 * @author Claude Code
 */
public final class ClaimMembersEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.CLAIM_MEMBERS. */
    public static final String CLAIM_MEMBERS_ADDRESS = "modality/service/person/claimMembers";

    public ClaimMembersEndpoint() {
        super(CLAIM_MEMBERS_ADDRESS, argument -> {
            // On the caller's thread: person's audit triggers stamp changed_by_person_id from the
            // principal, and by the guard's first async step there is none left to read.
            java.util.List<Object> onlyIds = new java.util.ArrayList<>();
            if (argument instanceof Object[] array)
                java.util.Collections.addAll(onlyIds, array);
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return MemberSessionGuard.whenCallerIsVerifiedMember((callerPersonId, callerAccountId) ->
                MemberClaimRules.claim(callerPersonId, callerAccountId, onlyIds, callerUserId));
        });
    }
}

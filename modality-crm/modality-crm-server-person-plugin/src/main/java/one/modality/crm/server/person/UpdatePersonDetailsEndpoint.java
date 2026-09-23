package one.modality.crm.server.person;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Edits a person's details — the caller's own, or somebody in their account.
 *
 * <p>Replaces the profile and member forms' {@code update Person set …}. Two things that were the
 * client's to decide are now neither: which fields (PersonFields) and whose row (the principal).
 *
 * <p>Argument: {@code [personId, name, value, name, value, …]}. Pairs rather than a structured object
 * because the field list is the permission, and a list of pairs is read by a server that knows the names
 * it accepts — a name this build does not know is refused rather than dropped, so a screen and a server
 * that disagree say so instead of silently saving a form with a field missing.
 *
 * @author Claude Code
 */
public final class UpdatePersonDetailsEndpoint extends AsyncFunctionBusCallEndpoint<Object, Boolean> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.UPDATE_PERSON_DETAILS. */
    public static final String UPDATE_PERSON_DETAILS_ADDRESS = "modality/service/person/updateDetails";

    public UpdatePersonDetailsEndpoint() {
        super(UPDATE_PERSON_DETAILS_ADDRESS, argument -> {
            Object[] a = argument instanceof Object[] array ? array : null;
            if (a == null || Arrays.length(a) < 3)
                return MemberSessionGuard.refused();
            Object personId = a[0];
            Object[] namesAndValues = new Object[a.length - 1];
            System.arraycopy(a, 1, namesAndValues, 0, namesAndValues.length);
            Object callerUserId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
            return MemberSessionGuard.whenCallerIsVerifiedMember((callerPersonId, callerAccountId) ->
                PersonDetailsRules.updateDetails(personId, namesAndValues,
                    callerPersonId, callerAccountId, callerUserId));
        });
    }
}

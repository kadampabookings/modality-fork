package one.modality.crm.server.person;

import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;

/**
 * "I would like the overpayment on my cancelled booking back."
 *
 * <p>Argument: the booking's id, and nothing else. The browser used to compose the whole mail — the
 * subject, the sentence, the amount, the reply address — and insert the row itself. Every one of
 * those is a fact about the booking, so none of them was the browser's to send; and the insert is
 * what makes a client able to write the mail table at all, which is what the mail arc is closing.
 *
 * <p>Two kinds of caller reach the orders page and both see this button, so both are answered here:
 * a member, whose account decides what they may ask about, and a guest, whose proven address does.
 * Which test applies is read from the PRINCIPAL, never from the argument — a caller cannot ask to be
 * treated as the other kind. {@link RefundRequestRules} applies whichever inside the read itself.
 *
 * <p><b>Fenced since 2026-09-23</b>, with the rest of the member endpoints. It used to take the plain
 * door on the argument that a fixed sentence to the centre's own mailbox gains an asserted caller
 * nothing, and that the fence would cost a member with a tab open overnight a button failing for no
 * visible reason. The identity-binding flip retires both halves: a principal can no longer be
 * asserted, and an unverified session is now a logged-out one, so the fence costs nothing it did not
 * already cost.
 *
 * @author Claude Code
 */
public final class RequestRefundEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.REQUEST_REFUND. */
    public static final String REQUEST_REFUND_ADDRESS = "modality/service/mail/requestRefund";

    public RequestRefundEndpoint() {
        super(REQUEST_REFUND_ADDRESS, argument -> {
            // On the caller's thread: the rows this writes carry an author, and by the first async
            // step there is no principal left to read.
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            Object callerUserId = StateAccessor.getUserId(state);
            Object documentId = Numbers.toLong(argument instanceof Object[] array && array.length > 0
                ? array[0] : argument);
            if (callerUserId instanceof ModalityGuestPrincipal guest)
                return RefundRequestRules.request(documentId, guest.getEmail(), true, callerUserId);
            return MemberSessionGuard.whenCallerIsVerifiedMember((callerPersonId, callerAccountId) ->
                RefundRequestRules.request(documentId, callerAccountId, false, callerUserId));
        });
    }
}

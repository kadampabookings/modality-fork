package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;

/**
 * "I have a question about my booking."
 *
 * <p>Argument: the booking's id, the caller's subject and the caller's message. Those last two are
 * genuinely the caller's — a contact form without them is not one — and everything else the browser
 * used to send is a fact about the booking, so it is read here instead: the {@code [event-ref]} prefix,
 * the display name, the reply address, and the document the mail hangs off.
 *
 * <p>Two kinds of caller reach the orders page and the media-access notice, and both see this form, so
 * both are answered: a member, whose account decides what they may ask about, and a guest, whose proven
 * address does. Which test applies is read from the PRINCIPAL, never from the argument — a caller
 * cannot ask to be treated as the other kind. {@link ContactRequestRules} applies whichever inside the
 * read itself.
 *
 * <h3>Not the fenced door — but NOT for the refund's reason</h3>
 *
 * <p>This uses {@code whenCallerIsMember}, which does not require a verified session, the same as
 * {@link RequestRefundEndpoint}. The reasoning there does not transfer, and it is worth being exact
 * about why rather than inheriting it: the refund composes a FIXED sentence, so a caller who asserted
 * a principal gains nothing but a note the centre would have received anyway. This one carries the
 * caller's own words. An asserted identity can therefore put arbitrary text into the centre's mailbox
 * under a real member's display name, with that member's address as the reply-to — which is a
 * social-engineering surface the refund does not have.
 *
 * <p>It is still a strict improvement on what it replaces, which is the only reason it ships this way:
 * before this endpoint, ANY caller — with no principal at all — could insert a mail row naming any
 * document, and choose the display name and the reply address as well. What is left is narrower on
 * every axis. But this endpoint should take {@code whenCallerIsVerifiedMember} once the identity
 * binding flip lands, and it belongs at the FRONT of that queue rather than with the others.
 *
 * <p>Two things are also not closed here, both unchanged from the dialog: nothing limits how many
 * messages one caller may send, and nothing deduplicates them.
 *
 * @author Claude Code
 */
public final class ContactCentreEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.CONTACT_CENTRE. */
    public static final String CONTACT_CENTRE_ADDRESS = "modality/service/mail/contactCentre";

    public ContactCentreEndpoint() {
        super(CONTACT_CENTRE_ADDRESS, argument -> {
            // On the caller's thread: the rows this writes carry an author, and by the first async
            // step there is no principal left to read.
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            Object callerUserId = StateAccessor.getUserId(state);
            Object[] array = argument instanceof Object[] a ? a : null;
            if (array == null || array.length < 3)
                return Future.failedFuture("[" + ContactRequestRules.BAD_MESSAGE_KEY
                                           + "] This operation is not available to you");
            Object documentId = Numbers.toLong(array[0]);
            String subject = array[1] == null ? null : String.valueOf(array[1]);
            String message = array[2] == null ? null : String.valueOf(array[2]);
            if (callerUserId instanceof ModalityGuestPrincipal guest)
                return ContactRequestRules.send(documentId, subject, message,
                    guest.getEmail(), true, callerUserId);
            return MemberSessionGuard.whenCallerIsMember((callerPersonId, callerAccountId) ->
                ContactRequestRules.send(documentId, subject, message,
                    callerAccountId, false, callerUserId));
        });
    }
}

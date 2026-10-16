package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Sending a booking's letter — the replacement for clients writing
 * {@code document.trigger_send_letter_id} themselves.
 *
 * <p>Argument: {@code [documentId, letterId]} — ONE argument that is an array of two, which is the
 * shape every staff endpoint here uses ({@code ADD_RESIDENT}, {@code MERGE_DUPLICATE_PERSONS} and the
 * rest), and the length is checked exactly.
 *
 * <p><b>Do not relax that to "at least two" for forward compatibility.</b> It looks like the
 * future-proof choice and cuts the wrong way on this endpoint: tolerating extra elements means a
 * later argument that TIGHTENS something — a confirmation, a reason, an asserted recipient — would be
 * silently ignored by an older server during a mixed-version window, which is a quiet downgrade.
 * Refusing is loud and recoverable, and the client and server ship from the same deploy, so the window
 * is short. If this ever needs optional or named fields, move it to the {@code $codec} typed-argument
 * form the authentication endpoints already use, rather than loosening the array.
 *
 * <p>Why it exists, and why it is guarded on an operation rather than a route, is in
 * {@link DocumentLetterRules}. In short: the trigger that column fires composes a letter and lets the
 * database address it from columns the same client may write, and the standard letters carry the cart
 * link, which is a bearer token. The column is denied to clients once every client has moved here.
 *
 * @author Claude Code
 */
public final class SendDocumentLetterEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.SEND_DOCUMENT_LETTER. */
    public static final String SEND_DOCUMENT_LETTER_ADDRESS = "modality/service/document/sendLetter";

    public SendDocumentLetterEndpoint() {
        super(SEND_DOCUMENT_LETTER_ADDRESS, argument -> {
            Object[] arguments = argument instanceof Object[] array ? array : null;
            if (arguments == null || arguments.length != 2)
                return RouteAccessGuard.refused();
            Object documentId = arguments[0], letterId = arguments[1];
            // Both read on the caller's thread, before the first async step: the booking's owner has to
            // be queried before the grant can be scoped to it, and by the time that answers the
            // thread-local is restored and there is no caller left to read.
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            Object callerUserId = StateAccessor.getUserId(state);
            return DocumentLetterRules.sendLetter(state, documentId, letterId, callerUserId)
                .map(x -> (Object) x);
        });
    }
}

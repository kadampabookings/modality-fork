package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Reading one of a booking's mails, content included — for the back office's mail viewer on
 * /registration-bookings, and the replacement for clients selecting {@code mail.content} themselves.
 *
 * <p>Argument: {@code [documentId, mailId]}, checked to be exactly two, as {@link SendDocumentLetterEndpoint}
 * explains. Answers the content as a string.
 *
 * <p>Why it is fenced on {@code SendDocumentLetter} and logged, rather than left to a query: a composed mail
 * carries the booking's cart link, a bearer token. See {@link DocumentLetterRules#readMailContent}.
 *
 * @author Claude Code
 */
public final class ReadDocumentMailEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React back office's registration feature (use-mail-content.ts). */
    public static final String READ_DOCUMENT_MAIL_ADDRESS = "modality/service/document/readMail";

    public ReadDocumentMailEndpoint() {
        super(READ_DOCUMENT_MAIL_ADDRESS, argument -> {
            Object[] arguments = argument instanceof Object[] array ? array : null;
            if (arguments == null || arguments.length != 2)
                return RouteAccessGuard.refused();
            // Read on the caller's thread, before the first async step (see SendDocumentLetterEndpoint).
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            Object callerUserId = StateAccessor.getUserId(state);
            return DocumentLetterRules.readMailContent(state, arguments[0], arguments[1], callerUserId)
                .map(x -> (Object) x);
        });
    }
}

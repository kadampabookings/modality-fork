package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Previewing one of a booking's letters as the booker would receive it, brackets filled in — for the
 * "Send a letter" dialog of the back office's /registration-bookings.
 *
 * <p>Argument: {@code [documentId, letterId]}, checked to be exactly two, as {@link SendDocumentLetterEndpoint}
 * explains. Answers {@code [subject, content]}. Fenced like a send, because the filled-in letter carries the
 * booking's cart link: see {@link DocumentLetterRules#previewLetter}.
 *
 * @author Claude Code
 */
public final class PreviewDocumentLetterEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React back office's registration feature (use-letter-preview.ts). */
    public static final String PREVIEW_DOCUMENT_LETTER_ADDRESS = "modality/service/document/previewLetter";

    public PreviewDocumentLetterEndpoint() {
        super(PREVIEW_DOCUMENT_LETTER_ADDRESS, argument -> {
            Object[] arguments = argument instanceof Object[] array ? array : null;
            if (arguments == null || arguments.length != 2)
                return RouteAccessGuard.refused();
            // Read on the caller's thread, before the first async step (see SendDocumentLetterEndpoint).
            Object state = ThreadLocalStateHolder.getThreadLocalState();
            return DocumentLetterRules.previewLetter(state, arguments[0], arguments[1]).map(x -> (Object) x);
        });
    }
}

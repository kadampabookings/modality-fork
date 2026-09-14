package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#resolveMateInvite(String, Object)} — "can this room-share
 * invite link still be followed?".
 *
 * <p>UNAUTHENTICATED by design: the person following an invite may have no account yet, so there is
 * nobody to authenticate. That is only acceptable because the reply is a bare status — USABLE, FULL,
 * EXPIRED or UNKNOWN — and never says whose room it is, which booking it names or what event it
 * belongs to. The whole reason a forwarded link is harmless is that it discloses nothing; this
 * endpoint must not become the thing that does.
 *
 * <p>Argument is {@code [token, eventId]}, because the bus call carries a single argument.
 *
 * @author Bruno Salmon
 */
public class ResolveMateInviteDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    public ResolveMateInviteDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.RESOLVE_MATE_INVITE_ADDRESS, arg -> {
            Object[] args = arg instanceof Object[] ? (Object[]) arg : new Object[0];
            String token = Arrays.length(args) > 0 && args[0] != null ? args[0].toString() : null;
            Object eventId = Arrays.length(args) > 1 ? args[1] : null;
            return DocumentService.resolveMateInvite(token, eventId);
        });
    }

}

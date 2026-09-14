package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#describeMateInviteRoom(String, Object)} — "which room does this
 * usable invite link join?", for the booking form's "Accept invitation and book" (room-mate plan step 7).
 *
 * <p>UNAUTHENTICATED, like {@link ResolveMateInviteDocumentMethodEndpoint}: the person following an invite
 * may have no account yet. What it discloses was agreed deliberately and is narrow: for a link that can
 * still be followed, the room's accommodation item and the room booking's first and last attendance day.
 * Never the booker's name, the booking's reference, or anything for a link that is unknown, expired or
 * full — those describe nothing.
 *
 * <p>Argument is {@code [token, eventId]}, because the bus call carries a single argument.
 *
 * @author Bruno Salmon
 */
public class DescribeMateInviteRoomDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    public DescribeMateInviteRoomDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.DESCRIBE_MATE_INVITE_ROOM_ADDRESS, arg -> {
            Object[] args = arg instanceof Object[] ? (Object[]) arg : new Object[0];
            String token = Arrays.length(args) > 0 && args[0] != null ? args[0].toString() : null;
            Object eventId = Arrays.length(args) > 1 ? args[1] : null;
            return DocumentService.describeMateInviteRoom(token, eventId);
        });
    }

}

package one.modality.ecommerce.document.service.buscall;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#listMateInvitations(Object)} (room-mate plan Part C).
 *
 * <p>Argument is the booker's booking; the result is a JSON array of {slot, date, failed}. No token, no
 * hash and no address crosses the bus — the cart has the names already, in the room line it is showing.
 *
 * @author Bruno Salmon
 */
public class ListMateInvitationsDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    public ListMateInvitationsDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.LIST_MATE_INVITATIONS_ADDRESS, DocumentService::listMateInvitations);
    }

}

package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#sendMateInvitation(Object, int, String, String)} (room-mate
 * plan Part C, the "they'll book themselves" arm).
 *
 * <p>Four values across one argument, as an {@code Object[]} unpacked here: the booking, which of its
 * room's seven roommate slots, where to write, and which language to write in. One argument crosses the
 * bus, so an array is how several travel.
 *
 * <p>Nothing comes back but success or failure. The invitation link is deliberately NOT part of the
 * result: it carries a bearer token for a bed, and the whole reason this endpoint exists rather than a
 * mint-then-post pair is that the browser never has to hold one in order to have it sent.
 *
 * @author Bruno Salmon
 */
public class SendMateInvitationDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, Void> {

    public SendMateInvitationDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.SEND_MATE_INVITATION_ADDRESS,
            SendMateInvitationDocumentMethodEndpoint::send);
    }

    private static Future<Void> send(Object argument) {
        if (!(argument instanceof Object[] values) || values.length < 4)
            return Future.failedFuture("[MateInviteError] Malformed invitation request");
        Object documentId = values[0];
        // The slot arrives as whatever the serialiser made of a number, so it is read as one rather than
        // cast. Out-of-range values are the service's to refuse, where the rule lives beside the room.
        int mateSlot = values[1] instanceof Number n ? n.intValue() : -1;
        String email = values[2] == null ? null : values[2].toString();
        String lang = values[3] == null ? null : values[3].toString();
        return DocumentService.sendMateInvitation(documentId, mateSlot, email, lang);
    }

}

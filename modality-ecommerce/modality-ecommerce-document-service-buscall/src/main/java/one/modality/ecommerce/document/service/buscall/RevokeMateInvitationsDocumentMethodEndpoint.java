package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#revokeMateInvitations(Object, int)} (room-mate plan Part C).
 *
 * <p>Two values across the one argument a bus call carries: the booking, and which of its room's seven
 * roommate slots — or anything outside that range for "every live link this room has", which is how a
 * booker reaches a link they copied and sent themselves.
 *
 * <p>The result is how many were stopped. Zero is ordinary: pressing it twice is a booker making sure.
 *
 * @author Bruno Salmon
 */
public class RevokeMateInvitationsDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, Integer> {

    public RevokeMateInvitationsDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.REVOKE_MATE_INVITATIONS_ADDRESS,
            RevokeMateInvitationsDocumentMethodEndpoint::revoke);
    }

    private static Future<Integer> revoke(Object argument) {
        if (!(argument instanceof Object[] values) || values.length < 2)
            return Future.failedFuture("[MateInviteError] Malformed request");
        // Refused rather than defaulted. 0 means "every live link this room has", and this is the one
        // place where not knowing what the serialiser made of a number would otherwise pick the widest,
        // irreversible reading of an ambiguous request. The sibling send endpoint refuses for the same
        // reason; it can afford to default because its fallback is a refusal, and this one's is not.
        if (!(values[1] instanceof Number number))
            return Future.failedFuture("[MateInviteError] Malformed request");
        return DocumentService.revokeMateInvitations(values[0], number.intValue());
    }

}

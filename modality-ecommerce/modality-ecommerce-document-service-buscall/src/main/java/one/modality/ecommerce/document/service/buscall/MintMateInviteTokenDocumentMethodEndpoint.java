package one.modality.ecommerce.document.service.buscall;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#mintMateInviteToken(Object)} (room-mate plan steps 4-5):
 * argument is the owner accommodation line id, result is the raw invite token.
 *
 * @author Bruno Salmon
 */
public class MintMateInviteTokenDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    public MintMateInviteTokenDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.MINT_MATE_INVITE_TOKEN_ADDRESS, DocumentService::mintMateInviteToken);
    }

}

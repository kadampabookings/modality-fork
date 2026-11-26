package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#deletePayment(Object, Object, Object)} — deleting a payment entered by hand, or a whole transfer.
 *
 * <p>Argument: {@code [documentId, moneyTransferId, comment]}, exactly. Shape only: see {@link PaymentEndpointArguments}.
 *
 * @author Claude Code
 */
public class DeletePaymentDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    public DeletePaymentDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.DELETE_PAYMENT_ADDRESS, DeletePaymentDocumentMethodEndpoint::call);
    }

    static Future<Object> call(Object argument) {
        Object[] v = PaymentEndpointArguments.exactly(argument, 3);
        if (v == null)
            return PaymentEndpointArguments.malformed();
        return DocumentService.deletePayment(v[0], v[1], v[2]);
    }
}

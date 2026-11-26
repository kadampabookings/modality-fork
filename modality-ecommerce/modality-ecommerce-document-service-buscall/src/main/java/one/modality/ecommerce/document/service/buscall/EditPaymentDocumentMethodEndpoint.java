package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#editPayment(Object, Object, Object, Object, Object, Object, Object)} — correcting a payment entered by hand.
 *
 * <p>Argument: {@code [documentId, moneyTransferId, methodId, amount, wireless, transactionRef, comment]}, exactly. Shape only: see {@link PaymentEndpointArguments}.
 *
 * @author Claude Code
 */
public class EditPaymentDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    public EditPaymentDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.EDIT_PAYMENT_ADDRESS, EditPaymentDocumentMethodEndpoint::call);
    }

    static Future<Object> call(Object argument) {
        Object[] v = PaymentEndpointArguments.exactly(argument, 7);
        if (v == null)
            return PaymentEndpointArguments.malformed();
        return DocumentService.editPayment(v[0], v[1], v[2], v[3], v[4], v[5], v[6]);
    }
}

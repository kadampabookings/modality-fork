package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#recordPayment(Object, Object, Object, Object, Object, Object, Object)} — recording a payment, or a refund, on a booking.
 *
 * <p>Argument: {@code [documentId, methodId, amount, wireless, transactionRef, comment, markArrived]}, exactly. Shape only: see {@link PaymentEndpointArguments}.
 *
 * @author Claude Code
 */
public class RecordPaymentDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    public RecordPaymentDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.RECORD_PAYMENT_ADDRESS, RecordPaymentDocumentMethodEndpoint::call);
    }

    static Future<Object> call(Object argument) {
        Object[] v = PaymentEndpointArguments.exactly(argument, 7);
        if (v == null)
            return PaymentEndpointArguments.malformed();
        return DocumentService.recordPayment(v[0], v[1], v[2], v[3], v[4], v[5], v[6]);
    }
}

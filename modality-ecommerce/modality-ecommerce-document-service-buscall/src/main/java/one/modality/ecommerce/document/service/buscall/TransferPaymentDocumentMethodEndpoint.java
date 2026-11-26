package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#transferPayment(Object, Object, Object, Object)} — moving an amount paid on one booking to another.
 *
 * <p>Argument: {@code [fromDocumentId, toDocumentId, amount, comment]}, exactly. Shape only: see {@link PaymentEndpointArguments}.
 *
 * @author Claude Code
 */
public class TransferPaymentDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    public TransferPaymentDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.TRANSFER_PAYMENT_ADDRESS, TransferPaymentDocumentMethodEndpoint::call);
    }

    static Future<Object> call(Object argument) {
        Object[] v = PaymentEndpointArguments.exactly(argument, 4);
        if (v == null)
            return PaymentEndpointArguments.malformed();
        return DocumentService.transferPayment(v[0], v[1], v[2], v[3]);
    }
}

package one.modality.ecommerce.payment.buscall;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.payment.GetPaymentMethodsArgument;
import one.modality.ecommerce.payment.GetPaymentMethodsResult;
import one.modality.ecommerce.payment.PaymentService;

/**
 * @author Bruno Salmon
 */
public final class GetPaymentMethodsEndpoint extends AsyncFunctionBusCallEndpoint<GetPaymentMethodsArgument, GetPaymentMethodsResult> {

    public GetPaymentMethodsEndpoint() {
        super(PaymentServiceBusAddress.GET_PAYMENT_METHODS_METHOD_ADDRESS, PaymentService::getPaymentMethods);
    }

}

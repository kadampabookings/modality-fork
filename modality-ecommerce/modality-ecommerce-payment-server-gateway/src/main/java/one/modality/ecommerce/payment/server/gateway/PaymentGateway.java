package one.modality.ecommerce.payment.server.gateway;

import dev.webfx.platform.async.Future;
import one.modality.ecommerce.payment.GatewayPaymentMethodInfo;

import java.util.List;

public interface PaymentGateway {

    String getName();

    /**
     * Returns the payment methods this gateway supports, in display order.
     * The result is static per gateway implementation — no async call is needed.
     * The server's {@code getPaymentMethods} endpoint delegates to this method
     * after resolving which gateway is configured for the current event/organization.
     */
    List<GatewayPaymentMethodInfo> getSupportedPaymentMethods();

    Future<GatewayInitiatePaymentResult> initiatePayment(GatewayInitiatePaymentArgument argument);

    Future<GatewayCompletePaymentResult> completePayment(GatewayCompletePaymentArgument argument);

}

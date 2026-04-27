package one.modality.ecommerce.payment.server.gateway;

import one.modality.ecommerce.payment.PaymentFormType;
import one.modality.ecommerce.payment.PaymentMethod;

import java.util.Map;

/**
 * @author Bruno Salmon
 */
public record GatewayInitiatePaymentArgument(
    String paymentId,
    GatewayOrder order,
    String currencyCode,
    boolean isLive, // if false, the payment gateway will use its sandbox environment for this payment
    PaymentMethod paymentMethod,
    PaymentFormType preferredFormType,
    // The following fields are only used for embedded payments
    boolean favorSeamless,
    boolean isOriginOnHttps,
    // null = gateway default (all methods)
    String returnUrl,
    String cancelUrl,
    Map<String, String> accountParameters,
    GatewayCustomer customer,  // might be null; used by gateways to pre-fill billing details
    String merchantCountryCode // ISO 3166-1 alpha-2 of the merchant's organization (e.g. "GB"); may be null
) {

    public String getAccountParameter(String key, String defaultValue) {
        String value = getAccountParameter(key);
        return value == null ? defaultValue : value;
    }

    public String getAccountParameter(String key) {
        return accountParameters.get(key);
    }

    public String getRequiredAccountParameter(String key) throws IllegalArgumentException {
        String value = getAccountParameter(key);
        if (value != null)
            return value;
        throw new IllegalArgumentException("Missing required account parameter: " + key);
    }

}

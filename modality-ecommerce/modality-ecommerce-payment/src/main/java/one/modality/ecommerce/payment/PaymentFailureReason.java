package one.modality.ecommerce.payment;

/**
 * @author Bruno Salmon
 */
public enum PaymentFailureReason {
    INVALID_CARD_NUMBER,
    EXPIRED_CARD,
    INVALID_CVV,
    INVALID_EXPIRY_DATE,
    INSUFFICIENT_FUNDS,
    DECLINED_BY_BANK,
    CARD_TYPE_NOT_ACCEPTED,
    BILLING_ADDRESS_REQUIRED,
    GATEWAY_ERROR,
    UNKNOWN_REASON
}

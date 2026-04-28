package one.modality.ecommerce.payment;

/**
 * @author Bruno Salmon
 */
public record UpdatePaymentStatusArgument(
    Object paymentPrimaryKey,
    String gatewayResponse,
    String gatewayTransactionRef,
    String gatewayStatus,
    boolean isPendingStatus,
    boolean isSuccessfulStatus,
    boolean isExplicitUserCancellation,
    String errorMessage,
    /** The payment form type used for this transaction, or null if not applicable/known. */
    PaymentFormType formType,
    /** The specific payment method (e.g. GOOGLE_PAY, APPLE_PAY), or null for standard card payments. */
    PaymentMethod paymentMethod
) {

    public static UpdatePaymentStatusArgument createCapturedStatusArgument(Object paymentPrimaryKey, String gatewayResponse, String gatewayTransactionRef, String gatewayStatus, boolean pendingStatus, boolean successStatus, PaymentFormType formType, PaymentMethod paymentMethod) {
        return new UpdatePaymentStatusArgument(paymentPrimaryKey, gatewayResponse, gatewayTransactionRef, gatewayStatus, pendingStatus, successStatus, false, null, formType, paymentMethod);
    }

    public static UpdatePaymentStatusArgument createCancelStatusArgument(Object paymentPrimaryKey, boolean explicitUserCancellation) {
        return createCancelStatusArgument(paymentPrimaryKey, explicitUserCancellation, null, null);
    }

    /** Variant that records the form type and payment method on the cancel/abandon history entry
     *  so it reads e.g. "Abandoned payment £25 (Google Pay)" instead of "Abandoned payment £25". */
    public static UpdatePaymentStatusArgument createCancelStatusArgument(Object paymentPrimaryKey, boolean explicitUserCancellation, PaymentFormType formType, PaymentMethod paymentMethod) {
        return new UpdatePaymentStatusArgument(paymentPrimaryKey, null, null, null, false, false, explicitUserCancellation, null, formType, paymentMethod);
    }

    public static UpdatePaymentStatusArgument createExceptionStatusArgument(Object paymentPrimaryKey, String gatewayResponse, String errorMessage) {
        return new UpdatePaymentStatusArgument(paymentPrimaryKey, gatewayResponse, null, null, true, false, false, errorMessage, null, null);
    }

}

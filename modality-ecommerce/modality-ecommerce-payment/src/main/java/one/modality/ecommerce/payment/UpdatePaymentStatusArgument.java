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
    PaymentFormType formType
) {

    public static UpdatePaymentStatusArgument createCapturedStatusArgument(Object paymentPrimaryKey, String gatewayResponse, String gatewayTransactionRef, String gatewayStatus, boolean pendingStatus, boolean successStatus, PaymentFormType formType) {
        return new UpdatePaymentStatusArgument(paymentPrimaryKey, gatewayResponse, gatewayTransactionRef, gatewayStatus, pendingStatus, successStatus, false, null, formType);
    }

    public static UpdatePaymentStatusArgument createCancelStatusArgument(Object paymentPrimaryKey, boolean explicitUserCancellation) {
        return new UpdatePaymentStatusArgument(paymentPrimaryKey, null, null, null, false, false, explicitUserCancellation, null, null);
    }

    public static UpdatePaymentStatusArgument createExceptionStatusArgument(Object paymentPrimaryKey, String gatewayResponse, String errorMessage) {
        return new UpdatePaymentStatusArgument(paymentPrimaryKey, gatewayResponse, null, null, true, false, false, errorMessage, null);
    }

}

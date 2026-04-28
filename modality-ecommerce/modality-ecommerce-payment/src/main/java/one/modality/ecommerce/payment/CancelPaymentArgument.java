package one.modality.ecommerce.payment;

/**
 * @param paymentPrimaryKey          primary key of the {@link one.modality.base.shared.entities.MoneyTransfer} to cancel
 * @param isExplicitUserCancellation true = user pressed a Cancel button, false = user didn't finalize the payment process (ex: closed the window, dismissed wallet sheet)
 * @param formType                   optional form type for the history-comment suffix; null = unknown
 * @param paymentMethod              optional payment method for the history-comment suffix (e.g. PAYPAL → " (PayPal)"); null = unknown
 *
 * @author Bruno Salmon
 */
public record CancelPaymentArgument(
    Object paymentPrimaryKey,
    boolean isExplicitUserCancellation,
    PaymentFormType formType,
    PaymentMethod paymentMethod
) {
    /** Convenience constructor preserving the original 2-arg signature for legacy callers. */
    public CancelPaymentArgument(Object paymentPrimaryKey, boolean isExplicitUserCancellation) {
        this(paymentPrimaryKey, isExplicitUserCancellation, null, null);
    }
}

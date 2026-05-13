package one.modality.ecommerce.payment;

/**
 * Argument for {@link PaymentService#getPaymentMethods}.
 *
 * <p>{@link #documentPrimaryKey()} is one of the documents that will be paid — the server
 * uses it to derive which payment gateway is configured (via the event's money account) and
 * whether the event is in live or test mode.
 *
 * @author Bruno Salmon
 */
public record GetPaymentMethodsArgument(
    Object documentPrimaryKey,
    Object eventPrimaryKey
) {
    /** Convenience constructor for the existing document-based path. */
    public GetPaymentMethodsArgument(Object documentPrimaryKey) {
        this(documentPrimaryKey, null);
    }

    /** Factory method for the event-based path (no document required). */
    public static GetPaymentMethodsArgument forEvent(Object eventPrimaryKey) {
        return new GetPaymentMethodsArgument(null, eventPrimaryKey);
    }
}

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
    Object documentPrimaryKey
) {}

package one.modality.ecommerce.payment;

/**
 * Result of {@link PaymentService#getPaymentMethods}.
 *
 * <p>Contains the name of the active gateway and the list of payment methods it supports.
 * The client partitions {@link #methods()} by {@link GatewayPaymentMethodInfo#formType()}:
 * each {@code EMBEDDED} method becomes its own selectable card, while all {@code REDIRECTED}
 * methods are collapsed into a single combined card named after the gateway.
 *
 * <p>Device-dependent methods (Google Pay, Apple Pay) must be additionally filtered
 * client-side using the eligibility check associated with their {@link PaymentMethod}.
 *
 * @author Bruno Salmon
 */
public record GetPaymentMethodsResult(
    String gatewayName,
    boolean live,
    GatewayPaymentMethodInfo[] methods
) {}

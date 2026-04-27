package one.modality.ecommerce.payment;

/**
 * Describes a single payment method offered by a gateway.
 *
 * <p>{@link #method()} identifies the method — the client derives everything method-specific
 * (eligibility check, icon, i18n label) from this enum value, with no help from the server.
 *
 * <p>{@link #formType()} tells the UI whether clicking this method will open an inline
 * embedded form ({@link PaymentFormType#EMBEDDED}) or redirect to the gateway's hosted
 * page ({@link PaymentFormType#REDIRECTED}).  The UI collapses all REDIRECTED methods into
 * one combined card (user makes the final method choice on the gateway page), while each
 * EMBEDDED method gets its own selectable card.
 *
 * @author Bruno Salmon
 */
public record GatewayPaymentMethodInfo(
    PaymentMethod method,
    PaymentFormType   formType
) {}

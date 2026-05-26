package one.modality.ecommerce.payment.server.gateway.impl.stripe;

import one.modality.ecommerce.payment.PaymentStatus;

/**
 * Maps the Stripe PaymentIntent status values to the generic Modality {@link PaymentStatus}.
 *
 * <p>Source of the canonical Stripe statuses: <a href="https://stripe.com/docs/payments/intents#intent-statuses">
 * https://stripe.com/docs/payments/intents#intent-statuses</a>.
 *
 * @author Bruno Salmon
 */
enum StripePaymentStatus {

    // The PaymentIntent has been created. No payment method yet attached.
    REQUIRES_PAYMENT_METHOD(PaymentStatus.PENDING),

    // The customer has supplied a payment method, but the PaymentIntent has not yet been confirmed.
    REQUIRES_CONFIRMATION(PaymentStatus.PENDING),

    // The customer must complete an additional step (e.g. 3DS authentication, redirect to bank).
    REQUIRES_ACTION(PaymentStatus.PENDING),

    // Stripe is processing the payment asynchronously (typical for some bank transfer methods).
    PROCESSING(PaymentStatus.PENDING),

    // Manual capture: the funds have been authorised but not yet captured.
    REQUIRES_CAPTURE(PaymentStatus.APPROVED),

    // The payment succeeded — funds have been captured.
    SUCCEEDED(PaymentStatus.COMPLETED),

    // The PaymentIntent was canceled before completion.
    CANCELED(PaymentStatus.CANCELED);

    private final PaymentStatus genericPaymentStatus;

    StripePaymentStatus(PaymentStatus genericPaymentStatus) {
        this.genericPaymentStatus = genericPaymentStatus;
    }

    public PaymentStatus getGenericPaymentStatus() {
        return genericPaymentStatus;
    }

    /** Safely converts a Stripe status string to this enum, returning null for null/unknown input. */
    public static StripePaymentStatus parse(String stripeStatus) {
        if (stripeStatus == null)
            return null;
        try {
            return StripePaymentStatus.valueOf(stripeStatus.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

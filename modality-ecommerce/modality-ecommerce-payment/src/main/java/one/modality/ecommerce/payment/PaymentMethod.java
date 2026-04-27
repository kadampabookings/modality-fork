package one.modality.ecommerce.payment;

/**
 * Identifies a specific payment method that a gateway can process.
 * Each value requires its own client-side eligibility check and UI assets (icon, label).
 *
 * @author Bruno Salmon
 */
public enum PaymentMethod {

    /** Credit or debit card — universally supported, no client eligibility check required. */
    CARD,

    /** PayPal wallet — always available; user logs into PayPal on the gateway form. */
    PAYPAL,

    /** Google Pay — requires client-side eligibility check via the Google Pay JS API. */
    GOOGLE_PAY,

    /** Apple Pay — requires client-side eligibility check via ApplePaySession.canMakePayments(). */
    APPLE_PAY,

    /** Venmo — US-only wallet offered by some gateways (e.g. PayPal). */
    VENMO,

    /** Buy Now Pay Later (e.g. PayPal Pay Later). */
    PAY_LATER,

    /** Bank transfer / direct debit (e.g. SEPA). */
    BANK_TRANSFER,

}

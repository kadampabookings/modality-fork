package one.modality.ecommerce.payment;

/**
 * What the resume-payment page is told, once its token has been verified.
 *
 * <p><b>What is NOT here, on purpose:</b> the event and its theme. Those are public reference data — an
 * anonymous visitor reads events legitimately on every booking form — so the page loads them itself by
 * {@link #eventPrimaryKey()}. Only the payment's state and the people it was paid for sit behind the token,
 * which keeps this record small and keeps the decision about what is sensitive in one place.
 *
 * @param pending    the payment is neither settled nor refused yet (a webhook may still be in flight)
 * @param successful the payment settled
 * @param amount     the total paid, in cents — from the PARENT transfer, since a spread payment's children
 *                   each carry only their own allocation
 * @param bookings   one per booking the payment covers, empty when the token is valid but nothing is linked
 *
 * @author Claude Code
 */
public record ResumePaymentResult(
    boolean pending,
    boolean successful,
    int amount,
    Object documentPrimaryKey,
    Object eventPrimaryKey,
    ResumePaymentBooking[] bookings
) { }

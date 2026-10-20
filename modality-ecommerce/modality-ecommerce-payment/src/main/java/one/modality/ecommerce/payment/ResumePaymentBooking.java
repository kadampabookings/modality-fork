package one.modality.ecommerce.payment;

/**
 * One booking covered by a payment, as its confirmation page shows it.
 *
 * <p>Deliberately a flat record of what the page displays rather than an entity: this travels to a PUBLIC
 * page, so what it carries is a decision, not a side effect of a query's select list.
 *
 * <p><b>NO E-MAIL ADDRESS, by decision (2026-10-20).</b> The page used to show each attendee's address under
 * their name, which is more than a confirmation needs and discloses it to a payer who may be a third party —
 * the pay-cart link exists to be shared, so the person paying need not be the person booked. The name says
 * who the booking is for; "you will receive a confirmation e-mail shortly" says the rest without naming
 * anybody's address. Do not add it back without deciding that question again.
 *
 * @author Claude Code
 */
public record ResumePaymentBooking(
    Object documentPrimaryKey,
    Object personPrimaryKey,
    String personName,
    String documentRef,
    int priceNet,
    int priceDeposit,
    int attendanceCount,
    Boolean inPerson
) { }

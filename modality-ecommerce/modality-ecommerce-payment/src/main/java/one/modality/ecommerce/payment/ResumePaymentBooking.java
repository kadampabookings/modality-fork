package one.modality.ecommerce.payment;

/**
 * One booking covered by a payment, as its confirmation page shows it.
 *
 * <p>Deliberately a flat record of what the page displays rather than an entity: this travels to a PUBLIC
 * page, so what it carries is a decision, not a side effect of a query's select list. It carries the person's
 * e-mail because the confirmation displays it and the "keep me updated" field is prefilled from it — if that
 * ever stops being true, this field should go with it.
 *
 * @author Claude Code
 */
public record ResumePaymentBooking(
    Object documentPrimaryKey,
    Object personPrimaryKey,
    String personName,
    String personEmail,
    String documentRef,
    int priceNet,
    int priceDeposit,
    int attendanceCount,
    Boolean inPerson
) { }

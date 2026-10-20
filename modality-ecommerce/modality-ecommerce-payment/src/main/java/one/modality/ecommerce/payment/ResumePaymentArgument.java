package one.modality.ecommerce.payment;

/**
 * Asks for the state of one payment, on behalf of whoever MADE it.
 *
 * @param paymentPrimaryKey the {@link one.modality.base.shared.entities.MoneyTransfer} to report on — for a
 *                          payment spread over several bookings, the PARENT, which is the id the gateway
 *                          return URL carries
 * @param resumeToken       the signed token minted for this payment when it was initiated, which is the whole
 *                          of the authorization: the resume page is PUBLIC (a gateway redirects the payer back
 *                          with no session) and the primary key above is sequential, so the id alone proves
 *                          nothing. The token went to whoever initiated the payment, and that is the right
 *                          reading rather than "does this booking belong to you": a booker can send the
 *                          pay-cart link to somebody else, so the payer need not own the booking, and an
 *                          ownership test would refuse them.
 *
 *                          <p>HOW OFTEN that happens on THIS page is UNMEASURABLE from what the system
 *                          records, and the reason is worth knowing before someone tries. The booking form
 *                          never initiates a redirected payment: it initiates an EMBEDDED one and offers a
 *                          fallback link to the gateway's hosted page. Clicking that link is a plain
 *                          navigation — no second initiatePayment, no second history row — so the history
 *                          comment says "(embedded form)", which was true when it was written and says
 *                          nothing about the hop that followed. Stripe and Square then return straight to
 *                          the React URL with no server hop, so nothing records it either. Over ALL payment
 *                          initiations a payer who is not the booking's owner is roughly one in four
 *                          (production, 90 days: 2,507 of 9,571), but that population is not this one.
 *
 *                          <p>Which is why the design rests on no ratio at all: one third-party payer
 *                          refused is the failure, whatever the frequency, and this page exists to stop a
 *                          payer paying twice.
 *
 *                          <p>That same fallback is why the token rides the TRANSFER and not the form type:
 *                          the hosted-page hop reuses the payment created for the embedded attempt, so the
 *                          token minted then is still the right one when the gateway redirects back.
 *
 *                          <p>Signed, NOT opaque: the payload base64url-decodes to the payment id, which
 *                          the holder already has from the URL. Nothing is hidden in it; what it carries
 *                          is the server's signature over that id and a deadline.
 *
 *                          <p><b>The residual it does not close</b>, stated rather than implied by the
 *                          phrase "whoever initiated the payment": initiating a payment on a booking is
 *                          itself unauthorized ({@code insertPayment} takes the document ids the caller
 *                          names), so a caller can mint a token for any booking by starting a payment on
 *                          it. This endpoint is therefore exactly as strong as that, which is open BY
 *                          DESIGN — the pay-cart flow exists so a booker can ask somebody else to pay. It
 *                          leaves a real transfer, a history row and a live gateway intent behind, so it
 *                          is noisy; and it buys nothing today that ordinary client DQL does not already
 *                          give, since reads are not authorized yet. It closes when the read-authorization
 *                          arc lands, or sooner if initiatePayment gains an ownership check.
 *
 * @author Claude Code
 */
public record ResumePaymentArgument(
    Object paymentPrimaryKey,
    String resumeToken
) { }

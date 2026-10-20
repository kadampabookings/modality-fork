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
 *                          nothing. The token went to whoever initiated the payment, which is the right
 *                          reading — one payment in four is made by somebody who does not own the booking
 *                          (measured on production, 2026-10-19: 2,505 of 9,564), so "does this booking belong
 *                          to you" would refuse a quarter of payers.
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

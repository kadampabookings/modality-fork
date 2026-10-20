package one.modality.ecommerce.payment.buscall;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.payment.PaymentService;
import one.modality.ecommerce.payment.ResumePaymentArgument;
import one.modality.ecommerce.payment.ResumePaymentResult;

/**
 * The resume-payment page's one read, as an ENDPOINT rather than as client DQL.
 *
 * <p>That is the point of it. The page is public and its reads returned booker names, e-mail addresses and
 * amounts keyed by a sequential id, so a conjunct in the client's own statement could never be the boundary —
 * the client chooses whether to include it, which is exactly what {@code ClientQueryGuard} does not stop.
 * Here the client chooses nothing: it names a payment and presents a token, and the server decides.
 *
 * <p>Unauthenticated by design, because a gateway redirect arrives with no session. The authorization is the
 * token — see {@link ResumePaymentArgument}.
 *
 * @author Claude Code
 */
public final class ResumePaymentMethodEndpoint extends AsyncFunctionBusCallEndpoint<ResumePaymentArgument, ResumePaymentResult> {

    public ResumePaymentMethodEndpoint() {
        super(PaymentServiceBusAddress.RESUME_PAYMENT_METHOD_ADDRESS, PaymentService::resumePayment);
    }

}

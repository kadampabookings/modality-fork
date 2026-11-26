package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;

/**
 * The shape check the four payment endpoints share ({@link RecordPaymentDocumentMethodEndpoint},
 * {@link TransferPaymentDocumentMethodEndpoint}, {@link EditPaymentDocumentMethodEndpoint},
 * {@link DeletePaymentDocumentMethodEndpoint}): one argument that is an array of EXACTLY the expected length.
 *
 * <p>Exact, for the reason SendDocumentLetterEndpoint gives: a later element that TIGHTENS a call must not be ignored
 * by an older server during a mixed-version window. The values themselves — ids, method, amount, text — are checked
 * by the server provider (BookingPayments in modality-ecommerce-document-service-server), which refuses rather than
 * defaults, so a Java caller of DocumentService gets the same fences as the bus.
 *
 * <p><b>Authentication decision</b> (docs/security/README.md): these endpoints check the shape only. Who may call is
 * the server provider's, before it reads anything: a verified back-office session of a person of their own, holding
 * RecordPayments (DeletePayments to delete) in the organisation and event that own the booking. Enforcing from the
 * first deploy: there is no existing caller to break.
 *
 * @author Claude Code
 */
final class PaymentEndpointArguments {

    /** What a malformed call is told — the same words as the server's, for every way it can be malformed. */
    static final String MALFORMED = "[PaymentError] Malformed request";

    private PaymentEndpointArguments() {
    }

    /** The values, or null unless {@code argument} is an array of exactly {@code length}. */
    static Object[] exactly(Object argument, int length) {
        return argument instanceof Object[] values && values.length == length ? values : null;
    }

    static <T> Future<T> malformed() {
        return Future.failedFuture(MALFORMED);
    }
}

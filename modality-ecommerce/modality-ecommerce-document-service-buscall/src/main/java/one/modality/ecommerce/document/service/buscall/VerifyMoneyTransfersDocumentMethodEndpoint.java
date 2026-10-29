package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import one.modality.ecommerce.document.service.DocumentService;

/**
 * Bus endpoint for {@link DocumentService#verifyMoneyTransfers(Object, Object[], boolean)} — the ticks of the
 * back office's Finance › Payments and Finance › Statements pages.
 *
 * <p>Three values across the one argument a bus call carries: {@code [organizationId, [transferId, …], verified]},
 * exactly three. The bus decodes a JSON array into an {@code Object[]} — the nested one too — and a JSON number
 * into whichever boxed type fits (Integer, Long, or Double when it had a fraction), so nothing here assumes one:
 * an id is any integral number that is a positive {@code int}, and is passed on as an Integer.
 *
 * <p><b>Refused, never defaulted.</b> A missing or non-boolean {@code verified} has no safe reading — read as
 * false it takes ticks back, read as true it vouches for payments nobody checked — so it fails, as
 * RevokeMateInvitationsDocumentMethodEndpoint refuses a slot it cannot read rather than widening it. The same
 * for the ids: none, more than 1,000, or any one that is not a positive integer refuses the whole call rather
 * than writing the ones that parsed. A digit string is refused too: the React client sends numbers. And the
 * length is exact, for the reason SendDocumentLetterEndpoint gives: a later element that TIGHTENS the call must
 * not be ignored by an older server.
 *
 * <p><b>Authentication decision</b> (docs/security/README.md, "every new surface records its auth decision"):
 * this class checks the shape only. Who may call is the server provider's, before it reads anything — a
 * verified back-office session of a person of their own (no support view), holding {@code VerifyPayments} for
 * the whole organisation — see MoneyTransferVerification in modality-ecommerce-document-service-server.
 * Enforcing from the first deploy: there is no existing caller to break.
 *
 * @author Claude Code
 */
public class VerifyMoneyTransfersDocumentMethodEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** What a malformed call is told — the same words for every way it can be malformed. */
    static final String MALFORMED = "[VerifyPaymentsError] Malformed request";

    /** The most ids one call may carry; the client sends larger selections in chunks. */
    static final int MAX_IDS = 1000;

    public VerifyMoneyTransfersDocumentMethodEndpoint() {
        super(DocumentServiceBusAddresses.VERIFY_MONEY_TRANSFERS_ADDRESS,
            VerifyMoneyTransfersDocumentMethodEndpoint::verify);
    }

    static Future<String> verify(Object argument) {
        if (!(argument instanceof Object[] values) || values.length != 3)
            return Future.failedFuture(MALFORMED);
        Integer organizationId = positiveInt(values[0]);
        Object[] transferIds = positiveInts(values[1]);
        if (organizationId == null || transferIds == null || !(values[2] instanceof Boolean verified))
            return Future.failedFuture(MALFORMED);
        return DocumentService.verifyMoneyTransfers(organizationId, transferIds, verified);
    }

    /** The ids as Integers, or null unless there are 1 to {@link #MAX_IDS} and every one is a positive int. */
    static Object[] positiveInts(Object value) {
        if (!(value instanceof Object[] array) || array.length < 1 || array.length > MAX_IDS)
            return null;
        Object[] ids = new Object[array.length];
        for (int i = 0; i < array.length; i++) {
            ids[i] = positiveInt(array[i]);
            if (ids[i] == null)
                return null;
        }
        return ids;
    }

    /**
     * A positive {@code int} from any integral number, or null for anything else: a fraction, zero or a negative,
     * an overflow, NaN, a string, a boolean, null.
     */
    static Integer positiveInt(Object value) {
        if (!(value instanceof Number number))
            return null;
        double d = number.doubleValue();
        // NaN fails the last test (NaN != NaN); the infinities fail the range.
        if (d < 1 || d > Integer.MAX_VALUE || d != Math.floor(d))
            return null;
        return number.intValue();
    }
}

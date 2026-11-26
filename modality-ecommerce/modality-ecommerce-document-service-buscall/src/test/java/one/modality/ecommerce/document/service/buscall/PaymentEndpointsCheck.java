package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;

/**
 * Check for the four payment endpoints' reading of the wire argument: an array of exactly the expected length, or
 * {@link PaymentEndpointArguments#MALFORMED} — before the service is reached. The values and the caller are the server
 * provider's to check (BookingPaymentsCheck). Runs from main(), like VerifyMoneyTransfersDocumentMethodEndpointCheck.
 */
public class PaymentEndpointsCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("PaymentEndpointsCheck");
        check("exactly 7 is read", PaymentEndpointArguments.exactly(new Object[7], 7) != null);
        check("6 for 7 is refused", PaymentEndpointArguments.exactly(new Object[6], 7) == null);
        check("8 for 7 is refused (a tightening element is never ignored)", PaymentEndpointArguments.exactly(new Object[8], 7) == null);
        check("a non-array is refused", PaymentEndpointArguments.exactly("x", 3) == null);
        check("null is refused", PaymentEndpointArguments.exactly(null, 3) == null);

        check("record refuses a short argument", malformed(RecordPaymentDocumentMethodEndpoint.call(new Object[6])));
        check("transfer refuses a long argument", malformed(TransferPaymentDocumentMethodEndpoint.call(new Object[5])));
        check("edit refuses a non-array", malformed(EditPaymentDocumentMethodEndpoint.call(42)));
        check("delete refuses null", malformed(DeletePaymentDocumentMethodEndpoint.call(null)));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    private static boolean malformed(Future<?> future) {
        return future.failed() && PaymentEndpointArguments.MALFORMED.equals(future.cause().getMessage());
    }
}

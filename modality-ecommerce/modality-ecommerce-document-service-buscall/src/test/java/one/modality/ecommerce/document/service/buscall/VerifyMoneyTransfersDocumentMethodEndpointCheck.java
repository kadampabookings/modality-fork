package one.modality.ecommerce.document.service.buscall;

import dev.webfx.platform.async.Future;

/**
 * Check for {@link VerifyMoneyTransfersDocumentMethodEndpoint}'s reading of the wire argument
 * {@code [organizationId, [transferId, …], verified]} — refused, never defaulted.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero on failure,
 * following the server module's checks. Only refusals are exercised: a well-formed argument goes on to the
 * document service, which needs a server.
 */
public class VerifyMoneyTransfersDocumentMethodEndpointCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("VerifyMoneyTransfersDocumentMethodEndpointCheck");

        // ── The outer shape ──
        check("no argument is malformed", malformed(null));
        check("a bare id is malformed", malformed(42));
        check("two values are malformed", malformed(new Object[] { 1, new Object[] { 2 } }));
        check("four values are malformed (an extra element is not ignored)",
            malformed(new Object[] { 1, new Object[] { 2 }, true, "confirm" }));

        // ── verified: a Boolean, or nothing ──
        check("a missing direction is not read as false", malformed(new Object[] { 1, new Object[] { 2 }, null }));
        check("1 is not true", malformed(new Object[] { 1, new Object[] { 2 }, 1 }));
        check("\"true\" is not true", malformed(new Object[] { 1, new Object[] { 2 }, "true" }));

        // ── The organisation ──
        check("no organisation is malformed", malformed(new Object[] { null, new Object[] { 2 }, true }));
        check("organisation 0 is malformed", malformed(new Object[] { 0, new Object[] { 2 }, true }));
        check("an organisation as a string is malformed", malformed(new Object[] { "1", new Object[] { 2 }, true }));

        // ── The ids ──
        check("no ids are malformed", malformed(new Object[] { 1, new Object[0], true }));
        check("ids that are not a list are malformed", malformed(new Object[] { 1, 2, true }));
        check("1,001 ids are malformed", malformed(new Object[] { 1, ids(1001), true }));
        check("one negative id refuses the call", malformed(new Object[] { 1, new Object[] { 2, -3 }, true }));
        check("one fractional id refuses the call", malformed(new Object[] { 1, new Object[] { 2, 3.5 }, true }));
        check("one null id refuses the call", malformed(new Object[] { 1, new Object[] { 2, null }, true }));
        check("1,000 ids are read", VerifyMoneyTransfersDocumentMethodEndpoint.positiveInts(ids(1000)) != null);
        check("a Long and an integral Double are read as Integers",
            VerifyMoneyTransfersDocumentMethodEndpoint.positiveInts(new Object[] { 5L, 6.0 })[1].equals(6));
        check("an id past the int range is refused",
            VerifyMoneyTransfersDocumentMethodEndpoint.positiveInt(3_000_000_000L) == null);
        check("NaN is refused", VerifyMoneyTransfersDocumentMethodEndpoint.positiveInt(Double.NaN) == null);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    private static boolean malformed(Object argument) {
        Future<String> answer = VerifyMoneyTransfersDocumentMethodEndpoint.verify(argument);
        return answer.failed() && VerifyMoneyTransfersDocumentMethodEndpoint.MALFORMED.equals(answer.cause().getMessage());
    }

    private static Object[] ids(int n) {
        Object[] ids = new Object[n];
        for (int i = 0; i < n; i++)
            ids[i] = i + 1;
        return ids;
    }
}

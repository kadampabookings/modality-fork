package one.modality.ecommerce.history.server;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Check that a history row names whoever acted — the two holes found 2026-10-19 by measuring production.
 *
 * <p>Of 1,420 payment history rows over 90 days that named nobody, 1,405 were a split payment's per-booking rows,
 * which never had a user set at all, and 15 were a guest's (a cart or magic link), whose principal fell to a branch
 * that records a null username. The read side's "did you make this payment?" check reads these rows, so it refused
 * the payer of every split payment.
 *
 * <p>The attribution itself runs inside a database write, so the two calls are asserted on the source — on the calls,
 * which the explaining comments cannot satisfy by mentioning them (a reformat makes these FAIL falsely, never pass
 * falsely). The guest value is a pure function, asserted directly.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure.
 */
public class HistoryAttributionCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("HistoryAttributionCheck");

        System.out.println("\nWhat a guest's rows record:");
        check("the guest's email, prefixed so it is not read as a KBS2 login",
            "guest:jane@example.org".equals(HistoryRecorder.guestUsername("jane@example.org")));
        check("never the constant \"Online guest\", which cannot tell two guests apart",
            !HistoryRecorder.guestUsername("jane@example.org").equals("Online guest"));
        String longEmail = "a".repeat(80) + "@example.org";
        String cut = HistoryRecorder.guestUsername(longEmail);
        check("cut to the column's 64 characters, so an over-long email cannot fail the insert and lose the row",
            cut.length() == HistoryRecorder.USERNAME_MAX_LENGTH && cut.startsWith(HistoryRecorder.GUEST_USERNAME_PREFIX));
        check("a guest with no email still gets the prefix, not a null",
            "guest:".equals(HistoryRecorder.guestUsername(null)));

        System.out.println("\nThe two calls that attribute a row:");
        String source = Files.readString(Path.of("src/main/java/one/modality/ecommerce/history/server/HistoryRecorder.java"));
        check("a guest principal has its own branch",
            source.contains("if (userId instanceof ModalityGuestPrincipal guest) {"));
        check("a split payment's per-booking rows are attributed, as a single payment's are",
            source.contains("History[]::new);\n        // Who paid, on every booking the payment is spread over")
            && source.contains("        return setUserOnHistories(histories, userId);\n    }\n\n}"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

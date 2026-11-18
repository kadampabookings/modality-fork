package one.modality.ecommerce.document.service.spi.impl.server;

import one.modality.ecommerce.document.service.events.book.AddDocumentEvent;

/**
 * Check for {@link BookingAgeRule} — which declared ages a new booking may carry, and which it forgets.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, following MateLinkRulesCheck.
 */
public class BookingAgeRuleCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("BookingAgeRule");

        // ── Refused: ages nobody has ──
        check("refuses a negative age, which would match every child band",
            BookingAgeRule.check(-1) != null && BookingAgeRule.check(-1).startsWith(BookingAgeRule.ERROR_PREFIX));
        check("refuses an age over 120", BookingAgeRule.check(121) != null);
        check("the refusal does not repeat the value", !BookingAgeRule.check(-1).contains("-1"));

        // ── Accepted ──
        check("accepts no age at all", BookingAgeRule.check(null) == null);
        check("accepts 0 and 120, the bounds", BookingAgeRule.check(0) == null && BookingAgeRule.check(120) == null);

        // ── Kept for a child, forgotten for an adult ──
        check("keeps 9", BookingAgeRule.check(9) == null && !BookingAgeRule.mustForget(9));
        check("keeps 17, the last child age", !BookingAgeRule.mustForget(17));
        check("forgets 18", BookingAgeRule.mustForget(18));
        check("forgets 30, accepted rather than refused", BookingAgeRule.check(30) == null && BookingAgeRule.mustForget(30));
        check("has nothing to forget when no age was given", !BookingAgeRule.mustForget(null));

        // ── The event the provider clears ──
        AddDocumentEvent adult = new AddDocumentEvent(1, 2, true, false, "en", null, "A", "B", "a@b.c", 30, null, null);
        adult.clearAge();
        check("clearAge leaves the event with no age to replay", adult.getAge() == null);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

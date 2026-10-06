package one.modality.ecommerce.document.service.spi.impl.server;

/**
 * Check for {@link BookingChangedRule} — when a front-office modification is refused because the
 * booking has gained lines since the client loaded it.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, following MateLinkRulesCheck.
 *
 * <p>The first cases are the ones that happened: booking 674 loaded with lines up to 1650962, its first
 * submit added 1650963/64, and the same tab then sent the same changes again.
 */
public class BookingChangedRuleCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("BookingChangedRule");

        // ── What happened on booking 674 ──
        check("refuses the second submit: the first one added lines the client never loaded",
            BookingChangedRule.changedSince(1650962L, 8359014L, 1650964, 8359028));
        check("lets the first submit through: nothing was added since the load",
            !BookingChangedRule.changedSince(1650962L, 8359014L, 1650962, 8359014));

        // ── Each kind of growth counts on its own ──
        check("refuses when only an attendance was added (a stay extended on an existing line)",
            BookingChangedRule.changedSince(10, 100, 10, 101));
        check("refuses when only a line was added (the back office split a line)",
            BookingChangedRule.changedSince(10, 100, 11, 100));

        // ── What must never refuse ──
        check("lets through when the newest rows the client loaded have been deleted since",
            !BookingChangedRule.changedSince(10, 100, 9, 98));
        check("lets through a booking that has no attendances at all",
            !BookingChangedRule.changedSince(10, 0, 10, null));

        // ── A booking that held nothing when it was loaded ──
        check("refuses when the client loaded no line and the booking has one now",
            BookingChangedRule.changedSince(0, 0, 5, null));
        check("an absent base counts as nothing loaded",
            BookingChangedRule.changedSince(null, null, 5, 50));

        // ── Ids as they arrive: the database gives Integer or Long, the wire may give a double or text ──
        check("compares an Integer from the database with a Long base by value",
            !BookingChangedRule.changedSince(1650964L, 8359028L, Integer.valueOf(1650964), Integer.valueOf(8359028)));
        check("reads a base that arrived as a double",
            !BookingChangedRule.changedSince(1650964.0, 8359028.0, 1650964, 8359028));
        check("reads a base that arrived as text",
            !BookingChangedRule.changedSince("1650964", "8359028", 1650964, 8359028));
        check("a garbled base reads as nothing loaded, so it can only refuse, never let a change through",
            BookingChangedRule.changedSince("not-an-id", 8359028, 1650964, 8359028));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

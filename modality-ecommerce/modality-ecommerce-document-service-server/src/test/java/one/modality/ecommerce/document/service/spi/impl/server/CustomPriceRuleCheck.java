package one.modality.ecommerce.document.service.spi.impl.server;

/**
 * Check for {@link CustomPriceRule} — the custom price a loaded line carries, and when a submitted one sets
 * the flag.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, following MateLinkRulesCheck.
 */
public class CustomPriceRuleCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("CustomPriceRule");

        // ── Read: the three shapes KBS2 leaves in the database ──
        check("a custom line is loaded with its amount",
            Integer.valueOf(-4_500).equals(CustomPriceRule.loadedPriceCustom(true, -4_500)));
        check("a line flagged custom with no amount is loaded as free (0), as the database prices it",
            Integer.valueOf(0).equals(CustomPriceRule.loadedPriceCustom(true, null)));
        check("an amount kept with the flag cleared is not loaded: the line is priced from the rates",
            CustomPriceRule.loadedPriceCustom(false, 12_000) == null);
        check("a line with neither is loaded with none",
            CustomPriceRule.loadedPriceCustom(false, null) == null);
        check("an absent flag reads as cleared",
            CustomPriceRule.loadedPriceCustom(null, 12_000) == null);

        // ── Write: only a verified back office sets the flag ──
        check("a back-office amount sets the flag (the reconciliation line)",
            CustomPriceRule.setsCustomFlag(true, -4_500));
        check("a back-office zero sets the flag too: free is a custom price",
            CustomPriceRule.setsCustomFlag(true, 0));
        check("a back-office event with no amount leaves the flag alone (a discount, a net price)",
            !CustomPriceRule.setsCustomFlag(true, null));
        check("a front-office amount never sets the flag: a guest cannot price their own booking",
            !CustomPriceRule.setsCustomFlag(false, -4_500));

        // ── Round trip: what is loaded and sent back changes nothing ──
        check("a cleared line loaded and sent back by the back office stays cleared",
            !CustomPriceRule.setsCustomFlag(true, CustomPriceRule.loadedPriceCustom(false, 12_000)));
        check("a custom line loaded and sent back stays custom",
            CustomPriceRule.setsCustomFlag(true, CustomPriceRule.loadedPriceCustom(true, null)));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

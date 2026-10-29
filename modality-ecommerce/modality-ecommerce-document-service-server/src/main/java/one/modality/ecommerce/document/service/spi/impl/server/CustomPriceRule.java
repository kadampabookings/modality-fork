package one.modality.ecommerce.document.service.spi.impl.server;

/**
 * What a custom price means between the database and the document service's events.
 *
 * <p>The database keeps a line's custom price in two columns, as KBS2 edits it: {@code price_custom}, the
 * amount, and {@code price_is_custom}, whether it applies. {@code compute_document_prices} (V0124) honours
 * the amount only when the flag is set, and KBS2 leaves both shapes behind: the flag set with no amount means
 * free (652 lines, all priced 0), and an amount kept with the flag cleared means back to the rates (1,112
 * lines; staging, 2026-10-28). The events carry the amount only — {@code PriceDocumentLineEvent.price_custom}
 * — and the clients price any line whose amount is present at that amount.
 *
 * <p>Bruno and David, 2026-10-28: a non-null {@code price_custom} in an event means "this amount, and the
 * flag set", so the flag never reaches the upper layers and can be dropped once KBS2 is retired.
 * <ul>
 *   <li>Read ({@link #loadedPriceCustom}): a line is loaded with its amount only when the flag is set,
 *       0 when the flag is set with no amount, and null when the flag is cleared — so a client prices it
 *       as the database does, and sending it back cannot flag a line that was not custom.</li>
 *   <li>Write ({@link #setsCustomFlag}): a submitted amount sets the flag, for a VERIFIED back-office submit
 *       only. {@code DocumentSubmitAuthorization} lists {@code PriceDocumentLineEvent} as back-office only
 *       but still lets it through while it observes; until now the missing flag made a front-office custom
 *       price harmless (priced from the rates), and without this limit a guest could set any price on their
 *       own booking — a negative one, then ask for a refund.</li>
 * </ul>
 * What the write rule does NOT close: on a line whose flag is ALREADY set (a reconciliation line, a free place
 * given in KBS2), a front-office submit still replays a new amount, as it did before this rule. That is the same
 * opening as the front office's 100% discount, and closes with it, when {@code DocumentSubmitAuthorization}
 * enforces.
 * The two ship together: the write rule alone would flag the 1,112 "amount kept" lines whenever an edit sends
 * a loaded event back.
 *
 * <p>Pure on purpose, like {@link BookingChangedRule}: {@code CustomPriceRuleCheck} covers it without a server.
 */
final class CustomPriceRule {

    private CustomPriceRule() {}

    /**
     * @param isCustom    the line's {@code price_is_custom}
     * @param priceCustom the line's {@code price_custom}
     * @return the {@code price_custom} the loaded event carries: the amount (0 when absent) for a custom line,
     *         null otherwise
     */
    static Integer loadedPriceCustom(Boolean isCustom, Integer priceCustom) {
        if (!Boolean.TRUE.equals(isCustom))
            return null;
        return priceCustom != null ? priceCustom : 0;
    }

    /**
     * @param verifiedBackoffice whether the submit comes from a verified back office ({@link VerifiedBackoffice})
     * @param priceCustom        the submitted event's {@code price_custom}
     * @return true when the submitted line must get {@code price_is_custom = true}
     */
    static boolean setsCustomFlag(boolean verifiedBackoffice, Integer priceCustom) {
        return verifiedBackoffice && priceCustom != null;
    }
}

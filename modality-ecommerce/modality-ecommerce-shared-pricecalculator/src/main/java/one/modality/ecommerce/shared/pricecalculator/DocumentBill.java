package one.modality.ecommerce.shared.pricecalculator;

import dev.webfx.platform.util.collection.Collections;
import one.modality.ecommerce.document.service.DocumentAggregate;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Bruno Salmon
 */
public final class DocumentBill {

    private final DocumentAggregate documentAggregate;
    private final Collection<SiteItemBill> siteItemBills;
    final boolean ignoreLongStayDiscount;
    /** When true, earlyBird rates are excluded from selection regardless of document.earlyBird. */
    final boolean ignoreEarlyBirdRates;
    /** When true, withItem (bundle) rates are excluded from selection regardless of which items are booked. */
    final boolean ignoreWithItemRates;
    private final boolean update;
    /**
     * Price of cancelled/abandoned lines, keyed by item primary key. These lines aren't recomputed
     * from attendances (they have none → £0); the server already set their price_net to the
     * non-refundable charge (compute_document_prices: "net becomes non refundable"), so the loaded
     * value is used directly. Added to the total and the min deposit, and exposed for per-item display.
     * The same amount counts toward the min deposit (the server sets price_min_deposit =
     * price_non_refundable for cancelled lines too).
     */
    private final Map<Object, Integer> cancelledLinesPricesByItem;

    private int totalPrice = -1;
    private int minDeposit = -1;

    DocumentBill(DocumentAggregate documentAggregate, Collection<SiteItemBill> siteItemBills, boolean ignoreLongStayDiscount, boolean update) {
        this(documentAggregate, siteItemBills, ignoreLongStayDiscount, update, false, false);
    }

    DocumentBill(DocumentAggregate documentAggregate, Collection<SiteItemBill> siteItemBills, boolean ignoreLongStayDiscount, boolean update, boolean ignoreEarlyBirdRates, boolean ignoreWithItemRates) {
        this(documentAggregate, siteItemBills, ignoreLongStayDiscount, update, ignoreEarlyBirdRates, ignoreWithItemRates, new HashMap<>());
    }

    DocumentBill(DocumentAggregate documentAggregate, Collection<SiteItemBill> siteItemBills, boolean ignoreLongStayDiscount, boolean update, boolean ignoreEarlyBirdRates, boolean ignoreWithItemRates, Map<Object, Integer> cancelledLinesPricesByItem) {
        this.documentAggregate = documentAggregate;
        this.siteItemBills = siteItemBills;
        this.ignoreLongStayDiscount = ignoreLongStayDiscount;
        this.ignoreEarlyBirdRates = ignoreEarlyBirdRates;
        this.ignoreWithItemRates = ignoreWithItemRates;
        this.update = update;
        this.cancelledLinesPricesByItem = cancelledLinesPricesByItem;
        siteItemBills.forEach(SiteItemBill::sortAttendanceBillsByDate);
    }

    DocumentAggregate getDocumentAggregate() {
        return documentAggregate;
    }

    public int getTotalPrice() {
        if (totalPrice == -1)
            totalPrice = computePrice(false);
        return totalPrice;
    }

    public int getMinDeposit() {
        if (minDeposit == -1)
            minDeposit = computePrice(true);
        return minDeposit;
    }

    public Collection<SiteItemBill> getSiteItemBills() {
        return siteItemBills;
    }

    /**
     * Per-item prices of cancelled/abandoned lines (their loaded non-refundable charge). Consumers
     * that build a per-item breakdown from {@link #getSiteItemBills()} should merge these in, since
     * cancelled lines aren't represented as SiteItemBills.
     */
    public Map<Object, Integer> getCancelledLinesPricesByItem() {
        return cancelledLinesPricesByItem;
    }

    public boolean isChildRateApplied() {
        // Note: this doesn't consider already priced lines
        getTotalPrice(); // To force the price algorithm to compute if not already done
        return Collections.anyMatch(siteItemBills, SiteItemBill::isChildRateApplied);
    }

    private int computePrice(boolean minDeposit) {
        // Cancelled/abandoned lines contribute their non-refundable charge (their loaded price_net) to
        // both the total and the min deposit — they're not recomputed from attendances. (Restores the
        // KBS2 logic that summed cancelled document lines' price_net.)
        int price = 0;
        for (Integer cancelledPrice : cancelledLinesPricesByItem.values())
            price += cancelledPrice;
        for (SiteItemBill siteItemBill : siteItemBills)
            price += siteItemBill.computePrice(this, minDeposit);
        if (minDeposit) {
            // Rounding the min deposit of the booking to the superior amount (without cents)
            int rounded = ((price + 99) / 100) * 100;
            if (rounded > price) { // If the rounded amount is indeed superior,
                // we still need to check that it doesn't exceed the total amount
                price = Math.min(rounded, getTotalPrice());
            }
        } else if (update) {
            documentAggregate.getDocument().setPriceNet(price);
        }
        return price;
    }
}

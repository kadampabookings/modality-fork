package one.modality.ecommerce.shared.pricecalculator;

import dev.webfx.platform.console.Console;
import one.modality.base.shared.entities.*;
import one.modality.base.shared.entities.util.Attendances;
import one.modality.ecommerce.document.service.DocumentAggregate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @author Bruno Salmon
 */
final class Kbs2PriceAlgorithm {

    public static DocumentBill computeDocumentBill(DocumentAggregate documentAggregate, boolean ignoreLongStayDiscount, boolean update) {
        return computeDocumentBill(documentAggregate, documentAggregate.getDocumentLinesStream(), ignoreLongStayDiscount, update, false, false);
    }

    public static DocumentBill computeDocumentBill(DocumentAggregate documentAggregate, Stream<DocumentLine> documentLineStream, boolean ignoreLongStayDiscount, boolean update) {
        return computeDocumentBill(documentAggregate, documentLineStream, ignoreLongStayDiscount, update, false, false);
    }

    public static DocumentBill computeDocumentBill(DocumentAggregate documentAggregate, boolean ignoreLongStayDiscount, boolean update, boolean ignoreEarlyBirdRates, boolean ignoreWithItemRates) {
        return computeDocumentBill(documentAggregate, documentAggregate.getDocumentLinesStream(), ignoreLongStayDiscount, update, ignoreEarlyBirdRates, ignoreWithItemRates);
    }

    public static DocumentBill computeDocumentBill(DocumentAggregate documentAggregate, Stream<DocumentLine> documentLineStream, boolean ignoreLongStayDiscount, boolean update, boolean ignoreEarlyBirdRates, boolean ignoreWithItemRates) {
        Map<SiteItem, SiteItemBill> siteItemBills = new HashMap<>();
        // Cancelled/abandoned lines are NOT recomputed from attendances (they have none → £0). The
        // server already set their price_net to the non-refundable charge (compute_document_prices:
        // "net becomes non refundable"), so use that loaded value directly, keyed by item, and add it
        // to the total via the DocumentBill. (Restores the KBS2 cancelled-lines pricing.)
        Map<Object, Integer> cancelledLinesPricesByItem = new HashMap<>();
        documentLineStream.forEach(line -> {
            if (Boolean.TRUE.equals(line.isCancelled()) || Boolean.TRUE.equals(line.getBooleanFieldValue("abandoned"))) {
                Item cancelledItem = line.getItem();
                if (cancelledItem != null) {
                    Integer priceNet = line.getPriceNet();
                    cancelledLinesPricesByItem.merge(cancelledItem.getPrimaryKey(), priceNet == null ? 0 : priceNet, Integer::sum);
                }
                return;
            }
            Site site = line.getSite();
            Item item = line.getItem();
            if (item.getRateAliasItem() != null)
                item = item.getRateAliasItem();
            SiteItem siteItem = new SiteItem(site, item);
            SiteItemBill siteItemBill = siteItemBills.computeIfAbsent(siteItem, siteItem1 -> new SiteItemBill(siteItem1, documentAggregate.findPriceDocumentLineEvent(line, false)));
            List<Attendance> lineAttendances = documentAggregate.getLineAttendances(line);
            lineAttendances.forEach(attendance -> {
                LocalDate date = Attendances.getDate(attendance);
                if (date == null) {
                    ScheduledItem scheduledItem = attendance.getScheduledItem();
                    if (scheduledItem == null)
                        Console.warn("No scheduled item could be found for attendance " + attendance.getPrimaryKey() + " - Was it added with a wrong date in KBS2?");
                    else
                        Console.warn("No date could be found for attendance " + attendance.getPrimaryKey() + " or its associated scheduled item " + scheduledItem.getPrimaryKey());
                    return;
                }
                AttendanceBill attendanceBill = new AttendanceBill(line, date);
                siteItemBill.addAttendanceBill(attendanceBill);
            });
        });
        return new DocumentBill(documentAggregate, siteItemBills.values(), ignoreLongStayDiscount, update, ignoreEarlyBirdRates, ignoreWithItemRates, cancelledLinesPricesByItem);
    }

}

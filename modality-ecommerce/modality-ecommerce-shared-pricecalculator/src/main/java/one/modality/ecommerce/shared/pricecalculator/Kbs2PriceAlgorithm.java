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
        documentLineStream.forEach(line -> {
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
        return new DocumentBill(documentAggregate, siteItemBills.values(), ignoreLongStayDiscount, update, ignoreEarlyBirdRates, ignoreWithItemRates);
    }

}

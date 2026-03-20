package one.modality.booking.client.workingbooking;

import dev.webfx.platform.util.collection.Collections;
import one.modality.base.client.time.ModalityDates;
import one.modality.base.shared.entities.Attendance;
import one.modality.base.shared.entities.DocumentLine;
import one.modality.base.shared.entities.Item;
import one.modality.base.shared.entities.util.Attendances;
import one.modality.ecommerce.document.service.events.book.*;
import one.modality.ecommerce.document.service.events.registration.MarkDocumentAsArrivedEvent;
import one.modality.ecommerce.document.service.events.registration.MarkDocumentAsCheckedOutEvent;
import one.modality.ecommerce.document.service.events.registration.documentline.PriceDocumentLineEvent;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author David Hello
 * @author Bruno Salmon
 */
public final class WorkingBookingHistoryHelper {

    public static String generateHistoryComment(WorkingBooking workingBooking) {
        return generateHistoryComment(workingBooking, true);
    }

    public static String generateHistoryComment(WorkingBooking workingBooking, boolean fromChangesOnly) {
        StringBuilder sb = new StringBuilder();
        boolean addedSection = appendHistoryCommentFromAttendances(workingBooking.getAttendancesAdded(fromChangesOnly), true, sb, workingBooking);
        boolean removedSection = appendHistoryCommentFromAttendances(workingBooking.getAttendancesRemoved(fromChangesOnly), false, sb, workingBooking);
        boolean addedSection2 = appendHistoryCommentFromDocumentLines(workingBooking.getNonTemporalDocumentLinesAdded(fromChangesOnly), !addedSection || removedSection, true, sb, workingBooking);
        appendHistoryCommentFromDocumentLines(workingBooking.getNonTemporalDocumentLinesRemoved(fromChangesOnly), addedSection2 || !removedSection, false, sb, workingBooking);
        EditShareMateInfoDocumentLineEvent editShareMateInfoDocumentLineEvent = workingBooking.findEditShareMateInfoDocumentLineEvent(fromChangesOnly);
        if (editShareMateInfoDocumentLineEvent != null) {
            newSection(sb).append("Room owner: ").append(editShareMateInfoDocumentLineEvent.getOwnerName());
        }
        EditShareOwnerInfoDocumentLineEvent editShareOwnerInfoDocumentLineEvent = workingBooking.findEditShareOwnerInfoDocumentLineEvent(fromChangesOnly);
        if (editShareOwnerInfoDocumentLineEvent != null) {
            newSection(sb).append("Room mate(s): ").append(String.join(", ", editShareOwnerInfoDocumentLineEvent.getMatesNames()));
        }
        EditCarersInfoEvent editCarersInfoEvent = workingBooking.findEditCarersInfoEvent(fromChangesOnly);
        if (editCarersInfoEvent != null) {
            newSection(sb).append("Carer(s): ");
            if (editCarersInfoEvent.getCarer1Name() != null) {
                if (editCarersInfoEvent.getCarer1DocumentPrimaryKey() != null)
                    sb.append("🔗");
                sb.append(editCarersInfoEvent.getCarer1Name());
            }
            if (editCarersInfoEvent.getCarer2Name() != null) {
                sb.append(", ");
                if (editCarersInfoEvent.getCarer2DocumentPrimaryKey() != null)
                    sb.append("🔗");
                sb.append(editCarersInfoEvent.getCarer2Name());
            }
        }
        // "Selected member rate" if the user selected the member rate
        ApplyFacilityFeeEvent applyFacilityFeeEvent = workingBooking.findApplyFacilityFeeEvent(fromChangesOnly);
        if (applyFacilityFeeEvent != null) {
            newSection(sb).append(applyFacilityFeeEvent.isApply() ? "Selected member rate" : "Unselected member rate");
        }
        // "Wrote a request" if the user wrote a request
        AddRequestEvent addRequestEvent = workingBooking.findAddRequestEvent(fromChangesOnly);
        if (addRequestEvent != null) {
            newSection(sb).append("Wrote a request"); // No need to say more, as the request itself will be copied in
            // the history (request) on the server side (see HistoryRecorder).
        }
        // Marked as arrived
        MarkDocumentAsArrivedEvent markAsArrivedDocumentEvent = workingBooking.findMarkAsArrivedDocumentEvent(fromChangesOnly);
        if (markAsArrivedDocumentEvent != null) {
            newSection(sb).append(markAsArrivedDocumentEvent.isArrived() ? "Marked as arrived" : "Unmarked as arrived");
        }
        // Marked as checked out
        MarkDocumentAsCheckedOutEvent markAsCheckedOutDocumentEvent = workingBooking.findMarkAsCheckedOutDocumentEvent(fromChangesOnly);
        if (markAsCheckedOutDocumentEvent != null) {
            newSection(sb).append(markAsCheckedOutDocumentEvent.isCheckedOut() ? "Marked as checked out" : "Unmarked as checked out");
        }
        PriceDocumentLineEvent priceDocumentLineEvent = workingBooking.findPriceDocumentLineEvent(null, fromChangesOnly);
        if (priceDocumentLineEvent != null) {
            Integer priceDiscount = priceDocumentLineEvent.getPrice_discount();
            if (priceDiscount != null) {
                newSection(sb);
                if (priceDiscount == 0)
                    sb.append("Removed the discount on ");
                else
                    sb.append("Applied a ").append(priceDiscount).append("% discount on ");
                sb.append(priceDocumentLineEvent.getDocumentLine().getItem().getName());
            }
        }
        return sb.toString();
    }

    private static boolean appendHistoryCommentFromAttendances(List<Attendance> attendances, boolean added, StringBuilder sb, WorkingBooking workingBooking) {
        if (attendances.isEmpty())
            return false;

        newSection(sb).append(!added ? "Removed" : workingBooking.isNewBooking() ? "Booked" : "Added");
        attendances.stream().collect(Collectors.groupingBy(Attendances::getItem))
            // Sorting by item family ordinal then item ordinal
            .entrySet().stream().sorted(Comparator.<Map.Entry<Item, List<Attendance>>, Integer>comparing(entry -> entry.getKey().getFamily().getOrd()).thenComparing(entry -> entry.getKey().getOrd()))
            .forEach(entry -> {
                Item item = entry.getKey();
                List<Attendance> itemAttendances = entry.getValue();
                List<LocalDate> dates = Collections.map(itemAttendances, Attendances::getDate);
                sb.append("  ⦿ ").append(item.getName()).append(" ").append(ModalityDates.formatDateSeries(dates));
            });
        return true;
    }

    private static boolean appendHistoryCommentFromDocumentLines(List<DocumentLine> documentLines, boolean newSection, boolean added, StringBuilder sb, WorkingBooking workingBooking) {
        if (documentLines.isEmpty())
            return false;

        if (newSection)
            newSection(sb).append(!added ? "Removed" : workingBooking.isNewBooking() ? "Booked" : "Added");
        documentLines.stream()
            // Sorting by item family ordinal then item ordinal
            .sorted(Comparator.<DocumentLine, Integer>comparing(dl -> dl.getItem().getFamily().getOrd()).thenComparing(dl -> dl.getItem().getOrd()))
            .forEach(documentLine -> {
                Item item = documentLine.getItem();
                sb.append("  ⦿ ").append(item.getName());
            });
        return true;
    }

    private static StringBuilder newSection(StringBuilder sb) {
        if (sb.length() > 0) // GWT
            sb.append("  ~  ");
        return sb;
    }

}

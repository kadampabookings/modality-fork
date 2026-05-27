package one.modality.booking.backoffice.bookingeditor.multi;

import dev.webfx.platform.util.collection.Collections;
import one.modality.base.shared.knownitems.KnownItemFamily;
import one.modality.booking.backoffice.bookingeditor.BookingEditor;
import one.modality.booking.backoffice.bookingeditor.family.FamilyBookingEditorBase;
import one.modality.booking.backoffice.bookingeditor.spi.BookingEditorProvider;
import one.modality.booking.client.workingbooking.WorkingBooking;

import java.util.List;

/**
 * @author Bruno Salmon
 */
public final class MultiBookingEditorProvider implements BookingEditorProvider {

    private final List<BookingEditorProvider> bookingEditorProviders;

    public MultiBookingEditorProvider(List<BookingEditorProvider> bookingEditorProviders) {
        this.bookingEditorProviders = bookingEditorProviders;
    }

    @Override
    public boolean acceptBooking(WorkingBooking workingBooking) {
        return true;
    }

    @Override
    public BookingEditor createBookingEditor(WorkingBooking workingBooking) {
        List<BookingEditor> bookingEditors = Collections.map(bookingEditorProviders,
            bookingEditorProvider -> bookingEditorProvider.createBookingEditor(workingBooking));
        // Workaround to fix issue for Pre-Summer Festival Volunteers event where the booking form is showing the
        // teachings for Friday introduction (we don't want that)
        BookingEditor teachingBookingEditor = Collections.findFirst(bookingEditors, be -> be.getClass().getSimpleName().equals("TeachingBookingEditor"));
        BookingEditor volunteerBookingEditor = Collections.findFirst(bookingEditors, be -> be.getClass().getSimpleName().equals("VolunteerBookingEditor"));
        if (teachingBookingEditor != null && volunteerBookingEditor != null)
            bookingEditors.remove(teachingBookingEditor);
        // End of workaround
        bookingEditors.sort((be1, be2) -> {
            if (be1 instanceof FamilyBookingEditorBase fbe1 && be2 instanceof FamilyBookingEditorBase fbe2) {
                KnownItemFamily family1 = fbe1.getFamily();
                KnownItemFamily family2 = fbe2.getFamily();
                return family1.compareTo(family2);
            }
            if (be1 instanceof FamilyBookingEditorBase)
                return -1;
            if (be2 instanceof FamilyBookingEditorBase)
                return 1;
            return 0;
        });
        return new MultiBookingEditor(workingBooking, bookingEditors);
    }
}

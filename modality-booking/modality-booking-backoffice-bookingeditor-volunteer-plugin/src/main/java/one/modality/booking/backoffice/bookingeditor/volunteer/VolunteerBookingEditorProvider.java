package one.modality.booking.backoffice.bookingeditor.volunteer;

import dev.webfx.stack.orm.entity.Entities;
import one.modality.base.shared.entities.Event;
import one.modality.booking.backoffice.bookingeditor.BookingEditor;
import one.modality.booking.backoffice.bookingeditor.spi.BookingEditorProvider;
import one.modality.booking.client.workingbooking.WorkingBooking;

/**
 * @author Bruno Salmon
 */
public final class VolunteerBookingEditorProvider implements BookingEditorProvider {

    @Override
    public boolean acceptBooking(WorkingBooking workingBooking) {
        Event event = workingBooking.getEvent();
        return Entities.samePrimaryKey(event.getTypeId(), 61);
    }

    @Override
    public BookingEditor createBookingEditor(WorkingBooking workingBooking) {
        return new VolunteerBookingEditor(workingBooking);
    }
}

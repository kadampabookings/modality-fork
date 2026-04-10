package one.modality.booking.frontoffice.bookingform;

import one.modality.base.shared.entities.AttendanceMode;
import one.modality.base.shared.entities.Event;
import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;

/**
 * Service provider interface for creating booking forms.
 *
 * <p>Implementations of this interface are discovered via ServiceLoader and matched
 * to events via the BookingForm.code stored in the event's EventType. The provider
 * whose {@link #getBookingFormCode()} matches the event's type.bookingForm.code
 * will be selected to create the booking form.</p>
 *
 * @author Bruno Salmon
 * @see BookingFormEntryPoint
 * @see BookingForm
 */
public interface BookingFormProvider {

    /**
     * Returns the booking form code that this provider handles.
     * Must match a BookingForm.code value in the database.
     * Return null for the default/fallback provider.
     *
     * @return the booking form code, or null for the default provider
     */
    String getBookingFormCode();

    /**
     * Creates a booking form for the given event and entry point.
     *
     * @param event the event to create the form for
     * @param activity the activity providing working booking properties
     * @param entryPoint the booking form entry point context
     * @return the booking form instance
     */
    BookingForm createBookingForm(Event event, HasWorkingBookingProperties activity, BookingFormEntryPoint entryPoint);

    /**
     * Returns the intended attendance mode for bookings created by this provider.
     * Override this method if the form type determines the attendance mode (e.g., online-only forms).
     *
     * @param event the event to check
     * @return the attendance mode to use, or null to use the event's default
     */
    default AttendanceMode getAttendanceMode(Event event) {
        return null;  // Default: use event's default (derived from inPersonAllowed flag)
    }

    /**
     * Returns whether the existing booking for a logged-in user should be auto-loaded.
     * When true, the ExistingBookingSection is shown allowing users to select which
     * family member's booking to modify or create a new one.
     *
     * @param event the event to check
     * @return true to auto-load existing bookings, false otherwise
     */
    default boolean autoLoadExistingBooking(Event event) {
        return false;
    }

    /**
     * Legacy method for backward compatibility - defaults to NEW_BOOKING entry point.
     *
     * @param event the event to create the form for
     * @param activity the activity providing working booking properties
     * @return the booking form instance
     * @deprecated Use {@link #createBookingForm(Event, HasWorkingBookingProperties, BookingFormEntryPoint)} instead
     */
    @Deprecated
    default BookingForm createBookingForm(Event event, HasWorkingBookingProperties activity) {
        return createBookingForm(event, activity, BookingFormEntryPoint.NEW_BOOKING);
    }
}

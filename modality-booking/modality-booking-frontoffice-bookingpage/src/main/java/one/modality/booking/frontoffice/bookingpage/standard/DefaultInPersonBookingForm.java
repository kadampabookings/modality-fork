package one.modality.booking.frontoffice.bookingpage.standard;

import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;
import one.modality.booking.frontoffice.bookingform.BookingFormEntryPoint;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;
import one.modality.event.frontoffice.activities.book.event.EventBookingFormSettings;

/**
 * Default implementation of in-person booking form.
 * Uses event-resolved or specified color scheme with full inherited functionality.
 *
 * <p>This is the fallback in-person form used by {@link DefaultBookingFormProvider}
 * for events without custom providers.</p>
 *
 * <p>The form inherits all functionality from {@link AbstractSinglePeriodInPersonBookingForm}:</p>
 * <ul>
 *   <li>Accommodation selection with availability status</li>
 *   <li>Festival day selection (arrival/departure dates)</li>
 *   <li>Meals selection with dietary options</li>
 *   <li>Transport and additional options</li>
 *   <li>Roommate information for shared rooms</li>
 *   <li>Audio recording coverage</li>
 *   <li>Complete checkout flow</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see AbstractSinglePeriodInPersonBookingForm
 * @see DefaultBookingFormProvider
 */
public class DefaultInPersonBookingForm extends AbstractSinglePeriodInPersonBookingForm {

    /**
     * Creates a default in-person booking form with event-resolved color scheme.
     *
     * @param activity   the activity providing WorkingBookingProperties
     * @param settings   the event booking form settings
     * @param entryPoint the entry point (NEW_BOOKING, MODIFY_BOOKING, etc.)
     */
    public DefaultInPersonBookingForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint) {
        this(activity, settings, entryPoint,
             BookingFormColorScheme.resolveFromEvent(settings.event()));
    }

    /**
     * Creates a default in-person booking form with specified color scheme.
     *
     * @param activity    the activity providing WorkingBookingProperties
     * @param settings    the event booking form settings
     * @param entryPoint  the entry point (NEW_BOOKING, MODIFY_BOOKING, etc.)
     * @param colorScheme the color scheme to use for theming
     */
    public DefaultInPersonBookingForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint,
            BookingFormColorScheme colorScheme) {
        super(activity, settings, entryPoint, colorScheme);
    }
}

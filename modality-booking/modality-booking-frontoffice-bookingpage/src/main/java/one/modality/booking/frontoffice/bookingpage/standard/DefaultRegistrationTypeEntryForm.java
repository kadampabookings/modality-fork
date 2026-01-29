package one.modality.booking.frontoffice.bookingpage.standard;

import one.modality.base.shared.entities.Event;
import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;
import one.modality.booking.frontoffice.bookingform.BookingForm;
import one.modality.booking.frontoffice.bookingform.BookingFormEntryPoint;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;
import one.modality.event.frontoffice.activities.book.event.EventBookingFormSettings;

/**
 * Default registration type entry form that presents In-Person vs Online choice.
 *
 * <p>This form is used by {@link DefaultBookingFormProvider} when an event supports
 * both in-person and online registration ({@code inPersonAllowed=true} and
 * {@code onlineAllowed=true}).</p>
 *
 * <p>The form dynamically swaps to the appropriate booking form based on user selection:</p>
 * <ul>
 *   <li>In-Person → {@link DefaultInPersonBookingForm}</li>
 *   <li>Online → {@link DefaultOnlineEventBookingForm}</li>
 * </ul>
 *
 * <p>The online option is automatically enabled based on the event's
 * {@code isOnlineAllowed()} flag.</p>
 *
 * @author Bruno Salmon
 * @see AbstractRegistrationTypeEntryForm
 * @see DefaultBookingFormProvider
 */
public class DefaultRegistrationTypeEntryForm extends AbstractRegistrationTypeEntryForm {

    private final Event event;

    /**
     * Creates a default registration type entry form with event-resolved color scheme.
     *
     * @param activity   the activity providing WorkingBookingProperties
     * @param settings   the event booking form settings
     * @param entryPoint the entry point (NEW_BOOKING, MODIFY_BOOKING, etc.)
     */
    public DefaultRegistrationTypeEntryForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint) {
        this(activity, settings, entryPoint,
             BookingFormColorScheme.resolveFromEvent(settings.event()));
    }

    /**
     * Creates a default registration type entry form with specified color scheme.
     *
     * @param activity    the activity providing WorkingBookingProperties
     * @param settings    the event booking form settings
     * @param entryPoint  the entry point (NEW_BOOKING, MODIFY_BOOKING, etc.)
     * @param colorScheme the color scheme to use for theming
     */
    public DefaultRegistrationTypeEntryForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint,
            BookingFormColorScheme colorScheme) {
        super(activity, settings, entryPoint, colorScheme);
        this.event = settings.event();
    }

    @Override
    protected BookingForm createInPersonForm() {
        return new DefaultInPersonBookingForm(
            getActivity(),
            (EventBookingFormSettings) settings,
            getEntryPoint(),
            getColorScheme()
        ).getForm();
    }

    @Override
    protected BookingForm createOnlineForm() {
        return new DefaultOnlineEventBookingForm(
            getActivity(),
            (EventBookingFormSettings) settings,
            getEntryPoint(),
            getColorScheme()
        ).getForm();
    }

    @Override
    protected boolean isOnlineEnabled() {
        Boolean onlineAllowed = event.isOnlineAllowed();
        return onlineAllowed != null && onlineAllowed;
    }
}

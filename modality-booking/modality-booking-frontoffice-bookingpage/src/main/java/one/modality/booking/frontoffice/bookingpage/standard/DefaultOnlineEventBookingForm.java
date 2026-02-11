package one.modality.booking.frontoffice.bookingpage.standard;

import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;
import one.modality.booking.frontoffice.bookingform.BookingFormEntryPoint;
import one.modality.booking.frontoffice.bookingpage.BookingPageI18nKeys;
import one.modality.booking.frontoffice.bookingpage.sections.options.DefaultRateTypeSection;
import one.modality.booking.frontoffice.bookingpage.sections.options.HasRateTypeSection;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;
import one.modality.event.frontoffice.activities.book.event.EventBookingFormSettings;

/**
 * Default implementation of online event booking form.
 * Uses event-resolved or specified color scheme with sensible defaults.
 *
 * <p>This is the fallback online form used by {@link DefaultBookingFormProvider}
 * for events without custom providers.</p>
 *
 * <p>The form inherits all functionality from {@link AbstractOnlineEventBookingForm}:</p>
 * <ul>
 *   <li>Event header section showing event details</li>
 *   <li>Rate/pricing section with {@link DefaultRateTypeSection}</li>
 *   <li>Audio recording selection section</li>
 *   <li>Complete checkout flow</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see AbstractOnlineEventBookingForm
 * @see DefaultBookingFormProvider
 */
public class DefaultOnlineEventBookingForm extends AbstractOnlineEventBookingForm {

    /**
     * Creates a default online booking form with specified color scheme.
     *
     * @param activity    the activity providing WorkingBookingProperties
     * @param settings    the event booking form settings
     * @param entryPoint  the entry point (NEW_BOOKING, MODIFY_BOOKING, etc.)
     * @param colorScheme the color scheme to use for theming
     */
    public DefaultOnlineEventBookingForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint,
            BookingFormColorScheme colorScheme) {
        super(activity, settings, entryPoint, colorScheme);
    }

    @Override
    protected HasRateTypeSection createRateTypeSection() {
        DefaultRateTypeSection section = new DefaultRateTypeSection();
        return section;
    }

    @Override
    protected Object getOptionsPageTitleKey() {
        return BookingPageI18nKeys.AvailableOptions;
    }
}

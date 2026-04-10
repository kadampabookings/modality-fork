package one.modality.booking.frontoffice.bookingpage.standard;

import dev.webfx.platform.util.Booleans;
import one.modality.base.shared.entities.Event;
import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;
import one.modality.booking.frontoffice.bookingform.BookingForm;
import one.modality.booking.frontoffice.bookingform.BookingFormEntryPoint;
import one.modality.booking.frontoffice.bookingform.BookingFormProvider;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;
import one.modality.event.frontoffice.activities.book.event.EventBookingFormSettings;
import one.modality.event.frontoffice.activities.book.event.EventBookingFormSettingsBuilder;

/**
 * Default booking form provider at MODALITY_PRIORITY (0).
 *
 * <p>Acts as the fallback for events without custom providers.
 * Custom providers at APP_PRIORITY (10) take precedence.</p>
 *
 * <p>Smart form selection based on event flags:</p>
 * <ul>
 *   <li>{@code inPersonAllowed=true AND onlineAllowed=false} → {@link DefaultInPersonBookingForm}</li>
 *   <li>{@code inPersonAllowed=false AND onlineAllowed=true} → {@link DefaultOnlineEventBookingForm}</li>
 *   <li>BOTH true → {@link DefaultRegistrationTypeEntryForm} (shows choice)</li>
 *   <li>BOTH false/null → {@link DefaultInPersonBookingForm} (fallback)</li>
 * </ul>
 *
 * <p>For MODIFY_BOOKING and PAY_BOOKING entry points, the provider goes directly
 * to the in-person form (future enhancement: detect existing booking type).</p>
 *
 * @author Bruno Salmon
 * @see DefaultInPersonBookingForm
 * @see DefaultOnlineEventBookingForm
 * @see DefaultRegistrationTypeEntryForm
 */
public final class DefaultBookingFormProvider implements BookingFormProvider {

    @Override
    public String getBookingFormCode() {
        return null; // Default/fallback provider — matches when no other provider does
    }

    @Override
    public BookingForm createBookingForm(Event event, HasWorkingBookingProperties activity, BookingFormEntryPoint entryPoint) {
        // Build default settings
        EventBookingFormSettings settings = createDefaultSettings(event);
        BookingFormColorScheme colorScheme = BookingFormColorScheme.resolveFromEvent(event);

        // Determine registration flags
        boolean inPersonAllowed = Booleans.isTrue(event.isInPersonAllowed());
        boolean onlineAllowed = Booleans.isTrue(event.isOnlineAllowed());

        // For NEW_BOOKING, apply smart form selection
        if (entryPoint == BookingFormEntryPoint.NEW_BOOKING) {
            return createNewBookingForm(activity, settings, entryPoint, colorScheme,
                                        inPersonAllowed, onlineAllowed);
        }

        // For MODIFY_BOOKING and PAY_BOOKING, default to in-person
        // TODO: Future enhancement - detect if existing booking is online or in-person from document
        return createInPersonForm(activity, settings, entryPoint, colorScheme);
    }

    /**
     * Creates the appropriate form for new bookings based on event flags.
     */
    private BookingForm createNewBookingForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint,
            BookingFormColorScheme colorScheme,
            boolean inPersonAllowed,
            boolean onlineAllowed) {

        if (inPersonAllowed && onlineAllowed) {
            // Both allowed: show registration type choice
            return new DefaultRegistrationTypeEntryForm(activity, settings, entryPoint, colorScheme);
        } else if (onlineAllowed && !inPersonAllowed) {
            // Online only: go directly to online form
            return createOnlineForm(activity, settings, entryPoint, colorScheme);
        } else {
            // In-person only (or neither - fallback to in-person)
            return createInPersonForm(activity, settings, entryPoint, colorScheme);
        }
    }

    /**
     * Creates an in-person booking form.
     */
    private BookingForm createInPersonForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint,
            BookingFormColorScheme colorScheme) {
        return new DefaultInPersonBookingForm(activity, settings, entryPoint, colorScheme).getForm();
    }

    /**
     * Creates an online booking form.
     */
    private BookingForm createOnlineForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint,
            BookingFormColorScheme colorScheme) {
        return new DefaultOnlineEventBookingForm(activity, settings, entryPoint, colorScheme).getForm();
    }

    /**
     * Creates default event booking form settings.
     */
    private EventBookingFormSettings createDefaultSettings(Event event) {
        return new EventBookingFormSettingsBuilder(event)
            .setHeaderMaxTopBottomPadding(62)
            .setShowNavigationBar(true)
            .setShowPriceBar(false)
            .setPartialEventAllowed(true)   // Allow partial attendance by default
            .setBookAsAGuestAllowed(false)  // Require account by default
            .build();
    }
}

package one.modality.booking.frontoffice.bookingpage.standard;

import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;
import one.modality.booking.frontoffice.bookingform.BookingForm;
import one.modality.booking.frontoffice.bookingform.BookingFormEntryPoint;
import one.modality.booking.frontoffice.bookingpage.AbstractEntryForm;
import one.modality.booking.frontoffice.bookingpage.sections.options.DefaultRegistrationTypeSection;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;
import one.modality.event.frontoffice.activities.book.event.EventBookingFormSettings;

/**
 * Abstract base class for entry forms that present an In-Person vs Online registration choice.
 *
 * <p>This class provides the common infrastructure for registration type selection forms:</p>
 * <ul>
 *   <li>Automatic UI setup with {@link DefaultRegistrationTypeSection}</li>
 *   <li>Color scheme integration</li>
 *   <li>Callback setup for type selection</li>
 *   <li>Form swapping based on user choice</li>
 * </ul>
 *
 * <p>Subclasses must implement:</p>
 * <ul>
 *   <li>{@link #createInPersonForm()} - Creates the in-person booking form</li>
 *   <li>{@link #createOnlineForm()} - Creates the online booking form</li>
 * </ul>
 *
 * <p>Subclasses may optionally override:</p>
 * <ul>
 *   <li>{@link #isOnlineEnabled()} - Whether online registration is enabled (default: false)</li>
 *   <li>{@link #configureRegistrationTypeSection(DefaultRegistrationTypeSection)} - Additional section configuration</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see AbstractEntryForm
 * @see DefaultRegistrationTypeSection
 */
public abstract class AbstractRegistrationTypeEntryForm extends AbstractEntryForm {

    /** The registration type section showing In-Person vs Online choice. */
    private DefaultRegistrationTypeSection registrationTypeSection;

    // ========================================
    // Abstract Methods (Required)
    // ========================================

    /**
     * Creates the in-person booking form to swap to when user selects In-Person.
     *
     * <p>The returned form can be any {@link BookingForm} implementation. If returning
     * a wrapper class (like {@code AbstractSinglePeriodInPersonBookingForm}), use
     * {@code wrapper.getForm()} to get the underlying {@link BookingForm}.</p>
     *
     * @return the booking form for in-person registration
     */
    protected abstract BookingForm createInPersonForm();

    /**
     * Creates the online booking form to swap to when user selects Online.
     *
     * <p>The returned form can be any {@link BookingForm} implementation.</p>
     *
     * @return the booking form for online registration
     */
    protected abstract BookingForm createOnlineForm();

    // ========================================
    // Override Points (Optional)
    // ========================================

    /**
     * Returns whether online registration is enabled.
     * When false (default), the online card shows "Coming Soon" and is not selectable.
     *
     * <p>Override to return true when online registration is ready.</p>
     *
     * @return true if online registration is enabled, false otherwise
     */
    protected boolean isOnlineEnabled() {
        return false;
    }

    /**
     * Configures the registration type section after creation.
     * Override to add custom configuration (e.g., custom labels, callbacks).
     *
     * <p>Called after the section is created and color scheme is set,
     * but before the type selection callback is configured.</p>
     *
     * @param section the registration type section to configure
     */
    protected void configureRegistrationTypeSection(DefaultRegistrationTypeSection section) {
        // Default: no additional configuration
    }

    // ========================================
    // Constructor
    // ========================================

    /**
     * Creates a new registration type entry form.
     *
     * @param activity    the activity providing WorkingBookingProperties
     * @param settings    the event booking form settings
     * @param entryPoint  the entry point (NEW_BOOKING, MODIFY_BOOKING, etc.)
     * @param colorScheme the color scheme to use for theming
     */
    protected AbstractRegistrationTypeEntryForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint,
            BookingFormColorScheme colorScheme) {
        super(activity, settings, entryPoint);
        setColorScheme(colorScheme);
    }

    // ========================================
    // AbstractEntryForm Implementation
    // ========================================

    @Override
    protected final void initializeContent() {
        // Create the registration type section
        registrationTypeSection = new DefaultRegistrationTypeSection();
        registrationTypeSection.setColorScheme(getColorScheme());
        registrationTypeSection.setOnlineEnabled(isOnlineEnabled());

        // Allow subclass customization
        configureRegistrationTypeSection(registrationTypeSection);

        // Add to container
        getContainer().getChildren().add(registrationTypeSection.getView());

        // Set up type selection callback
        setupCallbacks();
    }

    @Override
    public void onWorkingBookingLoaded() {
        // Ensure content is initialized (buildUi triggers initializeContent)
        buildUi();
        // Pass working booking properties to registration type section for event header display
        registrationTypeSection.setWorkingBookingProperties(getActivity().getWorkingBookingProperties());
    }

    // ========================================
    // Internal Methods
    // ========================================

    private void setupCallbacks() {
        registrationTypeSection.setOnTypeSelected(type -> {
            if (type == DefaultRegistrationTypeSection.RegistrationType.IN_PERSON) {
                swapToForm(createInPersonForm());
            } else if (type == DefaultRegistrationTypeSection.RegistrationType.ONLINE) {
                swapToForm(createOnlineForm());
            }
        });

        // Override the continue callback to prevent auto-navigation within RegistrationTypeSection
        // The form swap handles the navigation
        registrationTypeSection.setOnContinuePressed(null);
    }

    // ========================================
    // Protected Accessors
    // ========================================

    /**
     * Returns the registration type section.
     * Useful for subclasses that need direct access.
     *
     * @return the registration type section, or null if not yet initialized
     */
    protected DefaultRegistrationTypeSection getRegistrationTypeSection() {
        return registrationTypeSection;
    }
}

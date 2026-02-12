package one.modality.booking.frontoffice.bookingpage.standard;

import dev.webfx.extras.i18n.I18n;
import dev.webfx.platform.async.Future;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import one.modality.base.shared.entities.AttendanceMode;
import one.modality.base.shared.entities.BookablePeriod;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.ItemFamilyPolicy;
import one.modality.base.shared.entities.ScheduledItem;
import one.modality.base.shared.knownitems.KnownItemFamily;
import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;
import one.modality.booking.client.workingbooking.WorkingBooking;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingform.BookingFormEntryPoint;
import one.modality.booking.frontoffice.bookingpage.BookingFormButton;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;
import one.modality.booking.frontoffice.bookingpage.BookingPageI18nKeys;
import one.modality.booking.frontoffice.bookingpage.CompositeBookingFormPage;
import one.modality.booking.frontoffice.bookingpage.sections.audio.DefaultAudioRecordingSection;
import one.modality.booking.frontoffice.bookingpage.sections.audio.HasAudioRecordingSection;
import one.modality.booking.frontoffice.bookingpage.sections.options.DefaultRateTypeSection;
import one.modality.booking.frontoffice.bookingpage.sections.options.HasRateTypeSection;
import one.modality.booking.frontoffice.bookingpage.sections.prerequisite.DefaultPrerequisiteSection;
import one.modality.booking.frontoffice.bookingpage.sections.prerequisite.HasPrerequisiteSection;
import one.modality.booking.frontoffice.bookingpage.sections.summary.DefaultEventHeaderSection;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;
import one.modality.ecommerce.policy.service.PolicyAggregate;
import one.modality.event.frontoffice.activities.book.event.EventBookingFormSettings;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Abstract base class for online event booking forms.
 *
 * <p>An "Online Event" booking typically includes:</p>
 * <ul>
 *   <li>Event header section</li>
 *   <li>Optional prerequisite/confirmation section</li>
 *   <li>Rate/pricing section</li>
 *   <li>Audio recording selection section</li>
 * </ul>
 *
 * <p>This abstract class provides:</p>
 * <ul>
 *   <li>Single "Options" page combining all custom sections</li>
 *   <li>Integration with StandardBookingForm for checkout flow</li>
 *   <li>CSS-based theming via color scheme</li>
 *   <li>Communication between rate selection and audio recording</li>
 * </ul>
 *
 * <p>Subclasses must implement:</p>
 * <ul>
 *   <li>{@link #getColorScheme()} - returns the color theme for the form</li>
 *   <li>{@link #createRateTypeSection()} - creates the rate/pricing section</li>
 *   <li>{@link #getOptionsPageTitleKey()} - returns i18n key for options page</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see StandardBookingForm
 * @see StandardBookingFormCallbacks
 */
public abstract class AbstractOnlineEventBookingForm implements StandardBookingFormCallbacks {

    // === Core Dependencies ===

    /** The StandardBookingForm instance that handles the checkout flow. */
    protected final StandardBookingForm form;

    /** The event booking form settings. */
    protected final EventBookingFormSettings settings;

    /** The entry point (NEW_BOOKING, MODIFY_BOOKING, PAY_BOOKING). */
    protected final BookingFormEntryPoint entryPoint;

    /** The color scheme for theming the form. */
    protected final BookingFormColorScheme colorScheme;

    // === Section Instances ===

    /** Event header section showing event name, dates, location. */
    protected DefaultEventHeaderSection eventHeaderSection;

    /** Optional prerequisite/confirmation section. */
    protected HasPrerequisiteSection prerequisiteSection;

    /** Rate/pricing selection section. */
    protected HasRateTypeSection rateTypeSection;

    /** Audio recording selection section. */
    protected HasAudioRecordingSection audioRecordingSection;

    // === Page ===

    /** The Options page combining all custom sections. */
    protected CompositeBookingFormPage optionsPage;

    /**
     * Returns the color scheme for this booking form.
     *
     * @return the color scheme to use for theming
     */
    protected BookingFormColorScheme getColorScheme() {
        return colorScheme;
    }

    // ========================================
    // Abstract Methods (Required)
    // ========================================

    /**
     * Creates the rate/pricing section.
     * The section should implement {@link HasRateTypeSection}.
     *
     * @return the rate type section
     */
    protected abstract HasRateTypeSection createRateTypeSection();

    /**
     * Returns the i18n key for the Options page title.
     *
     * @return the i18n key object
     */
    protected abstract Object getOptionsPageTitleKey();

    // ========================================
    // Override Points (Optional)
    // ========================================

    /**
     * Creates the prerequisite/confirmation section.
     * This implementation creates a DefaultPrerequisiteSection that configures itself
     * from ItemFamilyPolicy (TEACHING) when WorkingBookingProperties becomes available.
     *
     * <p>The section will display:</p>
     * <ul>
     *   <li>Livestream-only warning if vodEnabled is false</li>
     *   <li>Notice text from policy's noticeLabel</li>
     *   <li>Description from policy's prerequisiteDescriptionLabel</li>
     *   <li>Custom confirmation text from policy's prerequisiteConfirmationLabel</li>
     * </ul>
     *
     * @return the prerequisite section that configures from policy
     */
    protected HasPrerequisiteSection createPrerequisiteSection() {
        // Create a section that will configure itself from policy when props arrive
        return new DefaultPrerequisiteSection() {
            @Override
            public void setWorkingBookingProperties(WorkingBookingProperties props) {
                super.setWorkingBookingProperties(props);
                configurePrerequisiteSectionFromPolicy(this, props);
            }
        };
    }

    /**
     * Creates the audio recording section.
     * Default creates a {@link DefaultAudioRecordingSection}.
     *
     * @return the audio recording section
     */
    protected HasAudioRecordingSection createAudioRecordingSection() {
        return new DefaultAudioRecordingSection();
    }

    /**
     * Configures the StandardBookingFormBuilder.
     * Override to customize the builder with additional options.
     *
     * @param builder the builder to configure
     */
    protected void configureBuilder(StandardBookingFormBuilder builder) {
        builder.withCardPaymentOnly(true);
        builder.withFullPaymentOnly(true);
    }

    /**
     * Sets up communication between sections after they're created.
     * Default links package selection to audio recording section and calculates prices.
     *
     * <p>Override to add custom communication logic.</p>
     */
    protected void setupSectionCommunication() {
        if (rateTypeSection != null) {
            // Set up callback for when WorkingBookingProperties becomes available
            if (rateTypeSection instanceof DefaultRateTypeSection) {
                DefaultRateTypeSection section = (DefaultRateTypeSection) rateTypeSection;
                section.setOnDataLoadRequested(this::loadTeachingProgramme);
            }

            // Set up callback for rate type changes
            rateTypeSection.setOnRateTypeChanged(this::onRateTypeChanged);

            // Set up callback for package selection (notifies audio recording section)
            if (audioRecordingSection != null) {
                rateTypeSection.setOnPackageSelected(period -> {
                    audioRecordingSection.setSelectedProgramme(period);
                });
            }
        }
    }

    /**
     * Called when the user selects a different rate type.
     * Applies the rate to WorkingBooking and updates the total.
     *
     * @param rateType the selected rate type
     */
    protected void onRateTypeChanged(HasRateTypeSection.RateType rateType) {
        WorkingBookingProperties props = form.getWorkingBookingProperties();
        if (props == null || props.getWorkingBooking() == null) return;

        // Apply facility fee rate (Member rate uses facility fee)
        boolean isMember = (rateType == HasRateTypeSection.RateType.MEMBER);
        props.getWorkingBooking().applyFacilityFeeRate(isMember);
        props.updateTotal();
    }

    /**
     * Loads the teaching programme and books items into WorkingBooking.
     * Called when WorkingBookingProperties becomes available.
     *
     * @param props the WorkingBookingProperties
     */
    protected void loadTeachingProgramme(WorkingBookingProperties props) {
        if (props == null) return;

        PolicyAggregate policyAggregate = props.getPolicyAggregate();
        if (policyAggregate == null) return;

        // Get bookable periods with TEACHING items
        List<BookablePeriod> bookablePeriods = policyAggregate.getBookablePeriods(
                KnownItemFamily.TEACHING,
                getTeachingPeriodFilterKey()
        );

        if (bookablePeriods.isEmpty()) {
            // Fallback: try to get periods without filter key
            bookablePeriods = policyAggregate.getBookablePeriods(KnownItemFamily.TEACHING, null);
        }

        if (bookablePeriods.isEmpty()) return;

        // Use the first bookable period
        BookablePeriod period = bookablePeriods.get(0);

        // Get dates from period
        ScheduledItem startItem = period.getStartScheduledItem();
        ScheduledItem endItem = period.getEndScheduledItem();
        LocalDate startDate = startItem != null ? startItem.getDate() : null;
        LocalDate endDate = endItem != null ? endItem.getDate() : null;

        // Get teaching items within the period
        List<ScheduledItem> allTeachingItems = policyAggregate.filterTeachingScheduledItems();
        List<ScheduledItem> periodTeachingItems = filterTeachingItemsForPeriod(
                allTeachingItems, startDate, endDate);

        // Book teaching items into WorkingBooking (MODEL LAYER)
        if (!periodTeachingItems.isEmpty()) {
            props.getWorkingBooking().bookScheduledItems(periodTeachingItems, true);
        }

        // Get event name for display on rate cards
        Event event = props.getWorkingBooking().getEvent();
        String eventName = event != null ? event.getName() : null;

        // Set programme info on the section (UI LAYER)
        if (rateTypeSection instanceof DefaultRateTypeSection) {
            DefaultRateTypeSection section = (DefaultRateTypeSection) rateTypeSection;
            section.setProgrammeInfo(period, eventName, startDate, endDate);
        }

        // Calculate and set prices
        calculateAndSetRatePrices();

        // Notify package selected callback (for audio recording section)
        if (rateTypeSection != null) {
            Consumer<BookablePeriod> callback = getOnPackageSelectedCallback();
            if (callback != null) {
                callback.accept(period);
            }
        }
    }

    /**
     * Returns the i18n key used to filter BookablePeriods for teaching items.
     * Override to use a custom filter key.
     *
     * @return the filter key, or null for no filtering
     */
    protected Object getTeachingPeriodFilterKey() {
        return BookingPageI18nKeys.FullProgramme;
    }

    /**
     * Filters teaching items to only those within the given date range.
     *
     * @param allItems all teaching items
     * @param startDate start date (inclusive)
     * @param endDate end date (inclusive)
     * @return filtered list of teaching items
     */
    protected List<ScheduledItem> filterTeachingItemsForPeriod(
            List<ScheduledItem> allItems, LocalDate startDate, LocalDate endDate) {
        if (allItems == null || startDate == null || endDate == null) {
            return allItems != null ? allItems : List.of();
        }

        return allItems.stream()
                .filter(item -> item.getDate() != null)
                .filter(item -> {
                    LocalDate itemDate = item.getDate();
                    return !itemDate.isBefore(startDate) && !itemDate.isAfter(endDate);
                })
                .collect(Collectors.toList());
    }

    /**
     * Gets the onPackageSelected callback from the rate section.
     */
    private Consumer<BookablePeriod> getOnPackageSelectedCallback() {
        // The callback is set by setupSectionCommunication() via setOnPackageSelected()
        // We need to manually trigger it here since we're loading the programme
        if (audioRecordingSection != null) {
            return period -> audioRecordingSection.setSelectedProgramme(period);
        }
        return null;
    }

    /**
     * Calculates rate prices using temp WorkingBooking and sets them on the section.
     * This lets PriceCalculator handle discounts, policies, etc.
     *
     * <p>This method creates temporary WorkingBooking instances (one for standard rate,
     * one for member rate), books the teaching items, and uses PriceCalculator to
     * compute the actual prices including any applicable discounts.</p>
     */
    protected void calculateAndSetRatePrices() {
        if (rateTypeSection == null) return;

        WorkingBookingProperties props = form.getWorkingBookingProperties();
        if (props == null) return;

        PolicyAggregate policyAggregate = props.getPolicyAggregate();
        if (policyAggregate == null) return;

        // Get teaching items from PolicyAggregate
        List<ScheduledItem> teachingItems = policyAggregate.filterTeachingScheduledItems();
        if (teachingItems == null || teachingItems.isEmpty()) return;

        // Calculate STANDARD price (no facility fee)
        WorkingBooking tempStandard = new WorkingBooking(policyAggregate, AttendanceMode.ONLINE);
        tempStandard.bookScheduledItems(teachingItems, true);
        int standardPrice = tempStandard.getLatestBookingPriceCalculator().calculateTotalPrice();

        // Calculate MEMBER price (with facility fee)
        WorkingBooking tempMember = new WorkingBooking(policyAggregate, AttendanceMode.ONLINE);
        tempMember.bookScheduledItems(teachingItems, true);
        tempMember.applyFacilityFeeRate(true);
        int memberPrice = tempMember.getLatestBookingPriceCalculator().calculateTotalPrice();

        // Set prices on section
        rateTypeSection.setStandardPrice(standardPrice);
        rateTypeSection.setMemberPrice(memberPrice);
    }

    // ========================================
    // Constructor
    // ========================================

    /**
     * Creates a new online event booking form.
     *
     * @param activity    the activity providing WorkingBookingProperties
     * @param settings    the event booking form settings
     * @param entryPoint  the entry point (NEW_BOOKING, MODIFY_BOOKING, PAY_BOOKING)
     * @param colorScheme the color scheme to use for theming
     */
    protected AbstractOnlineEventBookingForm(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint,
            BookingFormColorScheme colorScheme
    ) {
        this.settings = settings;
        this.entryPoint = entryPoint;
        this.colorScheme = colorScheme;

        // Create custom options step
        createCustomStep();

        // Build the form using StandardBookingFormBuilder
        StandardBookingFormBuilder builder = new StandardBookingFormBuilder(activity, settings)
            .withColorScheme(getColorScheme())
            .withEntryPoint(entryPoint)
            .withAttendanceMode(AttendanceMode.ONLINE)  // Online event forms always use ONLINE mode
            .addCustomStep(optionsPage)
            .withCallbacks(this);

        // Let subclass configure additional builder options
        configureBuilder(builder);

        this.form = builder.build();

        // Set up communication between sections
        setupSectionCommunication();
    }

    // ========================================
    // Page Creation
    // ========================================

    /**
     * Creates the custom Options step with all sections.
     */
    private void createCustomStep() {
        // Event Header - shows event name, dates, location
        eventHeaderSection = new DefaultEventHeaderSection();

        // Optional prerequisite section
        prerequisiteSection = createPrerequisiteSection();

        // Rate/pricing section (required)
        rateTypeSection = createRateTypeSection();

        // Audio recording section
        audioRecordingSection = createAudioRecordingSection();

        // Build options page with non-null sections
        List<BookingFormSection> sections = new ArrayList<>();
        sections.add(eventHeaderSection);
        if (prerequisiteSection != null) {
            sections.add(prerequisiteSection);
        }
        sections.add(rateTypeSection);
        if (audioRecordingSection != null) {
            sections.add(audioRecordingSection);
        }

        optionsPage = new CompositeBookingFormPage(
                getOptionsPageTitleKey(),
                sections.toArray(new BookingFormSection[0]))
                .setStep(true)
                .setHeaderVisible(true);

        // Set up navigation button
        optionsPage.setButtons(
            BookingFormButton.async(BookingPageI18nKeys.Continue,
                button -> navigateFromOptionsAsync(),
                "btn-primary booking-form-btn-primary",
                Bindings.not(optionsPage.validProperty())));
    }

    /**
     * Handles navigation from the Options step.
     */
    protected Future<?> navigateFromOptionsAsync() {
        form.continueFromCustomSteps();
        return Future.succeededFuture();
    }

    // ========================================
    // StandardBookingFormCallbacks Implementation
    // ========================================

    @Override
    public void onBeforeSummary() {
        configureTerms();
    }

    /**
     * Configures the terms text and URL from the event's termsUrlEn field.
     * Called from onBeforeSummary() before the summary page is displayed.
     */
    protected void configureTerms() {
        form.setTermsText(I18n.getI18nText(BookingPageI18nKeys.AcceptBookingTermsText));

        WorkingBookingProperties props = form.getWorkingBookingProperties();
        if (props == null) return;

        PolicyAggregate policyAggregate = props.getPolicyAggregate();
        if (policyAggregate == null) return;

        Event event = policyAggregate.getEvent();
        if (event == null) return;

        String termsUrl = event.getTermsUrlEn();
        if (termsUrl != null && !termsUrl.isEmpty()) {
            form.setTermsUrl(termsUrl);
        }
    }

    // ========================================
    // Public Accessors
    // ========================================

    /**
     * Returns the built StandardBookingForm.
     */
    public StandardBookingForm getForm() {
        return form;
    }

    /**
     * Returns the prerequisite section, if any.
     */
    public HasPrerequisiteSection getPrerequisiteSection() {
        return prerequisiteSection;
    }

    /**
     * Returns the rate type section.
     */
    public HasRateTypeSection getRateTypeSection() {
        return rateTypeSection;
    }

    /**
     * Returns the audio recording section, if any.
     */
    public HasAudioRecordingSection getAudioRecordingSection() {
        return audioRecordingSection;
    }

    // ========================================
    // Policy-Driven Prerequisite Configuration
    // ========================================

    /**
     * Configures a DefaultPrerequisiteSection from ItemFamilyPolicy and Event data.
     * This method is called when WorkingBookingProperties becomes available.
     *
     * @param section the section to configure
     * @param props   the working booking properties containing policy and event data
     */
    protected void configurePrerequisiteSectionFromPolicy(DefaultPrerequisiteSection section, WorkingBookingProperties props) {
        if (props == null) {
            hidePrerequisiteSection(section);
            return;
        }

        PolicyAggregate policyAggregate = props.getPolicyAggregate();
        Event event = props.getEvent();

        // Show livestream-only warning if vodEnabled is false
        Boolean vodEnabled = event != null ? event.isVodEnabled() : null;
        if (Boolean.FALSE.equals(vodEnabled)) {
            section.setLivestreamOnlyWarning(true);
        }

        // Get teaching policy for online events
        ItemFamilyPolicy policy = policyAggregate != null
            ? policyAggregate.getItemFamilyPolicy(KnownItemFamily.TEACHING)
            : null;

        // Configure from policy labels (using localized text)
        if (policy != null) {
            one.modality.base.shared.entities.Label noticeLabel = policy.getNoticeLabel();
            if (noticeLabel != null) {
                section.setNoticeText(getLabelText(noticeLabel));
            }

            one.modality.base.shared.entities.Label prereqDesc = policy.getPrerequisiteDescriptionLabel();
            if (prereqDesc != null) {
                section.setDescriptionText(getLabelText(prereqDesc));
            }

            one.modality.base.shared.entities.Label prereqConfirm = policy.getPrerequisiteConfirmationLabel();
            if (prereqConfirm != null) {
                section.setConfirmationText(getLabelText(prereqConfirm));
            }
        }

        // Hide section if no content to display
        if (!section.hasDynamicContent()) {
            hidePrerequisiteSection(section);
        }
    }

    /**
     * Hides the prerequisite section by making it invisible and non-managed.
     *
     * @param section the section to hide
     */
    private void hidePrerequisiteSection(DefaultPrerequisiteSection section) {
        section.setConfirmed(true); // Mark as valid so it doesn't block page validity
        Node view = section.getView();
        if (view != null) {
            view.setVisible(false);
            view.setManaged(false);
        }
    }

    /**
     * Gets localized text from a Label entity based on current language.
     * Falls back to English if translation not available.
     *
     * @param label the Label entity containing i18n text
     * @return the localized text, or English fallback, or null if label is null
     */
    protected String getLabelText(one.modality.base.shared.entities.Label label) {
        if (label == null) return null;
        Object language = I18n.getLanguage();
        String text = (String) label.getFieldValue(language);
        if (text == null || text.isEmpty()) {
            text = label.getEn(); // Fallback to English
        }
        return text;
    }
}

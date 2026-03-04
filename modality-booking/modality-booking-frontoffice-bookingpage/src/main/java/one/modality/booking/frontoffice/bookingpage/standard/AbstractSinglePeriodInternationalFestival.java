package one.modality.booking.frontoffice.bookingpage.standard;

import dev.webfx.extras.i18n.I18n;
import dev.webfx.extras.i18n.controls.I18nControls;
import dev.webfx.platform.uischeduler.UiScheduler;
import dev.webfx.stack.orm.entity.Entities;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import one.modality.base.shared.entities.*;
import one.modality.base.shared.entities.util.ScheduledItems;
import one.modality.base.shared.knownitems.KnownItemFamily;
import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;
import one.modality.booking.client.workingbooking.WorkingBooking;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingform.BookingFormEntryPoint;
import one.modality.booking.frontoffice.bookingpage.*;
import one.modality.booking.frontoffice.bookingpage.components.BookingPageUIBuilder;
import one.modality.booking.frontoffice.bookingpage.components.StickyPriceHeader;
import one.modality.booking.frontoffice.bookingpage.components.ValidationWarningZone;
import one.modality.booking.frontoffice.bookingpage.sections.accommodation.AccommodationPageHeaderSection;
import one.modality.booking.frontoffice.bookingpage.sections.accommodation.DefaultAccommodationSelectionSection;
import one.modality.booking.frontoffice.bookingpage.sections.accommodation.HasAccommodationSelectionSection;
import one.modality.booking.frontoffice.bookingpage.sections.assistance.DefaultAssistanceNeedsSection;
import one.modality.booking.frontoffice.bookingpage.sections.audio.DefaultAudioRecordingPhaseCoverageSection;
import one.modality.booking.frontoffice.bookingpage.sections.booking.BookingDetailsPageHeaderSection;
import one.modality.booking.frontoffice.bookingpage.sections.childcarer.DefaultChildCarerSection;
import one.modality.booking.frontoffice.bookingpage.sections.childcarer.HasChildCarerSection;
import one.modality.booking.frontoffice.bookingpage.sections.dates.DefaultFestivalDaySelectionSection;
import one.modality.booking.frontoffice.bookingpage.sections.meals.DefaultMealsSelectionSection;
import one.modality.booking.frontoffice.bookingpage.sections.member.DefaultMemberSelectionSection;
import one.modality.booking.frontoffice.bookingpage.sections.ordination.DefaultOrdinationCeremonySection;
import one.modality.booking.frontoffice.bookingpage.sections.roommate.DefaultRoommateInfoSection;
import one.modality.booking.frontoffice.bookingpage.sections.summary.DefaultEventHeaderSection;
import one.modality.booking.frontoffice.bookingpage.sections.translation.DefaultTranslationSection;
import one.modality.booking.frontoffice.bookingpage.sections.transport.DefaultTransportSection;
import one.modality.booking.frontoffice.bookingpage.sections.user.DefaultYourInformationSection;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;
import one.modality.crm.shared.services.authn.fx.FXUserPerson;
import one.modality.ecommerce.policy.service.PolicyAggregate;
import one.modality.event.frontoffice.activities.book.event.EventBookingFormSettings;

import one.modality.base.shared.entities.Period;
import one.modality.base.shared.entities.util.ScheduledBoundaries;
import one.modality.booking.frontoffice.bookingpage.sections.dates.HasFestivalDaySelectionSection;
import one.modality.booking.frontoffice.bookingpage.util.BookingDateFormatter;
import one.modality.ecommerce.shared.pricecalculator.PriceCalculator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

import static one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors.*;

/**
 * Abstract base class for international festival booking forms.
 *
 * <p>This class extends the single-period in-person booking pattern with features
 * specific to international festivals:</p>
 * <ul>
 *   <li>Member selection as the first step (who is this booking for?)</li>
 *   <li>Child carer selection (required when booking for children)</li>
 *   <li>Translation or hard of hearing assistance</li>
 *   <li>Ordination ceremony registration (for ordained people)</li>
 *   <li>Accessibility assistance needs</li>
 * </ul>
 *
 * <p>Flow for NEW_BOOKING:</p>
 * <ol>
 *   <li>Member Selection - who is this booking for?</li>
 *   <li>Child Carer (conditional) - only shown if booking for a child</li>
 *   <li>Accommodation Selection</li>
 *   <li>Booking Details (dates, ordination, meals, translation, transport, audio, assistance, roommate)</li>
 *   <li>Your Information (if not logged in) - handled by StandardBookingForm</li>
 *   <li>Summary/Review - handled by StandardBookingForm</li>
 *   <li>Payment - handled by StandardBookingForm</li>
 *   <li>Confirmation - handled by StandardBookingForm</li>
 * </ol>
 *
 * @author Bruno Salmon
 * @see StandardBookingForm
 * @see StandardBookingFormCallbacks
 */
public abstract class AbstractSinglePeriodInternationalFestival implements StandardBookingFormCallbacks {

    // === Core Dependencies ===

    /** The StandardBookingForm instance that handles the checkout flow. */
    protected final StandardBookingForm form;

    /** The working booking properties from the activity. */
    protected final WorkingBookingProperties workingBookingProperties;

    /** The event booking form settings. */
    protected final EventBookingFormSettings settings;

    /** The entry point (NEW_BOOKING, MODIFY_BOOKING, PAY_BOOKING). */
    protected final BookingFormEntryPoint entryPoint;

    /** The color scheme for theming the form. */
    protected final BookingFormColorScheme colorScheme;

    /** The sticky price header shown at the top of the form. */
    protected final StickyPriceHeader stickyPriceHeader;

    // === Section Instances ===

    // Step 1: Sign In Page (only shown when not logged in)
    protected CompositeBookingFormPage signInPage;
    protected DefaultEventHeaderSection signInEventHeader;

    // Step 2: Member Selection Page (Booking For)
    protected CompositeBookingFormPage memberSelectionPage;
    protected DefaultEventHeaderSection memberSelectionEventHeader;
    protected DefaultMemberSelectionSection memberSelectionSection;

    // Step 2: Child Carer Page (conditional)
    protected CompositeBookingFormPage childCarerPage;
    protected DefaultEventHeaderSection childCarerEventHeader;
    protected DefaultChildCarerSection childCarerSection;

    // Step 3: Accommodation Page
    protected CompositeBookingFormPage accommodationPage;
    protected AccommodationPageHeaderSection accommodationPageHeader;
    protected DefaultAccommodationSelectionSection accommodationSection;

    // Step 4: Booking Details Page
    protected CompositeBookingFormPage bookingDetailsPage;
    protected BookingDetailsPageHeaderSection bookingDetailsPageHeader;
    protected DefaultFestivalDaySelectionSection festivalDaySection;
    protected DefaultOrdinationCeremonySection ordinationSection;
    protected DefaultMealsSelectionSection mealsSection;
    protected DefaultTranslationSection translationSection;
    protected DefaultTransportSection transportSection;
    protected DefaultAudioRecordingPhaseCoverageSection audioSection;
    protected DefaultAssistanceNeedsSection assistanceSection;
    protected DefaultRoommateInfoSection roommateSection;

    // Your Information section (used when not logged in)
    protected DefaultEventHeaderSection yourInfoEventHeader;
    protected DefaultYourInformationSection yourInformationSection;

    // Validation warning zones
    protected ValidationWarningZone memberSelectionWarningZone;
    protected ValidationWarningZone childCarerWarningZone;
    protected ValidationWarningZone accommodationWarningZone;
    protected ValidationWarningZone bookingDetailsWarningZone;

    // === Event Boundary Dates ===
    protected LocalDate eventStartDate;
    protected LocalDate eventEndDate;
    protected LocalDate earlyArrivalDate;
    protected LocalDate lateDepartureDate;

    // === Population Flags ===
    protected boolean memberSelectionPopulated = false;
    protected boolean accommodationOptionsPopulated = false;
    protected boolean festivalDaysPopulated = false;
    protected boolean mealsOptionsPopulated = false;
    protected boolean translationOptionsPopulated = false;
    protected boolean transportOptionsPopulated = false;
    protected boolean audioOptionsPopulated = false;

    // === Child Booking State ===
    protected boolean isBookingForChild = false;
    protected int childAge = 0;
    protected String childName = "";

    // ========================================
    // Constructor
    // ========================================

    /**
     * Creates a new international festival booking form.
     *
     * @param activity    the activity providing WorkingBookingProperties
     * @param settings    the event booking form settings
     * @param entryPoint  the entry point (NEW_BOOKING, MODIFY_BOOKING, PAY_BOOKING)
     * @param colorScheme the color scheme to use for theming
     */
    protected AbstractSinglePeriodInternationalFestival(
            HasWorkingBookingProperties activity,
            EventBookingFormSettings settings,
            BookingFormEntryPoint entryPoint,
            BookingFormColorScheme colorScheme
    ) {
        this.settings = settings;
        this.workingBookingProperties = activity.getWorkingBookingProperties();
        this.entryPoint = entryPoint;
        this.colorScheme = colorScheme;

        // Create the sticky price header
        this.stickyPriceHeader = new StickyPriceHeader();
        this.stickyPriceHeader.setColorScheme(getColorScheme());

        // Build the form with custom steps based on entry point
        StandardBookingFormBuilder builder = new StandardBookingFormBuilder(activity, settings)
            .withColorScheme(getColorScheme())
            .withEntryPoint(entryPoint)
            .withStickyHeader(stickyPriceHeader)
            .withSkipMemberSelection(true);  // Skip default - we have our own custom member selection

        // Let subclass configure the builder
        configureBuilder(builder);

        // Handle different entry points
        if (entryPoint == BookingFormEntryPoint.NEW_BOOKING) {
            builder
                .addCustomStep(createSignInPage())           // Step 1: Sign In (first!)
                .addCustomStep(createMemberSelectionPage())  // Step 2: Booking For (child carer inline)
                .addCustomStep(createAccommodationPage())    // Step 3: Accommodation
                .addCustomStep(createBookingDetailsPage());  // Step 4: Booking Details
        } else if (entryPoint == BookingFormEntryPoint.MODIFY_BOOKING) {
            builder.addCustomStep(createModifyNotSupportedPage());
        }

        // Skip default Your Information page - we added our own as a custom step
        builder
            .withYourInformationPageSupplier(this::createSkippedYourInformationPage)
            .withShowCommentsSection(true)
            .withCallbacks(this);

        this.form = builder.build();

        // Bind sections to selection state
        bindSectionsToSelectionState();

        // Wire up section callbacks only for new bookings
        if (entryPoint == BookingFormEntryPoint.NEW_BOOKING) {
            setupMemberSelectionCallbacks();
            setupChildCarerCallbacks();
            setupAccommodationCallbacks();
            setupBookingDetailsCallbacks();
        }
        setupYourInformationCallbacks();

        // Load members immediately if user is already logged in
        loadMembersIfLoggedIn();

        // Listen for logout events
        setupLogoutListener();

        // Set up listener for when WorkingBooking becomes available
        setupWorkingBookingListener();
    }

    /**
     * Returns the color scheme for this booking form.
     */
    protected BookingFormColorScheme getColorScheme() {
        return colorScheme;
    }

    /**
     * Returns the view (root node) of the booking form.
     */
    public Node getView() {
        return form.getView();
    }

    /**
     * Returns the built StandardBookingForm for use as a BookingForm.
     * This method returns the underlying form which implements BookingForm
     * through StandardBookingForm → MultiPageBookingForm → BookingFormBase → BookingForm.
     *
     * @return the StandardBookingForm instance
     */
    public StandardBookingForm getForm() {
        return form;
    }

    // ========================================
    // Abstract Methods
    // ========================================

    /**
     * Returns the available translation languages for this event.
     * Subclasses must implement this to provide the list of available languages.
     *
     * @return list of available language names
     */
    protected abstract List<String> getAvailableTranslationLanguages();

    // ========================================
    // Override Points (Optional)
    // ========================================

    /**
     * Configures the StandardBookingFormBuilder.
     */
    protected void configureBuilder(StandardBookingFormBuilder builder) {
        builder.withShowUserBadge(false)
               .withCardPaymentOnly(true)
               .withNavigationClickable(false);
    }

    /**
     * Configures the terms text and URL.
     */
    protected void configureTerms() {
        form.setTermsText(I18n.getI18nText(BookingPageI18nKeys.AcceptBookingTermsText));

        if (workingBookingProperties == null) return;

        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;

        Event event = policyAggregate.getEvent();
        if (event == null) return;

        String termsUrl = event.getTermsUrlEn();
        if (termsUrl != null && !termsUrl.isEmpty()) {
            form.setTermsUrl(termsUrl);
        }
    }

    // ========================================
    // Page Creation Methods
    // ========================================

    /**
     * Creates the Sign In page (Step 1 - only shown when not logged in).
     * This page contains the login/registration form.
     */
    protected CompositeBookingFormPage createSignInPage() {
        signInEventHeader = new DefaultEventHeaderSection();

        yourInformationSection = new DefaultYourInformationSection();
        yourInformationSection.setBackButtonVisible(false); // First step, no back button

        signInPage = new CompositeBookingFormPage(BookingPageI18nKeys.SignIn,
            signInEventHeader,
            yourInformationSection) {
            @Override
            public boolean isApplicableToBooking(WorkingBooking workingBooking) {
                // Only show Sign In when user is NOT logged in
                return FXUserPerson.getUserPerson() == null;
            }
        }
            .setStep(true)
            .setShowingOwnSubmitButton(true);

        return signInPage;
    }

    /**
     * Creates a non-applicable Your Information page to skip the default one.
     * We use our own Sign In page as the first custom step instead.
     */
    protected CompositeBookingFormPage createSkippedYourInformationPage() {
        return new CompositeBookingFormPage(BookingPageI18nKeys.YourInformation) {
            @Override
            public boolean isApplicableToBooking(WorkingBooking workingBooking) {
                return false; // Never applicable - we have our own Sign In page
            }
        };
    }

    /**
     * Creates the Member Selection page (Step 2 - "Booking For").
     * Child carer selection is shown inline when a child is selected.
     */
    protected CompositeBookingFormPage createMemberSelectionPage() {
        memberSelectionEventHeader = new DefaultEventHeaderSection();

        memberSelectionSection = new DefaultMemberSelectionSection();
        memberSelectionSection.setBackButtonVisible(true); // Back button to go to Sign In
        memberSelectionSection.setInlineChildCarerEnabled(true); // Show child carer inline

        memberSelectionPage = new CompositeBookingFormPage("BookingFor",
            memberSelectionEventHeader,
            memberSelectionSection)
            .setStep(true)
            .setShowingOwnSubmitButton(true);

        return memberSelectionPage;
    }

    /**
     * Creates the Child Carer page (Step 2 - conditional).
     */
    protected CompositeBookingFormPage createChildCarerPage() {
        childCarerEventHeader = new DefaultEventHeaderSection();

        childCarerSection = new DefaultChildCarerSection();
        childCarerSection.setVisible(false); // Hidden until a child is selected

        childCarerPage = new CompositeBookingFormPage("ChildCarerSelection",
            childCarerEventHeader,
            childCarerSection) {
            @Override
            public boolean isApplicableToBooking(WorkingBooking workingBooking) {
                return isBookingForChild;
            }
        }
            .setStep(true);

        return childCarerPage;
    }

    /**
     * Creates the Accommodation page (Step 3).
     * Uses centered header matching JSX mockup design.
     */
    protected CompositeBookingFormPage createAccommodationPage() {
        // Use new centered header for accommodation page (matching JSX mockup)
        accommodationPageHeader = new AccommodationPageHeaderSection();

        accommodationSection = new DefaultAccommodationSelectionSection();

        accommodationPage = new CompositeBookingFormPage("Accommodation",
            accommodationPageHeader,
            accommodationSection)
            .setStep(true);

        return accommodationPage;
    }

    /**
     * Creates the Booking Details page (Step 4).
     */
    protected CompositeBookingFormPage createBookingDetailsPage() {
        // Page header: "Booking Details" with subtitle
        bookingDetailsPageHeader = new BookingDetailsPageHeaderSection();

        festivalDaySection = new DefaultFestivalDaySelectionSection();
        festivalDaySection.setTimeSelectionEnabled(false); // International festivals don't need time selection

        ordinationSection = new DefaultOrdinationCeremonySection();
        ordinationSection.setVisible(false); // Hidden until person is known to be ordained

        mealsSection = new DefaultMealsSelectionSection();

        translationSection = new DefaultTranslationSection();

        transportSection = new DefaultTransportSection();
        transportSection.setVisible(false);

        audioSection = new DefaultAudioRecordingPhaseCoverageSection();
        audioSection.setVisible(false);

        assistanceSection = new DefaultAssistanceNeedsSection();

        roommateSection = new DefaultRoommateInfoSection();
        roommateSection.setVisible(false);

        bookingDetailsPage = new CompositeBookingFormPage(BookingPageI18nKeys.BookingDetails,
            bookingDetailsPageHeader,
            festivalDaySection,
            ordinationSection,
            mealsSection,
            translationSection,
            transportSection,
            audioSection,
            assistanceSection,
            roommateSection)
            .setStep(true);

        return bookingDetailsPage;
    }

    /**
     * Creates Your Information page with event header.
     */
    protected CompositeBookingFormPage createYourInformationPageWithHeader() {
        yourInfoEventHeader = new DefaultEventHeaderSection();

        yourInformationSection = new DefaultYourInformationSection();
        yourInformationSection.setBackButtonVisible(true);

        return new CompositeBookingFormPage(BookingPageI18nKeys.YourInformation,
            yourInfoEventHeader,
            yourInformationSection) {
            @Override
            public boolean isApplicableToBooking(WorkingBooking workingBooking) {
                return FXUserPerson.getUserPerson() == null;
            }
        }
            .setStep(true)
            .setShowingOwnSubmitButton(true);
    }

    /**
     * Creates a page for modify-not-supported message.
     */
    protected CompositeBookingFormPage createModifyNotSupportedPage() {
        DefaultEventHeaderSection headerSection = new DefaultEventHeaderSection();

        VBox contentBox = new VBox(24);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.setPadding(new Insets(40, 40, 40, 40));

        Label titleLabel = I18nControls.newLabel(BookingPageI18nKeys.ModifyBookingNotSupportedTitle);
        titleLabel.getStyleClass().addAll(bookingpage_text_3xl, bookingpage_font_bold, bookingpage_text_primary);

        Label subtitleLabel = I18nControls.newLabel(BookingPageI18nKeys.ModifyBookingNotSupported);
        subtitleLabel.getStyleClass().addAll(bookingpage_text_md, bookingpage_text_muted);
        subtitleLabel.setWrapText(true);
        subtitleLabel.setMaxWidth(500);

        HBox instructionsBox = BookingPageUIBuilder.createInfoBox(
            BookingPageI18nKeys.ModifyBookingContactSupport,
            BookingPageUIBuilder.InfoBoxType.INFO);
        instructionsBox.setMaxWidth(500);

        Button goToOrdersButton = BookingPageUIBuilder.createPrimaryButton(BookingPageI18nKeys.GoToOrders);
        goToOrdersButton.setOnAction(e -> dev.webfx.platform.windowhistory.WindowHistory.getProvider().push("/orders"));

        contentBox.getChildren().addAll(titleLabel, subtitleLabel, instructionsBox, goToOrdersButton);

        BookingFormSection infoSection = new BookingFormSection() {
            @Override
            public Object getTitleI18nKey() {
                return BookingPageI18nKeys.ModifyBookingNotSupportedTitle;
            }

            @Override
            public Node getView() {
                return contentBox;
            }

            @Override
            public void setWorkingBookingProperties(WorkingBookingProperties workingBookingProperties) {
                // No-op
            }
        };

        return new CompositeBookingFormPage(BookingPageI18nKeys.ModifyBookingNotSupportedTitle,
            headerSection, infoSection)
            .setStep(false);
    }

    // ========================================
    // Callbacks Setup
    // ========================================

    protected void setupMemberSelectionCallbacks() {
        if (memberSelectionSection == null) return;

        memberSelectionSection.setOnMemberSelected(member -> {
            form.getState().setSelectedMember(member);

            // Check if booking for a child (under 18)
            Person personEntity = member != null ? member.getPersonEntity() : null;
            if (personEntity != null && personEntity.getBirthDate() != null) {
                LocalDate birthDate = personEntity.getBirthDate();
                LocalDate now = LocalDate.now();
                int age = now.getYear() - birthDate.getYear();
                if (now.getDayOfYear() < birthDate.getDayOfYear()) {
                    age--;
                }

                isBookingForChild = age < 18;
                childAge = age;
                childName = member.getName();

                // Update child carer section
                if (childCarerSection != null) {
                    childCarerSection.setChildAge(age);
                    childCarerSection.setChildName(childName);
                    childCarerSection.setVisible(isBookingForChild);
                }

                // Check if person is ordained for ordination ceremony section
                if (ordinationSection != null) {
                    boolean isOrdained = Boolean.TRUE.equals(personEntity.isOrdained());
                    ordinationSection.setIsOrdained(isOrdained);
                }
            } else {
                isBookingForChild = false;
                if (childCarerSection != null) {
                    childCarerSection.setVisible(false);
                }
            }
        });

        memberSelectionSection.setOnContinuePressed(() -> {
            if (isBookingForChild) {
                // Navigate to child carer page
                form.navigateToNextPage();
            } else {
                // Skip child carer, go to accommodation
                form.navigateToNextPage();
            }
        });

        memberSelectionSection.setOnBackPressed(() -> {
            // First step, no back navigation
        });
    }

    protected void setupChildCarerCallbacks() {
        // Get the child carer section - either standalone or inline from member selection
        DefaultChildCarerSection effectiveChildCarerSection = childCarerSection;
        if (effectiveChildCarerSection == null && memberSelectionSection != null) {
            effectiveChildCarerSection = memberSelectionSection.getChildCarerSection();
        }
        if (effectiveChildCarerSection == null) return;

        effectiveChildCarerSection.setOnSelectionChanged(() -> {
            commitChildCarerInfoToWorkingBooking();
        });
    }

    protected void setupAccommodationCallbacks() {
        if (accommodationSection == null) return;

        accommodationSection.setOnOptionSelected(option -> {
            bookAccommodationItemsOnly();
            autoIncludeBreakfastWithAccommodation();
            updateStickyPriceHeader();
            updateRoommateSectionVisibility();
        });
    }

    protected void setupBookingDetailsCallbacks() {
        setupFestivalDayCallbacks();
        setupMealsCallbacks();
        setupTranslationCallbacks();
        setupOrdinationCallbacks();
        setupTransportCallbacks();
        setupAudioCallbacks();
        setupAssistanceCallbacks();
        setupBookingDetailsButtons();
    }

    protected void setupFestivalDayCallbacks() {
        if (festivalDaySection == null) return;

        festivalDaySection.arrivalDateProperty().addListener((obs, old, newVal) -> {
            // Only book teachings after initial population (user interaction)
            if (festivalDaysPopulated) {
                bookTeachingItemsOnly();
            }
            updateStickyPriceHeader();
        });

        festivalDaySection.departureDateProperty().addListener((obs, old, newVal) -> {
            // Only book teachings after initial population (user interaction)
            if (festivalDaysPopulated) {
                bookTeachingItemsOnly();
            }
            updateStickyPriceHeader();
        });
    }

    protected void setupMealsCallbacks() {
        if (mealsSection == null) return;

        mealsSection.wantsBreakfastProperty().addListener((obs, old, newVal) -> {
            bookMealsItemsOnly();
        });

        mealsSection.wantsLunchProperty().addListener((obs, old, newVal) -> {
            bookMealsItemsOnly();
        });

        mealsSection.wantsDinnerProperty().addListener((obs, old, newVal) -> {
            bookMealsItemsOnly();
        });
    }

    protected void setupTranslationCallbacks() {
        if (translationSection == null) return;

        translationSection.setOnSelectionChanged(() -> {
            // Translation selection is stored in BookingSelectionState, booked in onBeforeSummary
        });
    }

    protected void setupOrdinationCallbacks() {
        if (ordinationSection == null) return;

        ordinationSection.setOnSelectionChanged(() -> {
            // Ordination selection is stored in BookingSelectionState, booked in onBeforeSummary
        });
    }

    protected void setupTransportCallbacks() {
        if (transportSection == null) return;

        transportSection.setOnSelectionChanged(() -> {
            // Transport selection is stored in BookingSelectionState, booked in onBeforeSummary
        });
    }

    protected void setupAudioCallbacks() {
        if (audioSection == null) return;

        audioSection.setOnOptionSelected(option -> {
            // Audio selection is stored in BookingSelectionState, booked in onBeforeSummary
        });
    }

    protected void setupAssistanceCallbacks() {
        if (assistanceSection == null) return;

        assistanceSection.setOnSelectionChanged(() -> {
            // Assistance needs don't affect booking items directly
        });
    }

    protected void setupBookingDetailsButtons() {
        if (bookingDetailsPage == null) return;

        bookingDetailsWarningZone = new ValidationWarningZone();
        if (roommateSection != null) {
            bookingDetailsWarningZone.addValidationSource(
                roommateSection.validProperty(),
                roommateSection::getValidationMessage
            );
        }

        bookingDetailsPage.setFooterContent(bookingDetailsWarningZone);
        bookingDetailsPage.setButtons(
            new BookingFormButton(BookingPageI18nKeys.Back,
                e -> form.navigateToPreviousPage(),
                "btn-back booking-form-btn-back"),
            new BookingFormButton(BookingPageI18nKeys.Continue,
                e -> form.navigateToNextPage(),
                "btn-primary booking-form-btn-primary",
                Bindings.not(bookingDetailsPage.validProperty()))
        );
    }

    protected void setupYourInformationCallbacks() {
        if (yourInformationSection == null) return;

        yourInformationSection.setOnLoginSuccess(person -> {
            form.getState().setLoggedInPerson(person);
            onAfterLogin();
            // Navigate to next page (Booking For) instead of skipping to Summary
            form.navigateToNextPage();
        });

        yourInformationSection.setOnNewUserContinue(newUserData -> {
            form.getState().setPendingNewUserData(newUserData);
            // Navigate to next page (Booking For) instead of skipping to Summary
            form.navigateToNextPage();
        });

        yourInformationSection.setOnBackPressed(() -> form.navigateToPreviousPage());
    }

    protected void setupLogoutListener() {
        FXUserPerson.userPersonProperty().addListener((obs, oldPerson, newPerson) -> {
            if (oldPerson != null && newPerson == null) {
                if (workingBookingProperties != null && workingBookingProperties.getWorkingBooking() != null) {
                    form.navigateToYourInformation();
                }
            }
        });
    }

    protected void loadMembersIfLoggedIn() {
        Person person = FXUserPerson.getUserPerson();
        if (person != null && memberSelectionSection != null) {
            AccountMemberLoader.loadMembersAsync(person, memberSelectionSection, settings.event());
        }
    }

    // ========================================
    // WorkingBooking Listener & Selection State Binding
    // ========================================

    protected void setupWorkingBookingListener() {
        if (workingBookingProperties == null) return;

        if (entryPoint != BookingFormEntryPoint.NEW_BOOKING) {
            if (stickyPriceHeader != null) {
                stickyPriceHeader.totalPriceProperty().bind(workingBookingProperties.totalProperty());
            }
            return;
        }

        workingBookingProperties.totalProperty().addListener((obs, oldValue, newValue) -> {
            populateAllOptions();
        });

        if (stickyPriceHeader != null) {
            stickyPriceHeader.totalPriceProperty().bind(workingBookingProperties.totalProperty());
        }

        if (workingBookingProperties.getWorkingBooking() != null) {
            populateAllOptions();
        }
    }

    protected void bindSectionsToSelectionState() {
        var selectionState = form.getSelectionState();

        if (accommodationSection != null) {
            accommodationSection.bindToSelectionState(selectionState);
        }
        if (festivalDaySection != null) {
            festivalDaySection.bindToSelectionState(selectionState);
        }
        if (mealsSection != null) {
            mealsSection.bindToSelectionState(selectionState);
        }
        if (roommateSection != null) {
            roommateSection.bindToSelectionState(selectionState);
        }
        if (transportSection != null) {
            transportSection.bindToSelectionState(selectionState);
        }
        if (audioSection != null) {
            audioSection.bindToSelectionState(selectionState);
        }
        if (translationSection != null) {
            translationSection.bindToSelectionState(selectionState);
        }
        if (ordinationSection != null) {
            ordinationSection.bindToSelectionState(selectionState);
        }
        if (assistanceSection != null) {
            assistanceSection.bindToSelectionState(selectionState);
        }
        // Member selection section (includes inline child carer binding when enabled)
        if (memberSelectionSection != null) {
            memberSelectionSection.bindToSelectionState(selectionState);
        }
        // Standalone child carer section (for forms using separate child carer page)
        if (childCarerSection != null) {
            childCarerSection.bindToSelectionState(selectionState);
        }
        if (form.getCommentsSection() != null) {
            form.getCommentsSection().bindToSelectionState(selectionState);
        }
    }

    // ========================================
    // Population Methods
    // ========================================

    protected void populateAllOptions() {
        populateEventBoundaries();
        populateAccommodationOptions();
        populateFestivalDays();
        populateMealsOptions();
        populateTranslationOptions();
        populateTransportOptions();
        populateAudioOptions();
        configureTerms();
    }

    protected void populateEventBoundaries() {
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;

        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;

        Event event = policyAggregate.getEvent();
        if (event != null) {
            eventStartDate = event.getStartDate();
            eventEndDate = event.getEndDate();
        }

        EventPart earlyArrivalPart = policyAggregate.getEarlyArrivalPart();
        if (earlyArrivalPart != null) {
            earlyArrivalDate = earlyArrivalPart.getStartDate();
        }

        EventPart lateDeparturePart = policyAggregate.getLateDeparturePart();
        if (lateDeparturePart != null) {
            lateDepartureDate = lateDeparturePart.getEndDate();
        }
    }

    protected void populateAccommodationOptions() {
        if (accommodationOptionsPopulated || accommodationSection == null) return;
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;

        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;

        LocalDate arrivalDate = eventStartDate;
        LocalDate departureDate = eventEndDate;
        if (arrivalDate == null || departureDate == null) return;

        accommodationSection.clearOptions();

        List<ScheduledItem> accommodationItems = policyAggregate.filterAccommodationScheduledItems();
        Map<Item, List<ScheduledItem>> itemScheduledItemsMap = accommodationItems.stream()
            .filter(si -> si.getItem() != null)
            .collect(Collectors.groupingBy(ScheduledItem::getItem));

        List<Map.Entry<Item, List<ScheduledItem>>> sortedEntries = itemScheduledItemsMap.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey().getOrd() != null ? e.getKey().getOrd() : Integer.MAX_VALUE))
            .collect(Collectors.toList());

        for (Map.Entry<Item, List<ScheduledItem>> entry : sortedEntries) {
            Item item = entry.getKey();
            List<ScheduledItem> scheduledItems = entry.getValue();

            int minAvailability = scheduledItems.stream()
                .mapToInt(si -> si.getGuestsAvailability() != null ? si.getGuestsAvailability() : 0)
                .min()
                .orElse(0);

            HasAccommodationSelectionSection.AvailabilityStatus status;
            if (minAvailability <= 0) {
                status = HasAccommodationSelectionSection.AvailabilityStatus.SOLD_OUT;
            } else if (minAvailability <= 5) {
                status = HasAccommodationSelectionSection.AvailabilityStatus.LIMITED;
            } else {
                status = HasAccommodationSelectionSection.AvailabilityStatus.AVAILABLE;
            }

            ItemPolicy itemPolicy = policyAggregate.getItemPolicy(item);
            HasAccommodationSelectionSection.ConstraintType constraintType = HasAccommodationSelectionSection.ConstraintType.NONE;
            String constraintLabel = null;
            int minNights = 0;

            if (itemPolicy != null && itemPolicy.getMinDay() != null && itemPolicy.getMinDay() > 0) {
                constraintType = HasAccommodationSelectionSection.ConstraintType.MIN_NIGHTS;
                minNights = itemPolicy.getMinDay();
                constraintLabel = I18n.getI18nText(BookingPageI18nKeys.MinNights, minNights);
            }

            // Read early arrival / late departure restrictions from ItemPolicy
            boolean earlyArrivalAllowed = itemPolicy == null || !Boolean.FALSE.equals(itemPolicy.isEarlyAccommodationAllowed());
            boolean lateDepartureAllowed = itemPolicy == null || !Boolean.FALSE.equals(itemPolicy.isLateAccommodationAllowed());

            // Get price and perPerson flag from rates
            Rate itemRate = policyAggregate.filterDailyRatesStreamOfSiteAndItem(null, item)
                .findFirst()
                .orElseGet(() -> policyAggregate.getDailyRatesStream()
                    .filter(r -> r.getItem() != null && Entities.samePrimaryKey(r.getItem(), item))
                    .findFirst()
                    .orElse(null));

            int pricePerNight = itemRate != null && itemRate.getPrice() != null ? itemRate.getPrice() : 0;
            boolean perPerson = itemRate == null || !Boolean.FALSE.equals(itemRate.isPerPerson());

            // Calculate price with breakdown
            AccommodationPriceResult priceResult = calculateAccommodationPriceWithBreakdown(policyAggregate, item, arrivalDate, departureDate, minAvailability);

            // Store the breakdown for this option
            accommodationSection.setBreakdownForOption(item.getPrimaryKey(), priceResult.breakdown);

            HasAccommodationSelectionSection.AccommodationOption option = new HasAccommodationSelectionSection.AccommodationOption(
                item.getPrimaryKey(),
                item,
                item.getName() != null ? item.getName() : "",
                "",
                pricePerNight,
                status,
                constraintType,
                constraintLabel,
                minNights,
                false,
                null,
                perPerson,
                priceResult.totalPrice,
                earlyArrivalAllowed,
                lateDepartureAllowed
            );

            accommodationSection.addAccommodationOption(option);
        }

        // Note: Share Accommodation removed - now users select their attendance type first
        // Day Visitor option is still needed for auto-selection when user chooses Day Visitor attendance type
        addDayVisitorOption(policyAggregate, arrivalDate, departureDate);

        accommodationOptionsPopulated = true;
    }

    protected void populateFestivalDays() {
        if (festivalDaysPopulated || festivalDaySection == null) return;
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;

        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;

        EventPart mainEventPart = findMainEventPart(policyAggregate);

        if (mainEventPart != null) {
            LocalDate mainStartDate = mainEventPart.getStartDate();
            LocalDate mainEndDate = mainEventPart.getEndDate();

            if (mainStartDate != null) {
                festivalDaySection.setMainEventStartDate(mainStartDate);
            }
            if (mainEndDate != null) {
                festivalDaySection.setMainEventEndDate(mainEndDate);
            }
        }

        EventPart lateDeparturePart = policyAggregate.getLateDeparturePart();
        if (lateDeparturePart == null && mainEventPart != null) {
            LocalDate mainEndDate = mainEventPart.getEndDate();
            if (policyAggregate.getEventParts() != null && mainEndDate != null) {
                for (EventPart part : policyAggregate.getEventParts()) {
                    if (Entities.samePrimaryKey(part, mainEventPart)) continue;
                    LocalDate partStartDate = part.getStartDate();
                    if (partStartDate != null && !partStartDate.isBefore(mainEndDate)) {
                        lateDeparturePart = part;
                        break;
                    }
                }
            }
        }

        festivalDaySection.setHasLateDeparturePart(lateDeparturePart != null);

        if (lateDeparturePart != null) {
            LocalDate lateDepartureEndDate = lateDeparturePart.getEndDate();
            if (lateDepartureEndDate != null) {
                festivalDaySection.setLateDepartureEndDate(lateDepartureEndDate);
            }
        }

        festivalDaySection.populateFromPolicyAggregate(policyAggregate);
        festivalDaysPopulated = true;

        // Synchronize initial dates/times to BookingSelectionState
        syncFestivalDaySelectionToState();
    }

    protected void populateMealsOptions() {
        if (mealsOptionsPopulated || mealsSection == null) return;
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;

        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;

        EventPart mainEventPart = findMainEventPart(policyAggregate);

        if (mainEventPart != null) {
            LocalDate mainStartDate = mainEventPart.getStartDate();
            LocalDate mainEndDate = mainEventPart.getEndDate();

            if (mainStartDate != null) {
                mealsSection.setEventBoundaryStartDate(mainStartDate);
            }
            if (mainEndDate != null) {
                mealsSection.setEventBoundaryEndDate(mainEndDate);
            }

            // Extract and set boundary meals from EventPart based on time of day
            ScheduledBoundary startBoundary = mainEventPart.getStartBoundary();
            ScheduledBoundary endBoundary = mainEventPart.getEndBoundary();

            if (startBoundary != null) {
                LocalTime startTime = getBoundaryTime(startBoundary, false);
                if (startTime != null) {
                    mealsSection.setEventBoundaryStartMeal(getMealBoundaryFromTime(startTime));
                }
            }

            if (endBoundary != null) {
                LocalTime endTime = getBoundaryTime(endBoundary, true);
                if (endTime != null) {
                    mealsSection.setEventBoundaryEndMeal(getMealBoundaryFromTime(endTime));
                }
            }

            // Set the main event period for isInPeriod checks
            mealsSection.setMainEventPeriod(mainEventPart);

            List<ScheduledItem> mealItems = policyAggregate.filterScheduledItemsOfFamily(KnownItemFamily.MEALS);
            mealsSection.setMealScheduledItems(mealItems);
        } else {
            if (eventStartDate != null) {
                mealsSection.setEventBoundaryStartDate(eventStartDate);
            }
            if (eventEndDate != null) {
                mealsSection.setEventBoundaryEndDate(eventEndDate);
            }
            List<ScheduledItem> mealItems = policyAggregate.filterScheduledItemsOfFamily(KnownItemFamily.MEALS);
            mealsSection.setMealScheduledItems(mealItems);
        }

        mealsSection.populateFromPolicyAggregate(policyAggregate);

        WorkingBooking workingBooking = workingBookingProperties.getWorkingBooking();
        if (workingBooking != null) {
            mealsSection.setWorkingBooking(workingBooking);
        }

        mealsOptionsPopulated = true;
    }

    protected void populateTranslationOptions() {
        if (translationOptionsPopulated || translationSection == null) return;

        List<String> languages = getAvailableTranslationLanguages();
        if (languages != null && !languages.isEmpty()) {
            translationSection.setAvailableLanguages(languages);
            translationSection.setVisible(true);
        } else {
            translationSection.setVisible(false);
        }

        translationOptionsPopulated = true;
    }

    protected void populateTransportOptions() {
        if (transportOptionsPopulated || transportSection == null) return;
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;

        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;

        transportSection.populateFromPolicyAggregate(policyAggregate);

        if (transportSection.hasAnyOptions()) {
            transportSection.setVisible(true);
        }

        transportOptionsPopulated = true;
    }

    protected void populateAudioOptions() {
        if (audioOptionsPopulated || audioSection == null) return;
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;

        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;

        List<EventPhaseCoverage> phaseCoverages = policyAggregate.getAudioRecordingPhaseCoverages();

        if (phaseCoverages == null || phaseCoverages.size() <= 1) {
            audioSection.setVisible(false);
            audioOptionsPopulated = true;
            return;
        }

        audioSection.setWorkingBookingProperties(workingBookingProperties);
        audioSection.populateFromPolicyAggregate(policyAggregate);
        audioSection.setVisible(true);

        audioOptionsPopulated = true;
    }

    // ========================================
    // Helper Methods
    // ========================================

    protected void bookSelectedItemsIntoWorkingBooking() {
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;

        WorkingBooking workingBooking = workingBookingProperties.getWorkingBooking();
        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;

        bookTeachingItems(workingBooking, policyAggregate);
        bookAccommodationItems(workingBooking, policyAggregate);
        bookMealsItems(workingBooking, policyAggregate);

        // Push roommate names from section to selection state before storing
        if (roommateSection != null && roommateSection.isVisible()) {
            roommateSection.pushRoommateNamesToState(form.getSelectionState());
        }
        storeRoommateInfoOnDocumentLines(workingBooking);

        // Commit child carer information to working booking
        form.getSelectionState().commitCarersInfoToBooking(workingBooking, policyAggregate.getEntityStore());

        if (mealsSection != null) {
            mealsSection.populateFromDocumentBill();
        }
    }

    /**
     * Commits only the child carer information to the working booking.
     * Called when a carer is selected, without booking other items like teachings or meals.
     */
    protected void commitChildCarerInfoToWorkingBooking() {
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;

        WorkingBooking workingBooking = workingBookingProperties.getWorkingBooking();
        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;

        form.getSelectionState().commitCarersInfoToBooking(workingBooking, policyAggregate.getEntityStore());
    }

    /**
     * Resets child carer information in both BookingSelectionState and WorkingBooking.
     * Called when a different household member is selected.
     */
    protected void resetChildCarerInfo() {
        // Reset in BookingSelectionState
        form.getSelectionState().resetChildCarerInfo();

        // Reset in WorkingBooking
        if (workingBookingProperties != null && workingBookingProperties.getWorkingBooking() != null) {
            workingBookingProperties.getWorkingBooking().setCarersInfo(null, null, null, null);
        }

        // Reset the inline child carer section UI
        DefaultChildCarerSection inlineChildCarer = memberSelectionSection != null
            ? memberSelectionSection.getChildCarerSection() : null;
        if (inlineChildCarer != null) {
            inlineChildCarer.reset();
        }

        // Reset the standalone child carer section UI (if exists)
        if (childCarerSection != null) {
            childCarerSection.reset();
        }
    }

    /**
     * Books only teaching items into the working booking.
     * Called when user selects dates.
     */
    protected void bookTeachingItemsOnly() {
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;
        WorkingBooking workingBooking = workingBookingProperties.getWorkingBooking();
        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;
        bookTeachingItems(workingBooking, policyAggregate);
    }

    /**
     * Books only accommodation items into the working booking.
     * Called when user selects accommodation.
     */
    protected void bookAccommodationItemsOnly() {
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;
        WorkingBooking workingBooking = workingBookingProperties.getWorkingBooking();
        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;
        bookAccommodationItems(workingBooking, policyAggregate);
    }

    /**
     * Books only meal items into the working booking.
     * Called when user explicitly selects meals.
     */
    protected void bookMealsItemsOnly() {
        if (workingBookingProperties == null || workingBookingProperties.getWorkingBooking() == null) return;
        WorkingBooking workingBooking = workingBookingProperties.getWorkingBooking();
        PolicyAggregate policyAggregate = workingBookingProperties.getPolicyAggregate();
        if (policyAggregate == null) return;
        bookMealsItems(workingBooking, policyAggregate);
    }

    /**
     * Auto-includes breakfast when accommodation is selected, removes it when accommodation is reset.
     * Breakfast is included with overnight accommodation but not for day visitors.
     */
    protected void autoIncludeBreakfastWithAccommodation() {
        var selectionState = form.getSelectionState();
        var selectedAccommodation = selectionState.getSelectedAccommodation();
        if (selectedAccommodation != null && !selectedAccommodation.isDayVisitor()) {
            selectionState.setWantsBreakfast(true);
        } else {
            selectionState.setWantsBreakfast(false);
        }
    }

    protected void updateStickyPriceHeader() {
        if (stickyPriceHeader == null || workingBookingProperties == null) return;
        // Price is auto-bound via totalProperty
    }

    protected void updateRoommateSectionVisibility() {
        if (roommateSection == null || accommodationSection == null) return;

        var selectedAccommodation = form.getSelectionState().getSelectedAccommodation();
        if (selectedAccommodation != null && !selectedAccommodation.isDayVisitor()) {
            // Check if room has capacity > 1
            Item item = selectedAccommodation.getItemEntity();
            if (item != null) {
                Integer capacity = item.getCapacity();
                if (capacity != null && capacity > 1) {
                    roommateSection.setRoomCapacity(capacity);
                    roommateSection.setVisible(true);
                    return;
                }
            }
        }
        roommateSection.setVisible(false);
    }

    // ========================================
    // StandardBookingFormCallbacks Implementation
    // ========================================

    @Override
    public void onAfterLogin() {
        // Reload members after login
        Person person = FXUserPerson.getUserPerson();
        if (person != null && memberSelectionSection != null) {
            AccountMemberLoader.loadMembersAsync(person, memberSelectionSection, settings.event());
        }
    }

    @Override
    public void onBeforeSummary() {
        bookSelectedItemsIntoWorkingBooking();
    }

    @Override
    public void onPrepareNewBooking() {
        // Reset form-specific state for new booking
        isBookingForChild = false;
        childAge = 0;
        childName = "";

        if (childCarerSection != null) {
            childCarerSection.reset();
            childCarerSection.setVisible(false);
        }
        if (translationSection != null) {
            translationSection.reset();
        }
        if (ordinationSection != null) {
            ordinationSection.reset();
        }
        if (assistanceSection != null) {
            assistanceSection.reset();
        }
    }

    // ========================================
    // Price Calculation Helper Methods
    // ========================================

    /**
     * Result class holding both the total price and the breakdown items.
     */
    protected static class AccommodationPriceResult {
        public final int totalPrice;
        public final List<DefaultAccommodationSelectionSection.PriceBreakdownItem> breakdown;

        public AccommodationPriceResult(int totalPrice, List<DefaultAccommodationSelectionSection.PriceBreakdownItem> breakdown) {
            this.totalPrice = totalPrice;
            this.breakdown = breakdown;
        }
    }

    /**
     * Container for boundary time information.
     */
    protected static class BoundaryTimeInfo {
        public final LocalDate date;
        public final LocalTime time;

        public BoundaryTimeInfo(LocalDate date, LocalTime time) {
            this.date = date;
            this.time = time;
        }
    }

    /**
     * Calculates accommodation price with detailed breakdown.
     */
    protected AccommodationPriceResult calculateAccommodationPriceWithBreakdown(PolicyAggregate policyAggregate, Item accommodationItem,
                                                                                 LocalDate arrivalDate, LocalDate departureDate, Integer accommodationAvailability) {
        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, AttendanceMode.IN_PERSON);

        LocalDate teachingMinDate = null;
        LocalDate teachingMaxDate = null;
        int accommodationNightsCount = 0;

        // Book teachings (inclusive period: arrivalDate to departureDate)
        Period teachingPeriod = createSimplePeriod(arrivalDate, departureDate);
        List<ScheduledItem> teachingItems = ScheduledItems.filterOverPeriod(policyAggregate.filterTeachingScheduledItems(), teachingPeriod);
        if (!teachingItems.isEmpty()) {
            tempBooking.bookScheduledItems(teachingItems, true);
            for (ScheduledItem si : teachingItems) {
                LocalDate d = si.getDate();
                if (teachingMinDate == null || d.isBefore(teachingMinDate)) teachingMinDate = d;
                if (teachingMaxDate == null || d.isAfter(teachingMaxDate)) teachingMaxDate = d;
            }
        }

        // Book accommodation (exclusive end: arrivalDate to departureDate - 1)
        List<ScheduledItem> accommodationScheduledItems = new ArrayList<>();
        if (accommodationItem != null) {
            List<ScheduledItem> itemAccoItems = policyAggregate.filterAccommodationScheduledItems().stream()
                .filter(si -> Entities.samePrimaryKey(si.getItem(), accommodationItem))
                .collect(Collectors.toList());
            Period accoPeriod = createSimplePeriod(arrivalDate, departureDate.minusDays(1));
            accommodationScheduledItems = ScheduledItems.filterOverPeriod(itemAccoItems, accoPeriod);
            if (!accommodationScheduledItems.isEmpty()) {
                tempBooking.bookScheduledItems(accommodationScheduledItems, true);
                accommodationNightsCount = accommodationScheduledItems.size();
            }
        }

        // Book meals
        bookMealsForPriceCalculation(tempBooking, policyAggregate, arrivalDate, departureDate, accommodationItem != null);

        // Build breakdown
        List<DefaultAccommodationSelectionSection.PriceBreakdownItem> breakdown = new ArrayList<>();
        PriceCalculator calc = tempBooking.getLatestBookingPriceCalculator();

        // Teaching breakdown
        List<DocumentLine> teachingLines = tempBooking.getFamilyDocumentLines(KnownItemFamily.TEACHING);
        if (!teachingLines.isEmpty()) {
            int teachingPrice = calc.calculateDocumentLinesPrice(teachingLines);
            String teachingDateRange = BookingDateFormatter.formatDateRange(teachingMinDate, teachingMaxDate);
            breakdown.add(new DefaultAccommodationSelectionSection.PriceBreakdownItem(
                "Teachings", teachingDateRange, teachingPrice));
        }

        // Accommodation breakdown
        List<DocumentLine> accoLines = tempBooking.getFamilyDocumentLines(KnownItemFamily.ACCOMMODATION);
        if (!accoLines.isEmpty()) {
            int accoPrice = calc.calculateDocumentLinesPrice(accoLines);
            String accoDateRange = accommodationNightsCount + " night" + (accommodationNightsCount != 1 ? "s" : "");
            breakdown.add(new DefaultAccommodationSelectionSection.PriceBreakdownItem(
                "Accommodation", accoDateRange, accoPrice, accommodationAvailability));
        }

        // Meals breakdown
        addMealsBreakdown(breakdown, tempBooking, policyAggregate);

        int breakdownTotal = breakdown.stream().mapToInt(DefaultAccommodationSelectionSection.PriceBreakdownItem::getPrice).sum();

        return new AccommodationPriceResult(breakdownTotal, breakdown);
    }

    /**
     * Adds the Share Accommodation option.
     */
    protected void addShareAccommodationOption(PolicyAggregate policyAggregate, LocalDate arrivalDate, LocalDate departureDate) {
        if (accommodationSection == null) return;

        ItemPolicy sharingItemPolicy = policyAggregate.getSharingAccommodationItemPolicy();
        if (sharingItemPolicy == null) return;

        Item sharingItem = sharingItemPolicy.getItem();
        if (sharingItem == null) return;

        // Calculate price with breakdown
        AccommodationPriceResult priceResult = calculateShareAccommodationPriceWithBreakdown(policyAggregate, sharingItem, arrivalDate, departureDate);

        // Store the breakdown
        accommodationSection.setBreakdownForOption(sharingItem.getPrimaryKey(), priceResult.breakdown);

        // Try to find rate for this item
        Rate itemRate = policyAggregate.filterDailyRatesStreamOfSiteAndItem(null, sharingItem)
            .findFirst()
            .orElseGet(() -> policyAggregate.getDailyRatesStream()
                .filter(r -> r.getItem() != null && Entities.samePrimaryKey(r.getItem(), sharingItem))
                .findFirst()
                .orElse(null));
        int pricePerNight = itemRate != null && itemRate.getPrice() != null ? itemRate.getPrice() : 0;

        HasAccommodationSelectionSection.ConstraintType constraintType = HasAccommodationSelectionSection.ConstraintType.NONE;
        String constraintLabel = null;
        int minNights = 0;
        if (sharingItemPolicy.getMinDay() != null && sharingItemPolicy.getMinDay() > 0) {
            constraintType = HasAccommodationSelectionSection.ConstraintType.MIN_NIGHTS;
            minNights = sharingItemPolicy.getMinDay();
            constraintLabel = I18n.getI18nText(BookingPageI18nKeys.MinNights, minNights);
        }

        boolean earlyArrivalAllowed = !Boolean.FALSE.equals(sharingItemPolicy.isEarlyAccommodationAllowed());
        boolean lateDepartureAllowed = !Boolean.FALSE.equals(sharingItemPolicy.isLateAccommodationAllowed());

        HasAccommodationSelectionSection.AccommodationOption option = new HasAccommodationSelectionSection.AccommodationOption(
            sharingItem.getPrimaryKey(),
            sharingItem,
            sharingItem.getName() != null ? sharingItem.getName() : I18n.getI18nText(BookingPageI18nKeys.ShareAccommodation),
            "",
            pricePerNight,
            HasAccommodationSelectionSection.AvailabilityStatus.AVAILABLE,
            constraintType,
            constraintLabel,
            minNights,
            false,
            null,
            true,
            priceResult.totalPrice,
            earlyArrivalAllowed,
            lateDepartureAllowed
        );

        accommodationSection.addAccommodationOption(option);
    }

    /**
     * Calculates share accommodation price with detailed breakdown.
     */
    protected AccommodationPriceResult calculateShareAccommodationPriceWithBreakdown(PolicyAggregate policyAggregate, Item sharingItem,
                                                                                      LocalDate arrivalDate, LocalDate departureDate) {
        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, AttendanceMode.IN_PERSON);

        LocalDate teachingMinDate = null;
        LocalDate teachingMaxDate = null;

        // Book teachings
        Period teachingPeriod = createSimplePeriod(arrivalDate, departureDate);
        List<ScheduledItem> teachingItems = ScheduledItems.filterOverPeriod(policyAggregate.filterTeachingScheduledItems(), teachingPeriod);
        if (!teachingItems.isEmpty()) {
            tempBooking.bookScheduledItems(teachingItems, true);
            for (ScheduledItem si : teachingItems) {
                LocalDate d = si.getDate();
                if (teachingMinDate == null || d.isBefore(teachingMinDate)) teachingMinDate = d;
                if (teachingMaxDate == null || d.isAfter(teachingMaxDate)) teachingMaxDate = d;
            }
        }

        // Calculate sharing accommodation price from rate
        int accommodationNightsCount = 0;
        LocalDate current = arrivalDate;
        while (current.isBefore(departureDate)) {
            accommodationNightsCount++;
            current = current.plusDays(1);
        }

        Rate itemRate = policyAggregate.filterDailyRatesStreamOfSiteAndItem(null, sharingItem)
            .findFirst()
            .orElseGet(() -> policyAggregate.getDailyRatesStream()
                .filter(r -> r.getItem() != null && Entities.samePrimaryKey(r.getItem(), sharingItem))
                .findFirst()
                .orElse(null));
        int pricePerNight = itemRate != null && itemRate.getPrice() != null ? itemRate.getPrice() : 0;
        int sharingAccommodationPrice = pricePerNight * accommodationNightsCount;

        // Book meals (with breakfast since sharing guests stay overnight)
        bookMealsForPriceCalculation(tempBooking, policyAggregate, arrivalDate, departureDate, true);

        // Build breakdown
        List<DefaultAccommodationSelectionSection.PriceBreakdownItem> breakdown = new ArrayList<>();
        PriceCalculator calc = tempBooking.getLatestBookingPriceCalculator();

        List<DocumentLine> teachingLines = tempBooking.getFamilyDocumentLines(KnownItemFamily.TEACHING);
        if (!teachingLines.isEmpty()) {
            int teachingPrice = calc.calculateDocumentLinesPrice(teachingLines);
            String teachingDateRange = BookingDateFormatter.formatDateRange(teachingMinDate, teachingMaxDate);
            breakdown.add(new DefaultAccommodationSelectionSection.PriceBreakdownItem(
                "Teachings", teachingDateRange, teachingPrice));
        }

        if (sharingAccommodationPrice > 0) {
            String accoDateRange = accommodationNightsCount + " night" + (accommodationNightsCount != 1 ? "s" : "");
            breakdown.add(new DefaultAccommodationSelectionSection.PriceBreakdownItem(
                "Accommodation", accoDateRange, sharingAccommodationPrice));
        }

        addMealsBreakdown(breakdown, tempBooking, policyAggregate);

        int breakdownTotal = breakdown.stream().mapToInt(DefaultAccommodationSelectionSection.PriceBreakdownItem::getPrice).sum();

        return new AccommodationPriceResult(breakdownTotal, breakdown);
    }

    /**
     * Adds the Day Visitor option.
     */
    protected void addDayVisitorOption(PolicyAggregate policyAggregate, LocalDate arrivalDate, LocalDate departureDate) {
        if (accommodationSection == null) return;

        // Calculate price with breakdown
        AccommodationPriceResult priceResult = calculateDayVisitorPriceWithBreakdown(policyAggregate, arrivalDate, departureDate);

        // Store the breakdown
        accommodationSection.setBreakdownForOption("DAY_VISITOR", priceResult.breakdown);

        HasAccommodationSelectionSection.AccommodationOption option = new HasAccommodationSelectionSection.AccommodationOption(
            "DAY_VISITOR",
            null,
            I18n.getI18nText(BookingPageI18nKeys.DayVisitor),
            "",
            0,
            HasAccommodationSelectionSection.AvailabilityStatus.AVAILABLE,
            HasAccommodationSelectionSection.ConstraintType.NONE,
            null,
            0,
            true,
            null,
            true,
            priceResult.totalPrice,
            false,  // No early arrival for day visitors
            false   // No late departure for day visitors
        );

        accommodationSection.addAccommodationOption(option);
    }

    /**
     * Calculates day visitor price with detailed breakdown.
     */
    protected AccommodationPriceResult calculateDayVisitorPriceWithBreakdown(PolicyAggregate policyAggregate, LocalDate arrivalDate, LocalDate departureDate) {
        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, AttendanceMode.IN_PERSON);

        LocalDate teachingMinDate = null;
        LocalDate teachingMaxDate = null;

        // Book teachings
        Period teachingPeriod = createSimplePeriod(arrivalDate, departureDate);
        List<ScheduledItem> teachingItems = ScheduledItems.filterOverPeriod(policyAggregate.filterTeachingScheduledItems(), teachingPeriod);
        if (!teachingItems.isEmpty()) {
            tempBooking.bookScheduledItems(teachingItems, true);
            for (ScheduledItem si : teachingItems) {
                LocalDate d = si.getDate();
                if (teachingMinDate == null || d.isBefore(teachingMinDate)) teachingMinDate = d;
                if (teachingMaxDate == null || d.isAfter(teachingMaxDate)) teachingMaxDate = d;
            }
        }

        // Book meals (no breakfast for day visitors)
        bookMealsForPriceCalculation(tempBooking, policyAggregate, arrivalDate, departureDate, false);

        // Build breakdown
        List<DefaultAccommodationSelectionSection.PriceBreakdownItem> breakdown = new ArrayList<>();
        PriceCalculator calc = tempBooking.getLatestBookingPriceCalculator();

        List<DocumentLine> teachingLines = tempBooking.getFamilyDocumentLines(KnownItemFamily.TEACHING);
        if (!teachingLines.isEmpty()) {
            int teachingPrice = calc.calculateDocumentLinesPrice(teachingLines);
            String teachingDateRange = BookingDateFormatter.formatDateRange(teachingMinDate, teachingMaxDate);
            breakdown.add(new DefaultAccommodationSelectionSection.PriceBreakdownItem(
                "Teachings", teachingDateRange, teachingPrice));
        }

        addMealsBreakdown(breakdown, tempBooking, policyAggregate);

        int breakdownTotal = breakdown.stream().mapToInt(DefaultAccommodationSelectionSection.PriceBreakdownItem::getPrice).sum();

        return new AccommodationPriceResult(breakdownTotal, breakdown);
    }

    /**
     * Books meals for price calculation.
     */
    protected void bookMealsForPriceCalculation(WorkingBooking tempBooking, PolicyAggregate policyAggregate,
                                                 LocalDate arrivalDate, LocalDate departureDate, boolean hasAccommodation) {
        Map<String, BoundaryTimeInfo> boundaryInfo = extractBoundaryTimeInfo(policyAggregate);
        BoundaryTimeInfo startBoundary = boundaryInfo.get("startBoundary");
        BoundaryTimeInfo endBoundary = boundaryInfo.get("endBoundary");

        Timeline breakfastTimeline = policyAggregate.getBreakfastTimeline();
        Timeline lunchTimeline = policyAggregate.getLunchTimeline();
        Timeline dinnerTimeline = policyAggregate.getDinnerTimeline();

        List<ScheduledItem> allMeals = policyAggregate.filterScheduledItemsOfFamily(KnownItemFamily.MEALS);

        Set<LocalDate> accommodationNights = new HashSet<>();
        if (hasAccommodation) {
            LocalDate current = arrivalDate;
            while (current.isBefore(departureDate)) {
                accommodationNights.add(current);
                current = current.plusDays(1);
            }
        }

        Set<Object> processedTimelines = new HashSet<>();

        for (ScheduledItem si : allMeals) {
            Timeline timeline = si.getTimeline();
            if (timeline == null) continue;

            Object timelinePk = Entities.getPrimaryKey(timeline);
            if (processedTimelines.contains(timelinePk)) continue;
            processedTimelines.add(timelinePk);

            boolean isBreakfast = Entities.samePrimaryKey(timeline, breakfastTimeline);
            boolean isLunch = Entities.samePrimaryKey(timeline, lunchTimeline);
            boolean isDinner = Entities.samePrimaryKey(timeline, dinnerTimeline);

            LocalTime mealTime = timeline.getStartTime();

            List<ScheduledItem> timelineScheduledItems = allMeals.stream()
                .filter(msi -> Entities.samePrimaryKey(msi.getTimeline(), timeline))
                .collect(Collectors.toList());

            if (isBreakfast && hasAccommodation) {
                timelineScheduledItems = timelineScheduledItems.stream()
                    .filter(msi -> {
                        LocalDate mealDate = msi.getDate();
                        if (mealDate == null) return false;
                        LocalDate nightBefore = mealDate.minusDays(1);
                        if (!accommodationNights.contains(nightBefore)) return false;
                        return isMealWithinBoundaries(mealTime, mealDate, arrivalDate, departureDate, startBoundary, endBoundary);
                    })
                    .collect(Collectors.toList());
            } else if (isBreakfast) {
                timelineScheduledItems = java.util.Collections.emptyList();
            } else if (isLunch || isDinner) {
                timelineScheduledItems = timelineScheduledItems.stream()
                    .filter(msi -> {
                        LocalDate mealDate = msi.getDate();
                        if (mealDate == null) return false;
                        if (mealDate.isBefore(arrivalDate) || mealDate.isAfter(departureDate)) return false;
                        return isMealWithinBoundaries(mealTime, mealDate, arrivalDate, departureDate, startBoundary, endBoundary);
                    })
                    .collect(Collectors.toList());
            }

            if (!timelineScheduledItems.isEmpty()) {
                tempBooking.bookScheduledItems(timelineScheduledItems, false);
            }
        }
    }

    /**
     * Adds meal breakdown items to the breakdown list.
     */
    protected void addMealsBreakdown(List<DefaultAccommodationSelectionSection.PriceBreakdownItem> breakdown,
                                      WorkingBooking tempBooking, PolicyAggregate policyAggregate) {
        List<DocumentLine> mealDocumentLines = tempBooking.getFamilyDocumentLines(KnownItemFamily.MEALS);

        List<DocumentLine> breakfastLines = new ArrayList<>();
        List<DocumentLine> lunchLines = new ArrayList<>();
        List<DocumentLine> dinnerLines = new ArrayList<>();

        for (DocumentLine dl : mealDocumentLines) {
            Item item = dl.getItem();
            if (item == null) continue;

            String itemName = item.getName() != null ? item.getName().toLowerCase() : "";

            if (itemName.contains("breakfast") || itemName.contains("morning")) {
                breakfastLines.add(dl);
            } else if (itemName.contains("lunch") || itemName.contains("midday")) {
                lunchLines.add(dl);
            } else if (itemName.contains("dinner") || itemName.contains("evening") || itemName.contains("supper")) {
                dinnerLines.add(dl);
            }
        }

        PriceCalculator calc = tempBooking.getLatestBookingPriceCalculator();

        addMealDocumentLineBreakdown(breakdown, calc, tempBooking, "Breakfast", breakfastLines);
        addMealDocumentLineBreakdown(breakdown, calc, tempBooking, "Lunch", lunchLines);
        addMealDocumentLineBreakdown(breakdown, calc, tempBooking, "Dinner", dinnerLines);
    }

    /**
     * Adds a single meal breakdown item.
     */
    protected void addMealDocumentLineBreakdown(List<DefaultAccommodationSelectionSection.PriceBreakdownItem> breakdown,
                                                 PriceCalculator calc, WorkingBooking tempBooking,
                                                 String mealName, List<DocumentLine> mealLines) {
        if (mealLines.isEmpty()) return;

        int mealCount = 0;
        for (DocumentLine dl : mealLines) {
            List<Attendance> lineAttendances = tempBooking.getLastestDocumentAggregate().getLineAttendances(dl);
            mealCount += lineAttendances != null ? lineAttendances.size() : 0;
        }

        if (mealCount == 0) return;

        int totalPrice = calc.calculateDocumentLinesPrice(mealLines);
        String dateRange = mealCount + " " + mealName.toLowerCase() + (mealCount != 1 ? "s" : "");

        breakdown.add(new DefaultAccommodationSelectionSection.PriceBreakdownItem(mealName, dateRange, totalPrice));
    }

    // ========================================
    // Booking Helper Methods
    // ========================================

    protected void bookTeachingItems(WorkingBooking workingBooking, PolicyAggregate policyAggregate) {
        List<ScheduledItem> allTeachingItems = policyAggregate.filterTeachingScheduledItems();

        var selectionState = form.getSelectionState();
        LocalDate arrivalDate = selectionState.getArrivalDate();
        LocalDate departureDate = selectionState.getDepartureDate();

        if (arrivalDate != null && departureDate != null) {
            Period teachingPeriod = createSimplePeriod(arrivalDate, departureDate);
            workingBooking.bookScheduledItemsOverPeriod(allTeachingItems, teachingPeriod, true);
        } else if (!allTeachingItems.isEmpty()) {
            workingBooking.bookScheduledItems(allTeachingItems, true);
        }
    }

    protected void bookAccommodationItems(WorkingBooking workingBooking, PolicyAggregate policyAggregate) {
        var selectionState = form.getSelectionState();
        HasAccommodationSelectionSection.AccommodationOption selectedOption = selectionState.getSelectedAccommodation();
        if (selectedOption == null) return;

        if (selectionState.isDayVisitor()) return;

        Item selectedItem = selectionState.getSelectedAccommodationItem();
        if (selectedItem == null) return;

        if (Boolean.TRUE.equals(selectedItem.isShare_mate())) {
            bookShareAccommodationItem(workingBooking, policyAggregate, selectedItem);
            return;
        }

        LocalDate arrivalDate = selectionState.getArrivalDate();
        LocalDate departureDate = selectionState.getDepartureDate();

        if (arrivalDate == null || departureDate == null) {
            List<ScheduledItem> teachingItems = policyAggregate.filterTeachingScheduledItems();
            List<LocalDate> teachingDatesSorted = teachingItems.stream()
                .map(ScheduledItem::getDate)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());
            if (!teachingDatesSorted.isEmpty()) {
                arrivalDate = teachingDatesSorted.get(0);
                departureDate = teachingDatesSorted.get(teachingDatesSorted.size() - 1).plusDays(1);
            }
        }

        List<ScheduledItem> itemAccoItems = policyAggregate.filterAccommodationScheduledItems().stream()
            .filter(si -> Entities.samePrimaryKey(si.getItem(), selectedItem))
            .collect(Collectors.toList());

        if (arrivalDate != null && departureDate != null) {
            Period accoPeriod = createSimplePeriod(arrivalDate, departureDate.minusDays(1));
            List<ScheduledItem> filteredItems = ScheduledItems.filterOverPeriod(itemAccoItems, accoPeriod);
            if (!filteredItems.isEmpty()) {
                workingBooking.bookScheduledItems(filteredItems, true);
            } else if (!itemAccoItems.isEmpty()) {
                workingBooking.bookScheduledItems(itemAccoItems, true);
            }
        } else if (!itemAccoItems.isEmpty()) {
            workingBooking.bookScheduledItems(itemAccoItems, true);
        }
    }

    protected void bookShareAccommodationItem(WorkingBooking workingBooking, PolicyAggregate policyAggregate, Item sharingItem) {
        var selectionState = form.getSelectionState();
        LocalDate arrivalDate = selectionState.getArrivalDate();
        LocalDate departureDate = selectionState.getDepartureDate();

        if (arrivalDate == null || departureDate == null) {
            List<ScheduledItem> teachingItems = policyAggregate.filterTeachingScheduledItems();
            List<LocalDate> teachingDatesSorted = teachingItems.stream()
                .map(ScheduledItem::getDate)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());
            if (!teachingDatesSorted.isEmpty()) {
                arrivalDate = teachingDatesSorted.get(0);
                departureDate = teachingDatesSorted.get(teachingDatesSorted.size() - 1).plusDays(1);
            }
        }

        if (arrivalDate == null || departureDate == null) return;

        List<LocalDate> accommodationDates = new ArrayList<>();
        LocalDate current = arrivalDate;
        while (current.isBefore(departureDate)) {
            accommodationDates.add(current);
            current = current.plusDays(1);
        }

        if (accommodationDates.isEmpty()) return;

        Site site = null;
        ItemPolicy itemPolicy = policyAggregate.getItemPolicy(sharingItem);
        if (itemPolicy != null && itemPolicy.getScope() != null) {
            site = itemPolicy.getScope().getSite();
        }

        workingBooking.bookTemporalButNonScheduledItem(site, sharingItem, accommodationDates, true);
    }

    protected void bookMealsItems(WorkingBooking workingBooking, PolicyAggregate policyAggregate) {
        var selectionState = form.getSelectionState();

        List<ScheduledItem> mealsItems = policyAggregate.filterScheduledItemsOfFamily(KnownItemFamily.MEALS);
        if (mealsItems.isEmpty()) return;

        LocalDate arrivalDate = selectionState.getArrivalDate();
        LocalDate departureDate = selectionState.getDepartureDate();

        if (arrivalDate == null || departureDate == null) {
            List<ScheduledItem> teachingItems = policyAggregate.filterTeachingScheduledItems();
            List<LocalDate> teachingDatesSorted = teachingItems.stream()
                .map(ScheduledItem::getDate)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());
            if (!teachingDatesSorted.isEmpty()) {
                arrivalDate = teachingDatesSorted.get(0);
                departureDate = teachingDatesSorted.get(teachingDatesSorted.size() - 1);
            }
        }

        if (arrivalDate == null || departureDate == null) return;

        boolean hasAccommodation = !selectionState.isDayVisitor() && selectionState.getSelectedAccommodationItem() != null;

        Period mealsPeriod = createSimplePeriod(arrivalDate, departureDate);
        List<ScheduledItem> filteredMeals = ScheduledItems.filterOverPeriod(mealsItems, mealsPeriod);

        if (!filteredMeals.isEmpty()) {
            workingBooking.bookScheduledItems(filteredMeals, false);
        }
    }

    protected void storeRoommateInfoOnDocumentLines(WorkingBooking workingBooking) {
        var selectionState = form.getSelectionState();
        List<String> roommateNamesList = selectionState.getRoommateNames();
        if (roommateNamesList == null || roommateNamesList.isEmpty()) return;

        String roommateNames = String.join(", ", roommateNamesList);
        if (roommateNames.isEmpty()) return;

        List<DocumentLine> accoLines = workingBooking.getFamilyDocumentLines(KnownItemFamily.ACCOMMODATION);
        for (DocumentLine dl : accoLines) {
            dl.setFieldValue("comment", roommateNames);
        }
    }

    // ========================================
    // Utility Helper Methods
    // ========================================

    protected EventPart findMainEventPart(PolicyAggregate policyAggregate) {
        if (policyAggregate.getEventParts() == null) return null;

        for (EventPart part : policyAggregate.getEventParts()) {
            if (policyAggregate.getEarlyArrivalPart() != null &&
                Entities.samePrimaryKey(part, policyAggregate.getEarlyArrivalPart())) continue;
            if (policyAggregate.getLateDeparturePart() != null &&
                Entities.samePrimaryKey(part, policyAggregate.getLateDeparturePart())) continue;
            return part;
        }

        return null;
    }

    protected void syncFestivalDaySelectionToState() {
        if (festivalDaySection == null) return;

        LocalDate currentArrival = festivalDaySection.arrivalDateProperty().get();
        LocalDate currentDeparture = festivalDaySection.departureDateProperty().get();
        HasFestivalDaySelectionSection.ArrivalDepartureTime currentArrivalTime = festivalDaySection.arrivalTimeProperty().get();
        HasFestivalDaySelectionSection.ArrivalDepartureTime currentDepartureTime = festivalDaySection.departureTimeProperty().get();

        BookingSelectionState selectionState = form.getSelectionState();
        if (currentArrival != null) {
            selectionState.setArrivalDate(currentArrival);
        }
        if (currentDeparture != null) {
            selectionState.setDepartureDate(currentDeparture);
        }
        if (currentArrivalTime != null) {
            selectionState.setArrivalTime(currentArrivalTime);
        }
        if (currentDepartureTime != null) {
            selectionState.setDepartureTime(currentDepartureTime);
        }
    }

    protected LocalTime getBoundaryTime(ScheduledBoundary boundary, boolean isEnd) {
        return ScheduledBoundaries.getTime(boundary, isEnd);
    }

    protected DefaultMealsSelectionSection.MealBoundary getMealBoundaryFromTime(LocalTime time) {
        if (time == null) return DefaultMealsSelectionSection.MealBoundary.LUNCH;
        int hour = time.getHour();
        if (hour < 10) return DefaultMealsSelectionSection.MealBoundary.BREAKFAST;
        if (hour < 14) return DefaultMealsSelectionSection.MealBoundary.LUNCH;
        return DefaultMealsSelectionSection.MealBoundary.DINNER;
    }

    protected Map<String, BoundaryTimeInfo> extractBoundaryTimeInfo(PolicyAggregate policyAggregate) {
        Map<String, BoundaryTimeInfo> result = new HashMap<>();

        EventPart mainEventPart = findMainEventPart(policyAggregate);

        ScheduledBoundary effectiveStartBoundary = null;
        if (mainEventPart != null) {
            effectiveStartBoundary = mainEventPart.getStartBoundary();
        }

        ScheduledBoundary effectiveEndBoundary = null;
        if (mainEventPart != null) {
            effectiveEndBoundary = mainEventPart.getEndBoundary();
        }

        if (effectiveStartBoundary != null) {
            LocalDate date = ScheduledBoundaries.getDate(effectiveStartBoundary, false);
            LocalTime time = ScheduledBoundaries.getTime(effectiveStartBoundary, false);
            if (date != null && time != null) {
                result.put("startBoundary", new BoundaryTimeInfo(date, time));
            }
        }

        if (effectiveEndBoundary != null) {
            LocalDate date = ScheduledBoundaries.getDate(effectiveEndBoundary, true);
            LocalTime time = ScheduledBoundaries.getTime(effectiveEndBoundary, true);
            if (date != null && time != null) {
                result.put("endBoundary", new BoundaryTimeInfo(date, time));
            }
        }

        return result;
    }

    protected boolean isMealWithinBoundaries(LocalTime mealTime, LocalDate mealDate,
                                              LocalDate arrivalDate, LocalDate departureDate,
                                              BoundaryTimeInfo startBoundary, BoundaryTimeInfo endBoundary) {
        if (mealTime == null) {
            return true;
        }

        if (mealDate.equals(arrivalDate) && startBoundary != null && mealDate.equals(startBoundary.date)) {
            if (mealTime.isBefore(startBoundary.time)) {
                return false;
            }
        }

        if (mealDate.equals(departureDate) && endBoundary != null && mealDate.equals(endBoundary.date)) {
            if (mealTime.isAfter(endBoundary.time)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Creates a simple Period from start and end dates.
     */
    protected Period createSimplePeriod(LocalDate startDate, LocalDate endDate) {
        return new Period() {
            @Override public LocalDate getStartDate() { return startDate; }
            @Override public LocalTime getStartTime() { return LocalTime.MIN; }
            @Override public LocalDate getEndDate() { return endDate; }
            @Override public LocalTime getEndTime() { return LocalTime.MAX; }
        };
    }
}

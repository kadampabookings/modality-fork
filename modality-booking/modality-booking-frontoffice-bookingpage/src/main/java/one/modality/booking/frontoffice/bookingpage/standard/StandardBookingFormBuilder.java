package one.modality.booking.frontoffice.bookingpage.standard;

import javafx.scene.Node;
import one.modality.base.shared.entities.AttendanceMode;
import one.modality.base.shared.entities.Event;
import one.modality.booking.client.workingbooking.HasWorkingBookingProperties;
import one.modality.booking.frontoffice.bookingform.BookingFormEntryPoint;
import one.modality.booking.frontoffice.bookingpage.BookingFormPage;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;
import one.modality.event.frontoffice.activities.book.event.EventBookingFormSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builder for creating standard booking forms with a flexible number of custom steps
 * followed by common checkout steps (Your Information → Confirmation).
 *
 * <p>Usage pattern:</p>
 * <pre>
 * // Simple form with 1 custom step
 * StandardBookingFormBuilder builder = new StandardBookingFormBuilder(activity, settings)
 *     .withColorScheme(BookingFormColorScheme.WISDOM_BLUE)
 *     .addCustomStep(optionsPage)
 *     .build();
 *
 * // Complex form with 5 custom steps
 * StandardBookingFormBuilder builder = new StandardBookingFormBuilder(activity, settings)
 *     .withColorScheme(BookingFormColorScheme.JOY_AMBER)
 *     .addCustomStep(dateSelectionPage)
 *     .addCustomStep(packageSelectionPage)
 *     .addCustomStep(roomSelectionPage)
 *     .addCustomStep(mealsPage)
 *     .addCustomStep(extrasPage)
 *     .build();
 * </pre>
 *
 * <p>The resulting form will have:</p>
 * <ul>
 *   <li>Steps 1-N: Custom steps (provided via addCustomStep)</li>
 *   <li>Step N+1: Your Information (login/registration)</li>
 *   <li>Step N+2: Member Selection (who is booking for)</li>
 *   <li>Step N+3: Summary (review booking)</li>
 *   <li>Step N+4: Pending Bookings (basket)</li>
 *   <li>Step N+5: Payment</li>
 *   <li>Step N+6: Confirmation</li>
 * </ul>
 *
 * @author Bruno Salmon
 */
public class StandardBookingFormBuilder {

    // All fields are package-private so StandardBookingForm can read them directly
    // from the builder (config object pattern - eliminates 19-parameter constructor)

    final HasWorkingBookingProperties activity;
    final EventBookingFormSettings settings;
    final List<BookingFormPage> customSteps = new ArrayList<>();

    Supplier<BookingFormPage> yourInformationPageSupplier;
    Supplier<BookingFormPage> memberSelectionPageSupplier;
    Supplier<BookingFormPage> summaryPageSupplier;
    Supplier<BookingFormPage> pendingBookingsPageSupplier;
    Supplier<BookingFormPage> paymentPageSupplier;
    Supplier<BookingFormPage> confirmationPageSupplier;

    BookingFormColorScheme colorScheme = BookingFormColorScheme.DEFAULT;
    boolean showUserBadge = false;
    StandardBookingFormCallbacks callbacks;
    boolean cardPaymentOnly = false;
    boolean fullPaymentOnly = false;
    BookingFormEntryPoint entryPoint = BookingFormEntryPoint.NEW_BOOKING;
    boolean skipMemberSelection = false;
    boolean navigationClickable = true;
    Node stickyHeader;
    boolean showCommentsSection = false;
    AttendanceMode attendanceMode;

    // Resolved values (computed during build())
    BookingFormColorScheme resolvedColorScheme;
    boolean resolvedSkipPendingBookings;
    boolean resolvedSkipSummary;

    /**
     * Creates a new builder for a standard booking form.
     *
     * @param activity The activity providing WorkingBookingProperties
     * @param settings The event booking form settings
     */
    public StandardBookingFormBuilder(HasWorkingBookingProperties activity, EventBookingFormSettings settings) {
        this.activity = activity;
        this.settings = settings;
    }

    // === Custom Steps ===

    /**
     * Adds a custom step to the form. Custom steps appear before the common checkout steps.
     * Steps are displayed in the order they are added.
     *
     * @param page The custom step page
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder addCustomStep(BookingFormPage page) {
        customSteps.add(page);
        return this;
    }

    // === Theme ===

    /**
     * Sets the color scheme for the form.
     *
     * @param colorScheme The color scheme to apply
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withColorScheme(BookingFormColorScheme colorScheme) {
        this.colorScheme = colorScheme;
        return this;
    }

    /**
     * Sets whether to show the user badge in the step progress header.
     * When true, shows a badge with the logged-in user's initials and name.
     * Default is false (badge hidden).
     *
     * @param show true to show the badge, false to hide it
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withShowUserBadge(boolean show) {
        this.showUserBadge = show;
        return this;
    }

    // === Common Step Overrides ===

    /**
     * Sets a custom summary page supplier to use instead of the default summary page.
     * This allows forms to provide a completely custom summary page with their own sections.
     *
     * @param supplier The supplier that creates the custom summary page
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withSummaryPageSupplier(Supplier<BookingFormPage> supplier) {
        this.summaryPageSupplier = supplier;
        return this;
    }

    /**
     * Sets a custom Your Information page supplier to use instead of the default.
     * This allows forms to provide a custom login/registration page or skip it entirely.
     *
     * @param supplier The supplier that creates the custom Your Information page
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withYourInformationPageSupplier(Supplier<BookingFormPage> supplier) {
        this.yourInformationPageSupplier = supplier;
        return this;
    }

    /**
     * Sets a custom Member Selection page supplier to use instead of the default.
     * This allows forms to provide a custom member selection page or skip it entirely.
     *
     * @param supplier The supplier that creates the custom Member Selection page
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withMemberSelectionPageSupplier(Supplier<BookingFormPage> supplier) {
        this.memberSelectionPageSupplier = supplier;
        return this;
    }

    // === Callbacks ===

    /**
     * Sets the callbacks for inter-step communication.
     *
     * @param callbacks The callbacks implementation
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withCallbacks(StandardBookingFormCallbacks callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    // === Payment Configuration ===

    /**
     * Sets whether only card payment should be available (no bank transfer option).
     * Default is false (both card and bank transfer available).
     *
     * @param cardOnly true to only allow card payment, false to allow all methods
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withCardPaymentOnly(boolean cardOnly) {
        this.cardPaymentOnly = cardOnly;
        return this;
    }

    /**
     * Sets whether only full payment should be available (no deposit or custom amount).
     * Default is false (deposit, custom, and full payment available).
     *
     * @param fullOnly true to only allow full payment, false to allow all options
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withFullPaymentOnly(boolean fullOnly) {
        this.fullPaymentOnly = fullOnly;
        return this;
    }

    // === Entry Point ===

    /**
     * Sets the entry point for the booking form.
     * This determines the initial state and behavior of the form:
     * - NEW_BOOKING: Standard new booking flow (default)
     * - MODIFY_BOOKING: Modifying an existing booking
     * - RESUME_PAYMENT: Returning from payment gateway to show payment result
     *
     * @param entryPoint The entry point for this booking form
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withEntryPoint(BookingFormEntryPoint entryPoint) {
        this.entryPoint = entryPoint;
        return this;
    }

    /**
     * Sets whether to skip the member selection step.
     * Useful when the form handles member selection in a custom step.
     * Default is false (member selection step is shown).
     *
     * @param skip true to skip member selection, false to show it
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withSkipMemberSelection(boolean skip) {
        this.skipMemberSelection = skip;
        return this;
    }

    /**
     * Sets whether the step progress header circles are clickable for navigation.
     * When false, users can only navigate using the Next/Back buttons.
     * Default is true (circles are clickable).
     *
     * @param clickable true to allow clicking on step circles, false to disable
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withNavigationClickable(boolean clickable) {
        this.navigationClickable = clickable;
        return this;
    }

    /**
     * Sets a sticky header component that appears fixed at the top of the form.
     * Useful for displaying a running total, selected items, or other summary info
     * that should remain visible as the user scrolls.
     *
     * @param stickyHeader The header node to display at the top
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withStickyHeader(Node stickyHeader) {
        this.stickyHeader = stickyHeader;
        return this;
    }

    /**
     * Sets whether to show a comments/special requests section on the Summary page.
     * When enabled, users can enter free-text comments that are stored in the
     * Document's request field.
     * Default is false (comments section is hidden).
     *
     * @param show true to show the comments section, false to hide it
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withShowCommentsSection(boolean show) {
        this.showCommentsSection = show;
        return this;
    }

    /**
     * Sets the attendance mode for the booking.
     * When set, this overrides the default attendance mode derived from the event settings.
     * Use this when the form type determines the attendance mode (e.g., online-only forms).
     *
     * @param attendanceMode The attendance mode (ONLINE or IN_PERSON)
     * @return This builder for chaining
     */
    public StandardBookingFormBuilder withAttendanceMode(AttendanceMode attendanceMode) {
        this.attendanceMode = attendanceMode;
        return this;
    }

    /**
     * Returns the configured attendance mode, or null if not explicitly set.
     * Used by BookEventActivity to determine how to create the WorkingBooking.
     *
     * @return the attendance mode, or null
     */
    public AttendanceMode getAttendanceMode() {
        return attendanceMode;
    }

    // === Build ===

    /**
     * Builds the booking form with all configured steps.
     *
     * @return A new StandardBookingForm instance
     */
    public StandardBookingForm build() {
        // Resolve color scheme from Event if using DEFAULT
        resolvedColorScheme = colorScheme;
        if (resolvedColorScheme == BookingFormColorScheme.DEFAULT) {
            Event event = activity.getWorkingBookingProperties().getWorkingBooking().getEvent();
            resolvedColorScheme = BookingFormColorScheme.resolveFromEvent(event);
        }

        // Compute skip flags based on entry point
        boolean isPayBookingEntryPoint = entryPoint == BookingFormEntryPoint.PAY_BOOKING;
        resolvedSkipPendingBookings = isPayBookingEntryPoint && !StandardBookingForm.PAY_BOOKING_CAN_BE_MULTIPLE;
        resolvedSkipSummary = isPayBookingEntryPoint;

        return new StandardBookingForm(this);
    }
}

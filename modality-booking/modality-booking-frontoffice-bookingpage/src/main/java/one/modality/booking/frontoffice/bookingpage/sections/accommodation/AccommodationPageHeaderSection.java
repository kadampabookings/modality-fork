package one.modality.booking.frontoffice.bookingpage.sections.accommodation;

import dev.webfx.extras.i18n.controls.I18nControls;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import one.modality.base.client.i18n.I18nEntities;
import one.modality.base.shared.entities.Event;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;
import one.modality.booking.frontoffice.bookingpage.BookingPageI18nKeys;

import static one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Centered header for the Accommodation page (Step 3) matching JSX design:
 * <ul>
 *   <li>"How would you like to attend?" title (24px, bold, centered)</li>
 *   <li>"Booking for: [person name]" + optional "Ordained" badge</li>
 *   <li>Event name + date range (13px, lighter gray)</li>
 * </ul>
 *
 * <p>Person name and ordained status must be set externally via setPersonName() and setOrdained()
 * as they are not available in WorkingBookingProperties.</p>
 *
 * @author Claude
 * @see BookingFormSection
 */
public class AccommodationPageHeaderSection implements BookingFormSection {

    // === Date Formatters ===
    private static final DateTimeFormatter DATE_FORMAT_WITH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy");

    // === Validity ===
    private final SimpleBooleanProperty validProperty = new SimpleBooleanProperty(true);

    // === UI Components ===
    private final VBox container = new VBox(0);
    private final Label titleLabel;
    private final Label bookingForLabel;
    private final Label personNameLabel;
    private final Label ordainedBadge;
    private final Label eventInfoLabel;

    // === Data ===
    private WorkingBookingProperties workingBookingProperties;

    public AccommodationPageHeaderSection() {
        container.setAlignment(Pos.CENTER);

        // Title: "How would you like to attend?"
        titleLabel = I18nControls.newLabel(BookingPageI18nKeys.HowWouldYouLikeToAttend);
        titleLabel.getStyleClass().addAll(bookingpage_text_2xl, bookingpage_font_bold);
        VBox.setMargin(titleLabel, new Insets(0, 0, 10, 0));

        // Subtitle row: "Booking for: [person name]" + optional badge
        HBox subtitleRow = new HBox(8);
        subtitleRow.setAlignment(Pos.CENTER);

        bookingForLabel = I18nControls.newLabel(BookingPageI18nKeys.BookingFor);
        bookingForLabel.getStyleClass().addAll(bookingpage_text_md, bookingpage_text_muted);

        personNameLabel = new Label();
        personNameLabel.getStyleClass().addAll(bookingpage_text_md, bookingpage_font_semibold);

        ordainedBadge = I18nControls.newLabel(BookingPageI18nKeys.Ordained);
        ordainedBadge.getStyleClass().add(bookingpage_ordained_badge);
        ordainedBadge.setPadding(new Insets(2, 8, 2, 8));
        ordainedBadge.setVisible(false);
        ordainedBadge.setManaged(false);

        subtitleRow.getChildren().addAll(bookingForLabel, personNameLabel, ordainedBadge);
        VBox.setMargin(subtitleRow, new Insets(0, 0, 16, 0));

        // Event info line
        eventInfoLabel = new Label();
        eventInfoLabel.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_muted_light);
        VBox.setMargin(eventInfoLabel, new Insets(0, 0, 40, 0));

        container.getChildren().addAll(titleLabel, subtitleRow, eventInfoLabel);
    }

    /**
     * Sets the person name displayed in the header.
     * Must be called externally as person info is not in WorkingBookingProperties.
     */
    public void setPersonName(String name) {
        personNameLabel.setText(name);
    }

    /**
     * Sets whether to show the "Ordained" badge.
     * Must be called externally as person info is not in WorkingBookingProperties.
     */
    public void setOrdained(boolean ordained) {
        ordainedBadge.setVisible(ordained);
        ordainedBadge.setManaged(ordained);
    }

    /**
     * Sets the event info line (event name + date range).
     */
    public void setEventInfo(String eventName, String dateRange) {
        eventInfoLabel.setText(eventName + " • " + dateRange);
    }

    /**
     * Loads data from the working booking properties.
     * Only loads event info as person info is not available in WorkingBookingProperties.
     */
    protected void loadData() {
        if (workingBookingProperties == null) {
            return;
        }

        // Get event info
        Event event = workingBookingProperties.getEvent();
        if (event != null) {
            LocalDate startDate = event.getStartDate();
            LocalDate endDate = event.getEndDate();

            String dateRange = "";
            if (startDate != null && endDate != null) {
                dateRange = startDate.format(DATE_FORMAT_WITH_YEAR) + " – " + endDate.format(DATE_FORMAT_WITH_YEAR);
            } else if (startDate != null) {
                dateRange = startDate.format(DATE_FORMAT_WITH_YEAR);
            }

            // Bind event name with i18n (use + operator for concatenation, not concat())
            I18nEntities.bindExpressionToTextProperty(eventInfoLabel.textProperty(), event,
                "coalesce(i18n(label),name) + ' • " + dateRange + "'");
        }
    }

    // === BookingFormSection interface ===

    @Override
    public Object getTitleI18nKey() {
        return null; // No title needed for this header section
    }

    @Override
    public Node getView() {
        return container;
    }

    @Override
    public void setWorkingBookingProperties(WorkingBookingProperties workingBookingProperties) {
        this.workingBookingProperties = workingBookingProperties;
        loadData();
    }

    @Override
    public ObservableBooleanValue validProperty() {
        return validProperty;
    }
}

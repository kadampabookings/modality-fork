package one.modality.booking.frontoffice.bookingpage.sections.booking;

import dev.webfx.extras.i18n.controls.I18nControls;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;
import one.modality.booking.frontoffice.bookingpage.BookingPageI18nKeys;

import static one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors.*;

/**
 * Centered header for the Booking Details page (Step 4) matching JSX mockup design:
 * <ul>
 *   <li>"Booking Details" title (24px, bold, centered)</li>
 *   <li>"Customize your festival experience" subtitle (14px, centered, gray)</li>
 * </ul>
 *
 * @author Claude
 * @see BookingFormSection
 */
public class BookingDetailsPageHeaderSection implements BookingFormSection {

    // === Validity ===
    private final SimpleBooleanProperty validProperty = new SimpleBooleanProperty(true);

    // === UI Components ===
    private final VBox container = new VBox(8);

    public BookingDetailsPageHeaderSection() {
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(0, 0, 20, 0));

        // Title: "Booking Details"
        Label titleLabel = I18nControls.newLabel(BookingPageI18nKeys.BookingDetails);
        titleLabel.getStyleClass().addAll(bookingpage_text_2xl, bookingpage_font_bold);

        // Subtitle: "Customize your festival experience"
        Label subtitleLabel = I18nControls.newLabel(BookingPageI18nKeys.CustomizeYourFestivalExperience);
        subtitleLabel.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_muted);

        container.getChildren().addAll(titleLabel, subtitleLabel);
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
        // No data needed from WorkingBookingProperties for this simple header
    }

    @Override
    public ObservableBooleanValue validProperty() {
        return validProperty;
    }
}

package one.modality.booking.frontoffice.bookingpage.sections.confirmation;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;

/**
 * Interface for the "Terms and Conditions" section of a booking form.
 * This section displays a checkbox for accepting terms and conditions.
 *
 * <p>The section is valid only when the terms checkbox is checked.</p>
 *
 * @author Bruno Salmon
 * @see BookingFormSection
 */
public interface HasTermsSection extends BookingFormSection {

    /**
     * Returns whether the terms have been accepted.
     */
    boolean isTermsAccepted();

    /**
     * Returns the terms accepted property for binding.
     */
    BooleanProperty termsAcceptedProperty();

    /**
     * Sets the URL for the terms and conditions page.
     */
    void setTermsUrl(String url);

    /**
     * Returns the terms URL property for binding.
     */
    StringProperty termsUrlProperty();

    /**
     * Sets custom text for the terms checkbox (optional).
     * If not set, uses the default i18n text.
     */
    void setTermsText(String text);

    /**
     * Resets the section (unchecks the terms checkbox).
     */
    void reset();
}

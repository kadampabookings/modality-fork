package one.modality.booking.frontoffice.bookingpage;

import javafx.scene.Node;
import javafx.scene.control.ToggleButton;

/**
 * @author Bruno Salmon
 */
public interface BookingFormNavigation {

    void setBookingForm(MultiPageBookingForm bookingForm);

    Node getView();

    void updateState();

    default void setButtons(BookingFormButton... buttons) {
    }

    /** @deprecated Use {@link #setButtons(BookingFormButton...)} instead */
    @Deprecated
    default javafx.scene.control.ToggleButton getBackButton() {
        return null;
    }

    /** @deprecated Use {@link #setButtons(BookingFormButton...)} instead */
    @Deprecated
    default javafx.scene.control.ToggleButton getNextButton() {
        return null;
    }
}

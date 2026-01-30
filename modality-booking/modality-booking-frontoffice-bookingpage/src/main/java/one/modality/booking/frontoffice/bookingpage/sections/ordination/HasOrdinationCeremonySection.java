package one.modality.booking.frontoffice.bookingpage.sections.ordination;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;
import one.modality.booking.frontoffice.bookingpage.ResettableSection;
import one.modality.booking.frontoffice.bookingpage.standard.BookingSelectionState;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;

import java.time.LocalDateTime;

/**
 * Interface for the "Ordination Ceremony" section of a booking form.
 * This section allows ordained people to register for the ordination ceremony
 * during international festivals.
 *
 * <p>This section should only be visible when:</p>
 * <ul>
 *   <li>The event includes an ordination ceremony</li>
 *   <li>The person being booked is ordained (monk/nun)</li>
 * </ul>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Toggle card to attend ordination ceremony</li>
 *   <li>Display of ceremony date and time</li>
 *   <li>Conditional visibility based on ordination status</li>
 * </ul>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-ordination-section} - section container</li>
 *   <li>{@code .bookingpage-ordination-card} - toggle card</li>
 *   <li>{@code .bookingpage-ordination-datetime} - ceremony datetime display</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see BookingFormSection
 */
public interface HasOrdinationCeremonySection extends BookingFormSection, ResettableSection {

    // === Configuration ===

    /**
     * Returns the color scheme property for theming.
     */
    ObjectProperty<BookingFormColorScheme> colorSchemeProperty();

    /**
     * Sets the color scheme for this section.
     */
    void setColorScheme(BookingFormColorScheme scheme);

    // === Visibility ===

    /**
     * Property controlling whether this section is visible.
     * Should be hidden if the person is not ordained or event has no ceremony.
     */
    BooleanProperty visibleProperty();

    /**
     * Sets whether this section is visible.
     */
    void setVisible(boolean visible);

    /**
     * Returns whether this section is currently visible.
     */
    boolean isVisible();

    // === Ordination Status ===

    /**
     * Property indicating whether the person being booked is ordained.
     * The section should only be visible when this is true.
     */
    BooleanProperty isOrdainedProperty();

    /**
     * Returns true if the person is ordained.
     */
    default boolean isOrdained() {
        return isOrdainedProperty().get();
    }

    /**
     * Sets whether the person is ordained.
     */
    default void setIsOrdained(boolean ordained) {
        isOrdainedProperty().set(ordained);
    }

    // === Ceremony Selection ===

    /**
     * Property indicating whether the person wants to attend the ordination ceremony.
     */
    BooleanProperty wantsOrdinationCeremonyProperty();

    /**
     * Returns true if the person wants to attend the ceremony.
     */
    default boolean wantsOrdinationCeremony() {
        return wantsOrdinationCeremonyProperty().get();
    }

    /**
     * Sets whether the person wants to attend the ceremony.
     */
    default void setWantsOrdinationCeremony(boolean wants) {
        wantsOrdinationCeremonyProperty().set(wants);
    }

    // === Ceremony Information ===

    /**
     * Property for the ceremony date/time.
     */
    ObjectProperty<LocalDateTime> ceremonyDateTimeProperty();

    /**
     * Returns the ceremony date/time.
     */
    default LocalDateTime getCeremonyDateTime() {
        return ceremonyDateTimeProperty().get();
    }

    /**
     * Sets the ceremony date/time.
     */
    default void setCeremonyDateTime(LocalDateTime dateTime) {
        ceremonyDateTimeProperty().set(dateTime);
    }

    /**
     * Property for the ceremony location/venue description.
     */
    StringProperty ceremonyLocationProperty();

    /**
     * Returns the ceremony location.
     */
    default String getCeremonyLocation() {
        return ceremonyLocationProperty().get();
    }

    /**
     * Sets the ceremony location.
     */
    default void setCeremonyLocation(String location) {
        ceremonyLocationProperty().set(location);
    }

    // === Reset ===

    /**
     * Resets the section to its initial state.
     */
    @Override
    void reset();

    // === Callbacks ===

    /**
     * Sets a callback to be invoked when the selection changes.
     * @param callback the callback to invoke on selection change
     */
    void setOnSelectionChanged(Runnable callback);

    // === Selection State Binding ===

    /**
     * Binds this section to the centralized BookingSelectionState.
     * Changes in the section will be pushed to the state.
     *
     * @param selectionState the centralized state to bind to
     */
    default void bindToSelectionState(BookingSelectionState selectionState) {
        if (selectionState == null) return;

        // Push wantsOrdinationCeremony changes to state
        wantsOrdinationCeremonyProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setWantsOrdinationCeremony(newVal);
        });

        // Initialize state from current value
        selectionState.setWantsOrdinationCeremony(wantsOrdinationCeremony());
    }
}

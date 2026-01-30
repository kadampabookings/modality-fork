package one.modality.booking.frontoffice.bookingpage.sections.assistance;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;
import one.modality.booking.frontoffice.bookingpage.ResettableSection;
import one.modality.booking.frontoffice.bookingpage.standard.BookingSelectionState;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;

/**
 * Interface for the "Assistance Needs" section of a booking form.
 * This section allows users to indicate any accessibility or assistance
 * requirements they may have for the event.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Mobility assistance checkbox</li>
 *   <li>Hearing assistance checkbox</li>
 *   <li>Visual assistance checkbox</li>
 *   <li>Dietary requirements text field</li>
 *   <li>Info box explaining available accommodations</li>
 * </ul>
 *
 * <p>Note: No "Other" free-text field is provided. Only the specific
 * checkboxes and dietary field are available.</p>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-assistance-section} - section container</li>
 *   <li>{@code .bookingpage-assistance-checkbox} - checkbox item</li>
 *   <li>{@code .bookingpage-assistance-dietary} - dietary text field</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see BookingFormSection
 */
public interface HasAssistanceNeedsSection extends BookingFormSection, ResettableSection {

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

    // === Mobility Assistance ===

    /**
     * Property indicating whether the user needs mobility assistance.
     */
    BooleanProperty mobilityAssistanceProperty();

    /**
     * Returns true if mobility assistance is needed.
     */
    default boolean needsMobilityAssistance() {
        return mobilityAssistanceProperty().get();
    }

    /**
     * Sets whether mobility assistance is needed.
     */
    default void setMobilityAssistance(boolean needs) {
        mobilityAssistanceProperty().set(needs);
    }

    // === Hearing Assistance ===

    /**
     * Property indicating whether the user needs hearing assistance.
     */
    BooleanProperty hearingAssistanceProperty();

    /**
     * Returns true if hearing assistance is needed.
     */
    default boolean needsHearingAssistance() {
        return hearingAssistanceProperty().get();
    }

    /**
     * Sets whether hearing assistance is needed.
     */
    default void setHearingAssistance(boolean needs) {
        hearingAssistanceProperty().set(needs);
    }

    // === Visual Assistance ===

    /**
     * Property indicating whether the user needs visual assistance.
     */
    BooleanProperty visualAssistanceProperty();

    /**
     * Returns true if visual assistance is needed.
     */
    default boolean needsVisualAssistance() {
        return visualAssistanceProperty().get();
    }

    /**
     * Sets whether visual assistance is needed.
     */
    default void setVisualAssistance(boolean needs) {
        visualAssistanceProperty().set(needs);
    }

    // === Dietary Requirements ===

    /**
     * Property for dietary requirements text.
     */
    StringProperty dietaryRequirementsProperty();

    /**
     * Returns the dietary requirements text.
     */
    default String getDietaryRequirements() {
        return dietaryRequirementsProperty().get();
    }

    /**
     * Sets the dietary requirements text.
     */
    default void setDietaryRequirements(String requirements) {
        dietaryRequirementsProperty().set(requirements);
    }

    // === Convenience Methods ===

    /**
     * Returns true if any assistance is needed.
     */
    default boolean hasAnyAssistanceNeeds() {
        return needsMobilityAssistance() || needsHearingAssistance() || needsVisualAssistance()
            || (getDietaryRequirements() != null && !getDietaryRequirements().isEmpty());
    }

    // === Reset ===

    /**
     * Resets the section to its initial state (all options unchecked, dietary cleared).
     */
    @Override
    void reset();

    // === Callbacks ===

    /**
     * Sets a callback to be invoked when any selection changes.
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

        // Push mobility assistance changes to state
        mobilityAssistanceProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setAssistanceMobility(newVal);
        });

        // Push hearing assistance changes to state
        hearingAssistanceProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setAssistanceHearing(newVal);
        });

        // Push visual assistance changes to state
        visualAssistanceProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setAssistanceVisual(newVal);
        });

        // Push dietary requirements changes to state
        dietaryRequirementsProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setAssistanceDietary(newVal);
        });

        // Initialize state from current values
        selectionState.setAssistanceMobility(needsMobilityAssistance());
        selectionState.setAssistanceHearing(needsHearingAssistance());
        selectionState.setAssistanceVisual(needsVisualAssistance());
        if (getDietaryRequirements() != null) {
            selectionState.setAssistanceDietary(getDietaryRequirements());
        }
    }
}

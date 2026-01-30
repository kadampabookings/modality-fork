package one.modality.booking.frontoffice.bookingpage.sections.translation;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;
import one.modality.booking.frontoffice.bookingpage.ResettableSection;
import one.modality.booking.frontoffice.bookingpage.standard.BookingSelectionState;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;

import java.util.List;

/**
 * Interface for the "Translation or Hard of Hearing" section of a booking form.
 * This section allows users to request translation services or hearing assistance
 * headsets for the event.
 *
 * <p>Both translation and hard of hearing options use the same headphone system
 * and share the same API data source for available languages/options.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Toggle to enable translation/hearing assistance</li>
 *   <li>Language/option selection when enabled</li>
 *   <li>Available languages populated from event configuration</li>
 *   <li>Visibility control based on event having translation options</li>
 * </ul>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-translation-section} - section container</li>
 *   <li>{@code .bookingpage-translation-toggle} - toggle control</li>
 *   <li>{@code .bookingpage-translation-option} - language/option item</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see BookingFormSection
 */
public interface HasTranslationSection extends BookingFormSection, ResettableSection {

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
     * Should be hidden if no translation options are available for the event.
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

    // === Translation Toggle ===

    /**
     * Property indicating whether the user needs translation or hard of hearing assistance.
     */
    BooleanProperty needsTranslationProperty();

    /**
     * Returns true if the user has indicated they need translation/hearing assistance.
     */
    default boolean needsTranslation() {
        return needsTranslationProperty().get();
    }

    /**
     * Sets whether the user needs translation/hearing assistance.
     */
    default void setNeedsTranslation(boolean needs) {
        needsTranslationProperty().set(needs);
    }

    // === Language/Option Selection ===

    /**
     * Property for the selected translation language or hearing assistance option.
     */
    StringProperty selectedLanguageProperty();

    /**
     * Returns the selected language/option.
     */
    default String getSelectedLanguage() {
        return selectedLanguageProperty().get();
    }

    /**
     * Sets the selected language/option.
     */
    default void setSelectedLanguage(String language) {
        selectedLanguageProperty().set(language);
    }

    /**
     * Returns the list of available languages/options for translation or hearing assistance.
     */
    List<String> getAvailableLanguages();

    /**
     * Sets the available languages/options.
     * @param languages list of language names or hearing assistance options
     */
    void setAvailableLanguages(List<String> languages);

    /**
     * Clears all available languages/options.
     */
    void clearAvailableLanguages();

    /**
     * Returns true if there are any languages/options available.
     */
    default boolean hasAvailableLanguages() {
        return !getAvailableLanguages().isEmpty();
    }

    // === Validation ===

    /**
     * Returns a validation message if the section is invalid, or null if valid.
     * Section is invalid if translation is needed but no language is selected.
     */
    String getValidationMessage();

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

        // Push needsTranslation changes to state
        needsTranslationProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setNeedsTranslation(newVal);
        });

        // Push selected language changes to state
        selectedLanguageProperty().addListener((obs, oldVal, newVal) -> {
            selectionState.setTranslationLanguage(newVal);
        });

        // Initialize state from current values
        selectionState.setNeedsTranslation(needsTranslation());
        if (getSelectedLanguage() != null) {
            selectionState.setTranslationLanguage(getSelectedLanguage());
        }
    }
}

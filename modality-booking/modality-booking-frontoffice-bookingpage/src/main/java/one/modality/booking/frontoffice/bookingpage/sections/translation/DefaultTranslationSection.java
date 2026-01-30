package one.modality.booking.frontoffice.bookingpage.sections.translation;

import dev.webfx.extras.i18n.I18n;
import dev.webfx.extras.i18n.controls.I18nControls;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.components.BookingPageUIBuilder;
import one.modality.booking.frontoffice.bookingpage.components.StyledSectionHeader;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of the "Translation or Hard of Hearing" section.
 * Provides a toggle to enable translation/hearing assistance and a selection
 * of available languages or hearing assistance options.
 *
 * <p>Both translation and hard of hearing options use the same headphone system
 * and share the same API data source.</p>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-translation-section} - section container</li>
 *   <li>{@code .bookingpage-translation-toggle} - toggle control</li>
 *   <li>{@code .bookingpage-translation-option} - language/option item</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see HasTranslationSection
 */
public class DefaultTranslationSection implements HasTranslationSection {

    // I18n keys for this section
    private static final String TranslationOrHardOfHearing = "TranslationOrHardOfHearing";
    private static final String INeedTranslationOrHearingAssistance = "INeedTranslationOrHearingAssistance";
    private static final String SelectLanguageOrOption = "SelectLanguageOrOption";
    private static final String TranslationRequiredWarning = "TranslationRequiredWarning";

    // === COLOR SCHEME ===
    protected final ObjectProperty<BookingFormColorScheme> colorScheme = new SimpleObjectProperty<>(BookingFormColorScheme.DEFAULT);

    // === VISIBILITY ===
    protected final BooleanProperty visibleProperty = new SimpleBooleanProperty(true);

    // === DATA PROPERTIES ===
    protected final BooleanProperty needsTranslationProperty = new SimpleBooleanProperty(false);
    protected final StringProperty selectedLanguageProperty = new SimpleStringProperty();
    protected final List<String> availableLanguages = new ArrayList<>();

    // === VALIDATION ===
    protected final BooleanProperty validProperty = new SimpleBooleanProperty(true);

    // === UI COMPONENTS ===
    protected final VBox container = new VBox();
    protected HBox sectionHeader;
    protected VBox toggleContainer;
    protected VBox languageSelectionContainer;
    protected ToggleGroup languageToggleGroup;
    protected List<RadioButton> languageRadioButtons = new ArrayList<>();

    // === DATA ===
    protected WorkingBookingProperties workingBookingProperties;
    protected Runnable onSelectionChanged;

    public DefaultTranslationSection() {
        buildUI();
        setupBindings();
        updateValidity();
    }

    protected void buildUI() {
        container.setAlignment(Pos.TOP_LEFT);
        container.setSpacing(16);
        container.getStyleClass().add("bookingpage-translation-section");

        // Section header with headphones icon
        sectionHeader = new StyledSectionHeader(TranslationOrHardOfHearing, StyledSectionHeader.ICON_HEADPHONES);
        VBox.setMargin(sectionHeader, new Insets(0, 0, 8, 0));

        // Toggle container with checkbox-style toggle
        toggleContainer = new VBox(8);
        toggleContainer.getStyleClass().add("bookingpage-translation-toggle");

        // Create the toggle as a styled checkbox card
        Label toggleLabel = I18nControls.newLabel(INeedTranslationOrHearingAssistance);
        toggleLabel.getStyleClass().add("bookingpage-checkbox-label");
        HBox toggleCard = BookingPageUIBuilder.createCheckboxCard(
            toggleLabel,
            needsTranslationProperty,
            colorScheme
        );
        toggleContainer.getChildren().add(toggleCard);

        // Language selection container (shown when toggle is enabled)
        languageSelectionContainer = new VBox(8);
        languageSelectionContainer.setVisible(false);
        languageSelectionContainer.setManaged(false);
        languageSelectionContainer.setPadding(new Insets(8, 0, 0, 24)); // Indent under toggle

        Label selectLabel = I18nControls.newLabel(SelectLanguageOrOption);
        selectLabel.getStyleClass().add("bookingpage-form-label");
        languageSelectionContainer.getChildren().add(selectLabel);

        languageToggleGroup = new ToggleGroup();

        container.getChildren().addAll(sectionHeader, toggleContainer, languageSelectionContainer);
    }

    /**
     * Rebuilds the language radio buttons based on available languages.
     */
    protected void rebuildLanguageOptions() {
        // Clear existing options (keep the label)
        while (languageSelectionContainer.getChildren().size() > 1) {
            languageSelectionContainer.getChildren().remove(1);
        }
        languageRadioButtons.clear();

        // Create radio buttons for each language
        for (String language : availableLanguages) {
            RadioButton radioButton = new RadioButton(language);
            radioButton.setToggleGroup(languageToggleGroup);
            radioButton.getStyleClass().add("bookingpage-translation-option");
            radioButton.setPadding(new Insets(8, 12, 8, 12));

            // Bind selection
            radioButton.selectedProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal) {
                    selectedLanguageProperty.set(language);
                    notifySelectionChanged();
                }
            });

            languageRadioButtons.add(radioButton);
            languageSelectionContainer.getChildren().add(radioButton);
        }

        // Select first option by default if languages are available
        if (!languageRadioButtons.isEmpty() && needsTranslationProperty.get()) {
            languageRadioButtons.get(0).setSelected(true);
        }
    }

    protected void setupBindings() {
        // Show/hide language selection based on toggle
        needsTranslationProperty.addListener((obs, oldVal, newVal) -> {
            languageSelectionContainer.setVisible(newVal);
            languageSelectionContainer.setManaged(newVal);

            if (newVal && !languageRadioButtons.isEmpty() && selectedLanguageProperty.get() == null) {
                // Auto-select first option when enabled
                languageRadioButtons.get(0).setSelected(true);
            } else if (!newVal) {
                // Clear selection when disabled
                selectedLanguageProperty.set(null);
                if (languageToggleGroup.getSelectedToggle() != null) {
                    languageToggleGroup.getSelectedToggle().setSelected(false);
                }
            }

            updateValidity();
            notifySelectionChanged();
        });

        // Update validity when selection changes
        selectedLanguageProperty.addListener((obs, oldVal, newVal) -> {
            updateValidity();
        });

        // Update visibility
        visibleProperty.addListener((obs, oldVal, newVal) -> {
            container.setVisible(newVal);
            container.setManaged(newVal);
            updateValidity();
        });

        // Update toggle card when color scheme changes
        colorScheme.addListener((obs, oldVal, newVal) -> {
            rebuildToggleCard();
        });

        // Initial visibility
        container.setVisible(visibleProperty.get());
        container.setManaged(visibleProperty.get());
    }

    /**
     * Rebuilds the toggle card with the current color scheme.
     */
    protected void rebuildToggleCard() {
        toggleContainer.getChildren().clear();
        Label toggleLabel = I18nControls.newLabel(INeedTranslationOrHearingAssistance);
        toggleLabel.getStyleClass().add("bookingpage-checkbox-label");
        HBox toggleCard = BookingPageUIBuilder.createCheckboxCard(
            toggleLabel,
            needsTranslationProperty,
            colorScheme
        );
        toggleContainer.getChildren().add(toggleCard);
    }

    /**
     * Updates the validity based on current state.
     * Invalid if translation is needed but no language is selected.
     */
    protected void updateValidity() {
        if (!visibleProperty.get()) {
            validProperty.set(true);
            return;
        }

        if (needsTranslationProperty.get()) {
            String selected = selectedLanguageProperty.get();
            validProperty.set(selected != null && !selected.isEmpty());
        } else {
            validProperty.set(true);
        }
    }

    protected void notifySelectionChanged() {
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    // ========================================
    // BookingFormSection INTERFACE
    // ========================================

    @Override
    public Object getTitleI18nKey() {
        return TranslationOrHardOfHearing;
    }

    @Override
    public Node getView() {
        return container;
    }

    @Override
    public void setWorkingBookingProperties(WorkingBookingProperties workingBookingProperties) {
        this.workingBookingProperties = workingBookingProperties;
    }

    @Override
    public ObservableBooleanValue validProperty() {
        return validProperty;
    }

    // ========================================
    // HasTranslationSection INTERFACE
    // ========================================

    @Override
    public ObjectProperty<BookingFormColorScheme> colorSchemeProperty() {
        return colorScheme;
    }

    @Override
    public void setColorScheme(BookingFormColorScheme scheme) {
        this.colorScheme.set(scheme);
    }

    @Override
    public BooleanProperty visibleProperty() {
        return visibleProperty;
    }

    @Override
    public void setVisible(boolean visible) {
        visibleProperty.set(visible);
    }

    @Override
    public boolean isVisible() {
        return visibleProperty.get();
    }

    @Override
    public BooleanProperty needsTranslationProperty() {
        return needsTranslationProperty;
    }

    @Override
    public StringProperty selectedLanguageProperty() {
        return selectedLanguageProperty;
    }

    @Override
    public List<String> getAvailableLanguages() {
        return new ArrayList<>(availableLanguages);
    }

    @Override
    public void setAvailableLanguages(List<String> languages) {
        availableLanguages.clear();
        if (languages != null) {
            availableLanguages.addAll(languages);
        }
        rebuildLanguageOptions();
    }

    @Override
    public void clearAvailableLanguages() {
        availableLanguages.clear();
        rebuildLanguageOptions();
    }

    @Override
    public String getValidationMessage() {
        if (!visibleProperty.get()) {
            return null;
        }

        if (needsTranslationProperty.get()) {
            String selected = selectedLanguageProperty.get();
            if (selected == null || selected.isEmpty()) {
                return I18n.getI18nText(TranslationRequiredWarning);
            }
        }
        return null;
    }

    @Override
    public void reset() {
        needsTranslationProperty.set(false);
        selectedLanguageProperty.set(null);
        if (languageToggleGroup.getSelectedToggle() != null) {
            languageToggleGroup.getSelectedToggle().setSelected(false);
        }
        updateValidity();
    }

    @Override
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }
}

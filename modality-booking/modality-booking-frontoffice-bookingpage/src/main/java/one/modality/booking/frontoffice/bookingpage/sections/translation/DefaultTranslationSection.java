package one.modality.booking.frontoffice.bookingpage.sections.translation;

import dev.webfx.extras.i18n.I18n;
import dev.webfx.extras.i18n.controls.I18nControls;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.components.BookingPageUIBuilder;
import one.modality.booking.frontoffice.bookingpage.components.StyledSectionHeader;
import one.modality.booking.frontoffice.bookingpage.standard.BookingSelectionState;

import static one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of the "Translation or Hard of Hearing" section.
 * Uses a unified card pattern where the language selection pills appear inside
 * the same card as the checkbox toggle (following the CarPark section pattern).
 *
 * <p>Both translation and hard of hearing options use the same headphone system
 * and share the same API data source.</p>
 *
 * <p>This section follows the Selection Model Pattern - it does not store data internally
 * but binds to {@link BookingSelectionState} for centralized state management.</p>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-translation-section} - section container</li>
 *   <li>{@code .bookingpage-checkbox-card} - unified card container</li>
 *   <li>{@code .bookingpage-translation-subsection} - language pills subsection</li>
 *   <li>{@code .bookingpage-radio-pill} - language option pill</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see HasTranslationSection
 * @see BookingSelectionState
 */
public class DefaultTranslationSection implements HasTranslationSection {

    // I18n keys for this section
    private static final String TranslationOrHardOfHearing = "TranslationOrHardOfHearing";
    private static final String INeedTranslationOrHearingAssistance = "INeedTranslationOrHearingAssistance";
    private static final String SelectLanguageOrOption = "SelectLanguageOrOption";
    private static final String TranslationRequiredWarning = "TranslationRequiredWarning";

    // === VISIBILITY ===
    protected final BooleanProperty visibleProperty = new SimpleBooleanProperty(true);

    // === VALIDATION ===
    protected final BooleanProperty validProperty = new SimpleBooleanProperty(true);

    // === STATE BINDING ===
    protected BookingSelectionState selectionState;

    // === AVAILABLE LANGUAGES (configuration, not selection state) ===
    protected final List<String> availableLanguages = new ArrayList<>();

    // === UI COMPONENTS ===
    protected final VBox container = new VBox();
    protected HBox sectionHeader;
    protected VBox translationCard;  // Single unified card

    // === DATA ===
    protected WorkingBookingProperties workingBookingProperties;
    protected Runnable onSelectionChanged;

    public DefaultTranslationSection() {
        buildUI();
        setupVisibilityBindings();
        updateValidity();
    }

    protected void buildUI() {
        container.setAlignment(Pos.TOP_LEFT);
        container.setSpacing(16);
        container.getStyleClass().add(bookingpage_translation_section);

        // Section header with headphones icon
        sectionHeader = new StyledSectionHeader(TranslationOrHardOfHearing, StyledSectionHeader.ICON_HEADPHONES);
        VBox.setMargin(sectionHeader, new Insets(0, 0, 8, 0));

        // Single unified card (will be built when state is bound)
        translationCard = new VBox(0);
        translationCard.getStyleClass().add(bookingpage_checkbox_card);

        container.getChildren().addAll(sectionHeader, translationCard);
    }

    /**
     * Binds this section to a BookingSelectionState for centralized state management.
     * Must be called before the section can function properly.
     *
     * @param state The BookingSelectionState to bind to
     */
    @Override
    public void bindToSelectionState(BookingSelectionState state) {
        this.selectionState = state;
        buildUnifiedTranslationCard();
        setupStateBindings();
    }

    /**
     * Builds the unified translation card containing both the checkbox toggle
     * and the language selection pills (when enabled).
     */
    protected void buildUnifiedTranslationCard() {
        translationCard.getChildren().clear();

        if (selectionState == null) return;

        BooleanProperty needsTranslation = selectionState.needsTranslationProperty();

        // Main row: checkbox indicator + label
        HBox mainRow = createMainRow(needsTranslation);
        translationCard.getChildren().add(mainRow);

        // Language subsection (visible only when enabled)
        if (!availableLanguages.isEmpty()) {
            VBox languageSubsection = createLanguageSubsection();
            languageSubsection.visibleProperty().bind(needsTranslation);
            languageSubsection.managedProperty().bind(needsTranslation);
            translationCard.getChildren().add(languageSubsection);
        }

        // Update card selection styling
        updateCardSelectedClass(needsTranslation.get());
        needsTranslation.addListener((obs, old, newVal) -> updateCardSelectedClass(newVal));
    }

    /**
     * Creates the main row with checkbox indicator and label.
     */
    protected HBox createMainRow(BooleanProperty needsTranslation) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(16));
        row.setCursor(Cursor.HAND);

        // Use standard CSS-based checkbox indicator
        StackPane checkbox = BookingPageUIBuilder.createCheckboxIndicator(needsTranslation);

        // Label
        Label label = I18nControls.newLabel(INeedTranslationOrHearingAssistance);
        label.getStyleClass().addAll(bookingpage_text_base, bookingpage_font_medium, bookingpage_text_dark);

        row.getChildren().addAll(checkbox, label);

        // Click toggles selection
        row.setOnMouseClicked(e -> needsTranslation.set(!needsTranslation.get()));

        return row;
    }

    /**
     * Creates the language subsection with pills inside the card.
     */
    protected VBox createLanguageSubsection() {
        VBox subsection = new VBox(10);
        subsection.getStyleClass().add(bookingpage_translation_subsection);
        subsection.setPadding(new Insets(0, 16, 16, 48));  // 48px left indent under checkbox

        // "Select language or option:" label
        Label selectLabel = I18nControls.newLabel(SelectLanguageOrOption);
        selectLabel.getStyleClass().addAll(bookingpage_text_xs, bookingpage_font_medium, bookingpage_text_muted);

        // FlowPane for pills
        FlowPane pillsContainer = new FlowPane();
        pillsContainer.setHgap(10);
        pillsContainer.setVgap(10);

        // Build pills using BookingPageUIBuilder.createRadioPill()
        ObjectProperty<String> selectedLangWrapper = new SimpleObjectProperty<>();
        selectedLangWrapper.set(selectionState.getTranslationLanguage());

        // Bidirectional sync with state
        selectionState.translationLanguageProperty().addListener((obs, old, newVal) -> {
            if (!java.util.Objects.equals(selectedLangWrapper.get(), newVal)) {
                selectedLangWrapper.set(newVal);
            }
        });
        selectedLangWrapper.addListener((obs, old, newVal) -> {
            if (!java.util.Objects.equals(selectionState.getTranslationLanguage(), newVal)) {
                selectionState.setTranslationLanguage(newVal);
            }
        });

        for (String language : availableLanguages) {
            HBox pill = BookingPageUIBuilder.createRadioPill(
                language,
                language,
                selectedLangWrapper,
                () -> selectionState.setTranslationLanguage(language)
            );
            pillsContainer.getChildren().add(pill);
        }

        subsection.getChildren().addAll(selectLabel, pillsContainer);
        return subsection;
    }

    /**
     * Updates the card's "selected" CSS class based on state.
     */
    protected void updateCardSelectedClass(boolean isSelected) {
        if (isSelected) {
            if (!translationCard.getStyleClass().contains(selected)) {
                translationCard.getStyleClass().add(selected);
            }
        } else {
            translationCard.getStyleClass().remove(selected);
        }
    }

    /**
     * Sets up bindings to the BookingSelectionState.
     */
    protected void setupStateBindings() {
        if (selectionState == null) return;

        BooleanProperty needsTranslation = selectionState.needsTranslationProperty();
        StringProperty selectedLanguage = selectionState.translationLanguageProperty();

        // Auto-select first language when enabling
        needsTranslation.addListener((obs, oldVal, newVal) -> {
            if (newVal && !availableLanguages.isEmpty()
                && (selectedLanguage.get() == null || selectedLanguage.get().isEmpty())) {
                selectedLanguage.set(availableLanguages.get(0));
            } else if (!newVal) {
                selectedLanguage.set(null);
            }
            updateValidity();
            notifySelectionChanged();
        });

        selectedLanguage.addListener((obs, oldVal, newVal) -> {
            updateValidity();
            notifySelectionChanged();
        });
    }

    /**
     * Sets up visibility bindings (independent of state).
     */
    protected void setupVisibilityBindings() {
        visibleProperty.addListener((obs, oldVal, newVal) -> {
            container.setVisible(newVal);
            container.setManaged(newVal);
            updateValidity();
        });

        // Initial visibility
        container.setVisible(visibleProperty.get());
        container.setManaged(visibleProperty.get());
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

        if (selectionState != null && selectionState.needsTranslation()) {
            String selected = selectionState.getTranslationLanguage();
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
        return selectionState != null ? selectionState.needsTranslationProperty() : new SimpleBooleanProperty(false);
    }

    @Override
    public StringProperty selectedLanguageProperty() {
        return selectionState != null ? selectionState.translationLanguageProperty() : new SimpleStringProperty();
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
        buildUnifiedTranslationCard();  // Rebuild entire card
    }

    @Override
    public void clearAvailableLanguages() {
        availableLanguages.clear();
        buildUnifiedTranslationCard();
    }

    @Override
    public String getValidationMessage() {
        if (!visibleProperty.get()) {
            return null;
        }

        if (selectionState != null && selectionState.needsTranslation()) {
            String selected = selectionState.getTranslationLanguage();
            if (selected == null || selected.isEmpty()) {
                return I18n.getI18nText(TranslationRequiredWarning);
            }
        }
        return null;
    }

    @Override
    public void reset() {
        if (selectionState != null) {
            selectionState.setNeedsTranslation(false);
            selectionState.setTranslationLanguage(null);
        }
        updateValidity();
    }

    @Override
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }
}

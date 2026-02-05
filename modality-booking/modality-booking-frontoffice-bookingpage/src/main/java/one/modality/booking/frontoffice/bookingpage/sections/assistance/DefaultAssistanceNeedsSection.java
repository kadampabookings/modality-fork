package one.modality.booking.frontoffice.bookingpage.sections.assistance;

import dev.webfx.extras.i18n.controls.I18nControls;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.components.BookingPageUIBuilder;
import one.modality.booking.frontoffice.bookingpage.components.StyledSectionHeader;
import one.modality.booking.frontoffice.bookingpage.standard.BookingSelectionState;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;

/**
 * Default implementation of the "Assistance Needs" section.
 * Provides styled checkbox cards for mobility, hearing, and visual assistance,
 * plus a text field for dietary requirements.
 *
 * <p>This section follows the Selection Model Pattern - it does not store data internally
 * but binds to {@link BookingSelectionState} for centralized state management.</p>
 *
 * <p>Note: No "Other" free-text field is provided. Only the specific
 * checkboxes and dietary field are available.</p>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-assistance-section} - section container</li>
 *   <li>{@code .bookingpage-checkbox-card} - checkbox card styling</li>
 *   <li>{@code .bookingpage-assistance-dietary} - dietary text field</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see HasAssistanceNeedsSection
 * @see BookingSelectionState
 */
public class DefaultAssistanceNeedsSection implements HasAssistanceNeedsSection {

    // I18n keys for this section
    private static final String AssistanceNeeds = "AssistanceNeeds";
    private static final String MobilityAssistance = "MobilityAssistance";
    private static final String MobilityAssistanceDesc = "MobilityAssistanceDesc";
    private static final String HearingAssistance = "HearingAssistance";
    private static final String HearingAssistanceDesc = "HearingAssistanceDesc";
    private static final String VisualAssistance = "VisualAssistance";
    private static final String VisualAssistanceDesc = "VisualAssistanceDesc";

    // === COLOR SCHEME ===
    protected final ObjectProperty<BookingFormColorScheme> colorScheme = new SimpleObjectProperty<>(BookingFormColorScheme.DEFAULT);

    // === VISIBILITY ===
    protected final BooleanProperty visibleProperty = new SimpleBooleanProperty(true);

    // === VALIDATION ===
    protected final BooleanProperty validProperty = new SimpleBooleanProperty(true);

    // === STATE BINDING ===
    protected BookingSelectionState selectionState;

    // === UI COMPONENTS ===
    protected final VBox container = new VBox();
    protected HBox sectionHeader;
    protected HBox infoBox;
    protected VBox checkboxContainer;

    // === DATA ===
    protected WorkingBookingProperties workingBookingProperties;
    protected Runnable onSelectionChanged;

    public DefaultAssistanceNeedsSection() {
        buildUI();
        setupVisibilityBindings();
    }

    protected void buildUI() {
        container.setAlignment(Pos.TOP_LEFT);
        container.setSpacing(16);
        container.getStyleClass().add("bookingpage-assistance-section");

        // Section header with accessibility icon
        sectionHeader = new StyledSectionHeader(AssistanceNeeds, StyledSectionHeader.ICON_USERS);
        VBox.setMargin(sectionHeader, new Insets(0, 0, 8, 0));

        // Info box explaining available accommodations
        infoBox = BookingPageUIBuilder.createInfoBox(
            "Please let us know if you require any assistance during the event.",
            BookingPageUIBuilder.InfoBoxType.NEUTRAL
        );
        VBox.setMargin(infoBox, new Insets(0, 0, 8, 0));

        // Checkbox cards container (cards built when state is bound)
        checkboxContainer = new VBox(12);
        checkboxContainer.setPadding(new Insets(8, 0, 0, 0));

        container.getChildren().addAll(sectionHeader, infoBox, checkboxContainer);
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
        buildCheckboxCards();
        setupStateBindings();
        rebuildColorSchemeBindings();
    }

    /**
     * Builds styled checkbox cards for each assistance type.
     */
    protected void buildCheckboxCards() {
        checkboxContainer.getChildren().clear();

        if (selectionState == null) return;

        // Mobility Assistance checkbox card
        VBox mobilityContent = createCheckboxContent(MobilityAssistance, MobilityAssistanceDesc);
        HBox mobilityCard = BookingPageUIBuilder.createCheckboxCard(
            mobilityContent,
            selectionState.assistanceMobilityProperty(),
            colorScheme
        );

        // Hearing Assistance checkbox card
        VBox hearingContent = createCheckboxContent(HearingAssistance, HearingAssistanceDesc);
        HBox hearingCard = BookingPageUIBuilder.createCheckboxCard(
            hearingContent,
            selectionState.assistanceHearingProperty(),
            colorScheme
        );

        // Visual Assistance checkbox card
        VBox visualContent = createCheckboxContent(VisualAssistance, VisualAssistanceDesc);
        HBox visualCard = BookingPageUIBuilder.createCheckboxCard(
            visualContent,
            selectionState.assistanceVisualProperty(),
            colorScheme
        );

        checkboxContainer.getChildren().addAll(mobilityCard, hearingCard, visualCard);
    }

    /**
     * Creates the content (label + description) for a checkbox card.
     */
    protected VBox createCheckboxContent(Object titleKey, Object descriptionKey) {
        VBox content = new VBox(4);

        Label titleLabel = I18nControls.newLabel(titleKey);
        titleLabel.getStyleClass().addAll("bookingpage-text-base", "bookingpage-font-medium", "bookingpage-text-dark");

        Label descLabel = I18nControls.newLabel(descriptionKey);
        descLabel.getStyleClass().addAll("bookingpage-text-sm", "bookingpage-text-muted");
        descLabel.setWrapText(true);

        content.getChildren().addAll(titleLabel, descLabel);
        return content;
    }

    /**
     * Sets up bindings to the BookingSelectionState.
     */
    protected void setupStateBindings() {
        if (selectionState == null) return;

        // Listen for selection changes
        selectionState.assistanceMobilityProperty().addListener((obs, old, newVal) -> notifySelectionChanged());
        selectionState.assistanceHearingProperty().addListener((obs, old, newVal) -> notifySelectionChanged());
        selectionState.assistanceVisualProperty().addListener((obs, old, newVal) -> notifySelectionChanged());
    }

    /**
     * Sets up visibility bindings (independent of state).
     */
    protected void setupVisibilityBindings() {
        visibleProperty.addListener((obs, oldVal, newVal) -> {
            container.setVisible(newVal);
            container.setManaged(newVal);
        });

        // Initial visibility
        container.setVisible(visibleProperty.get());
        container.setManaged(visibleProperty.get());
    }

    /**
     * Sets up color scheme change listener to rebuild cards.
     */
    protected void rebuildColorSchemeBindings() {
        colorScheme.addListener((obs, old, newVal) -> buildCheckboxCards());
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
        return AssistanceNeeds;
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
    // HasAssistanceNeedsSection INTERFACE
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
    public BooleanProperty mobilityAssistanceProperty() {
        return selectionState != null ? selectionState.assistanceMobilityProperty() : new SimpleBooleanProperty(false);
    }

    @Override
    public BooleanProperty hearingAssistanceProperty() {
        return selectionState != null ? selectionState.assistanceHearingProperty() : new SimpleBooleanProperty(false);
    }

    @Override
    public BooleanProperty visualAssistanceProperty() {
        return selectionState != null ? selectionState.assistanceVisualProperty() : new SimpleBooleanProperty(false);
    }

    @Override
    public StringProperty dietaryRequirementsProperty() {
        // Dietary requirements removed - return empty property for interface compatibility
        return new SimpleStringProperty();
    }

    @Override
    public void reset() {
        if (selectionState != null) {
            selectionState.setAssistanceMobility(false);
            selectionState.setAssistanceHearing(false);
            selectionState.setAssistanceVisual(false);
        }
    }

    @Override
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }
}

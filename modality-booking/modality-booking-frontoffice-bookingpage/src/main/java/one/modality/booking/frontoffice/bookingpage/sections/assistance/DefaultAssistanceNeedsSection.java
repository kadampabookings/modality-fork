package one.modality.booking.frontoffice.bookingpage.sections.assistance;

import dev.webfx.extras.i18n.I18n;
import dev.webfx.extras.i18n.controls.I18nControls;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.components.BookingPageUIBuilder;
import one.modality.booking.frontoffice.bookingpage.components.StyledSectionHeader;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;

/**
 * Default implementation of the "Assistance Needs" section.
 * Provides checkboxes for mobility, hearing, and visual assistance,
 * plus a text field for dietary requirements.
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
 * @see HasAssistanceNeedsSection
 */
public class DefaultAssistanceNeedsSection implements HasAssistanceNeedsSection {

    // I18n keys for this section
    private static final String AssistanceNeeds = "AssistanceNeeds";
    private static final String AssistanceNeedsInfo = "AssistanceNeedsInfo";
    private static final String MobilityAssistance = "MobilityAssistance";
    private static final String MobilityAssistanceDesc = "MobilityAssistanceDesc";
    private static final String HearingAssistance = "HearingAssistance";
    private static final String HearingAssistanceDesc = "HearingAssistanceDesc";
    private static final String VisualAssistance = "VisualAssistance";
    private static final String VisualAssistanceDesc = "VisualAssistanceDesc";
    private static final String DietaryRequirements = "DietaryRequirements";
    private static final String DietaryRequirementsPlaceholder = "DietaryRequirementsPlaceholder";

    // === COLOR SCHEME ===
    protected final ObjectProperty<BookingFormColorScheme> colorScheme = new SimpleObjectProperty<>(BookingFormColorScheme.DEFAULT);

    // === VISIBILITY ===
    protected final BooleanProperty visibleProperty = new SimpleBooleanProperty(true);

    // === DATA PROPERTIES ===
    protected final BooleanProperty mobilityAssistanceProperty = new SimpleBooleanProperty(false);
    protected final BooleanProperty hearingAssistanceProperty = new SimpleBooleanProperty(false);
    protected final BooleanProperty visualAssistanceProperty = new SimpleBooleanProperty(false);
    protected final StringProperty dietaryRequirementsProperty = new SimpleStringProperty("");

    // === VALIDATION ===
    protected final BooleanProperty validProperty = new SimpleBooleanProperty(true);

    // === UI COMPONENTS ===
    protected final VBox container = new VBox();
    protected HBox sectionHeader;
    protected HBox infoBox;
    protected VBox checkboxContainer;
    protected CheckBox mobilityCheckbox;
    protected CheckBox hearingCheckbox;
    protected CheckBox visualCheckbox;
    protected VBox dietaryFieldContainer;
    protected TextField dietaryTextField;

    // === DATA ===
    protected WorkingBookingProperties workingBookingProperties;
    protected Runnable onSelectionChanged;

    public DefaultAssistanceNeedsSection() {
        buildUI();
        setupBindings();
    }

    protected void buildUI() {
        container.setAlignment(Pos.TOP_LEFT);
        container.setSpacing(16);
        container.getStyleClass().add("bookingpage-assistance-section");

        // Section header with accessibility icon
        sectionHeader = new StyledSectionHeader(AssistanceNeeds, StyledSectionHeader.ICON_USERS);
        VBox.setMargin(sectionHeader, new Insets(0, 0, 8, 0));

        // Info box explaining available accommodations
        infoBox = BookingPageUIBuilder.createInfoBox(AssistanceNeedsInfo, BookingPageUIBuilder.InfoBoxType.NEUTRAL);
        VBox.setMargin(infoBox, new Insets(0, 0, 8, 0));

        // Checkbox container
        checkboxContainer = new VBox(12);

        // Mobility assistance checkbox
        mobilityCheckbox = new CheckBox();
        I18n.bindI18nTextProperty(mobilityCheckbox.textProperty(), MobilityAssistance);
        mobilityCheckbox.getStyleClass().add("bookingpage-assistance-checkbox");
        mobilityCheckbox.selectedProperty().bindBidirectional(mobilityAssistanceProperty);
        VBox mobilityBox = createCheckboxWithDescription(mobilityCheckbox, MobilityAssistanceDesc);

        // Hearing assistance checkbox
        hearingCheckbox = new CheckBox();
        I18n.bindI18nTextProperty(hearingCheckbox.textProperty(), HearingAssistance);
        hearingCheckbox.getStyleClass().add("bookingpage-assistance-checkbox");
        hearingCheckbox.selectedProperty().bindBidirectional(hearingAssistanceProperty);
        VBox hearingBox = createCheckboxWithDescription(hearingCheckbox, HearingAssistanceDesc);

        // Visual assistance checkbox
        visualCheckbox = new CheckBox();
        I18n.bindI18nTextProperty(visualCheckbox.textProperty(), VisualAssistance);
        visualCheckbox.getStyleClass().add("bookingpage-assistance-checkbox");
        visualCheckbox.selectedProperty().bindBidirectional(visualAssistanceProperty);
        VBox visualBox = createCheckboxWithDescription(visualCheckbox, VisualAssistanceDesc);

        checkboxContainer.getChildren().addAll(mobilityBox, hearingBox, visualBox);

        // Dietary requirements text field
        dietaryFieldContainer = new VBox(6);
        dietaryFieldContainer.getStyleClass().add("bookingpage-assistance-dietary");

        Label dietaryLabel = I18nControls.newLabel(DietaryRequirements);
        dietaryLabel.getStyleClass().add("bookingpage-form-label");

        dietaryTextField = new TextField();
        I18n.bindI18nPromptProperty(dietaryTextField.promptTextProperty(), DietaryRequirementsPlaceholder);
        dietaryTextField.getStyleClass().add("bookingpage-text-input");
        dietaryTextField.textProperty().bindBidirectional(dietaryRequirementsProperty);
        dietaryTextField.setPadding(new Insets(12, 14, 12, 14));
        dietaryTextField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(dietaryTextField, Priority.ALWAYS);

        dietaryFieldContainer.getChildren().addAll(dietaryLabel, dietaryTextField);

        container.getChildren().addAll(sectionHeader, infoBox, checkboxContainer, dietaryFieldContainer);
    }

    /**
     * Creates a checkbox with an optional description label below it.
     */
    protected VBox createCheckboxWithDescription(CheckBox checkbox, String descriptionI18nKey) {
        VBox box = new VBox(4);

        box.getChildren().add(checkbox);

        if (descriptionI18nKey != null) {
            Label descLabel = I18nControls.newLabel(descriptionI18nKey);
            descLabel.getStyleClass().add("bookingpage-info-text");
            descLabel.setWrapText(true);
            VBox.setMargin(descLabel, new Insets(0, 0, 0, 24)); // Indent under checkbox
            box.getChildren().add(descLabel);
        }

        return box;
    }

    protected void setupBindings() {
        // Update visibility
        visibleProperty.addListener((obs, oldVal, newVal) -> {
            container.setVisible(newVal);
            container.setManaged(newVal);
        });

        // Notify on any selection change
        mobilityAssistanceProperty.addListener((obs, oldVal, newVal) -> notifySelectionChanged());
        hearingAssistanceProperty.addListener((obs, oldVal, newVal) -> notifySelectionChanged());
        visualAssistanceProperty.addListener((obs, oldVal, newVal) -> notifySelectionChanged());
        dietaryRequirementsProperty.addListener((obs, oldVal, newVal) -> notifySelectionChanged());

        // Initial visibility
        container.setVisible(visibleProperty.get());
        container.setManaged(visibleProperty.get());
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
        return mobilityAssistanceProperty;
    }

    @Override
    public BooleanProperty hearingAssistanceProperty() {
        return hearingAssistanceProperty;
    }

    @Override
    public BooleanProperty visualAssistanceProperty() {
        return visualAssistanceProperty;
    }

    @Override
    public StringProperty dietaryRequirementsProperty() {
        return dietaryRequirementsProperty;
    }

    @Override
    public void reset() {
        mobilityAssistanceProperty.set(false);
        hearingAssistanceProperty.set(false);
        visualAssistanceProperty.set(false);
        dietaryRequirementsProperty.set("");
    }

    @Override
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }
}

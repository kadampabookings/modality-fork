package one.modality.booking.frontoffice.bookingpage.sections.ordination;

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

import static one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Default implementation of the "Ordination Ceremony" section.
 * Provides a toggle card for ordained people to register for the ordination
 * ceremony during international festivals.
 *
 * <p>This section is only visible when the person being booked is ordained.</p>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-ordination-section} - section container</li>
 *   <li>{@code .bookingpage-ordination-card} - toggle card</li>
 *   <li>{@code .bookingpage-ordination-datetime} - ceremony datetime display</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see HasOrdinationCeremonySection
 */
public class DefaultOrdinationCeremonySection implements HasOrdinationCeremonySection {

    // I18n keys for this section
    private static final String OrdinationCeremony = "OrdinationCeremony";
    private static final String AttendOrdinationCeremony = "AttendOrdinationCeremony";
    private static final String OrdinationCeremonyInfo = "OrdinationCeremonyInfo";

    // Date/time formatter for display
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a");

    // === VISIBILITY ===
    protected final BooleanProperty visibleProperty = new SimpleBooleanProperty(false);
    protected final BooleanProperty isOrdainedProperty = new SimpleBooleanProperty(false);

    // === DATA PROPERTIES ===
    protected final BooleanProperty wantsOrdinationCeremonyProperty = new SimpleBooleanProperty(false);
    protected final ObjectProperty<LocalDateTime> ceremonyDateTimeProperty = new SimpleObjectProperty<>();
    protected final StringProperty ceremonyLocationProperty = new SimpleStringProperty();

    // === VALIDATION ===
    protected final BooleanProperty validProperty = new SimpleBooleanProperty(true);

    // === UI COMPONENTS ===
    protected final VBox container = new VBox();
    protected HBox sectionHeader;
    protected VBox cardContainer;
    protected Label dateTimeLabel;
    protected Label locationLabel;

    // === DATA ===
    protected WorkingBookingProperties workingBookingProperties;
    protected Runnable onSelectionChanged;

    public DefaultOrdinationCeremonySection() {
        buildUI();
        setupBindings();
    }

    protected void buildUI() {
        container.setAlignment(Pos.TOP_LEFT);
        container.setSpacing(16);
        container.getStyleClass().add(bookingpage_ordination_section);

        // Section header with ceremony icon
        sectionHeader = new StyledSectionHeader(OrdinationCeremony, StyledSectionHeader.ICON_USERS);
        VBox.setMargin(sectionHeader, new Insets(0, 0, 8, 0));

        // Card container for the toggle
        cardContainer = new VBox(12);
        cardContainer.getStyleClass().add(bookingpage_ordination_card);

        // Create the toggle card
        rebuildToggleCard();

        // Date/time and location display
        VBox infoContainer = new VBox(4);
        infoContainer.setPadding(new Insets(0, 0, 0, 24)); // Indent under toggle
        infoContainer.getStyleClass().add(bookingpage_ordination_datetime);

        dateTimeLabel = new Label();
        dateTimeLabel.getStyleClass().add(bookingpage_info_text);

        locationLabel = new Label();
        locationLabel.getStyleClass().add(bookingpage_info_text);

        infoContainer.getChildren().addAll(dateTimeLabel, locationLabel);

        container.getChildren().addAll(sectionHeader, cardContainer, infoContainer);

        // Initially hidden
        container.setVisible(false);
        container.setManaged(false);
    }

    /**
     * Rebuilds the toggle card with the current color scheme.
     */
    protected void rebuildToggleCard() {
        cardContainer.getChildren().clear();

        // Create content with label and description
        VBox content = new VBox(4);
        Label titleLabel = I18nControls.newLabel(AttendOrdinationCeremony);
        titleLabel.getStyleClass().add(bookingpage_checkbox_label);
        Label descLabel = I18nControls.newLabel(OrdinationCeremonyInfo);
        descLabel.getStyleClass().add(bookingpage_checkbox_description);
        descLabel.setWrapText(true);
        content.getChildren().addAll(titleLabel, descLabel);

        HBox toggleCard = BookingPageUIBuilder.createCheckboxCard(
            content,
            wantsOrdinationCeremonyProperty,
            null
        );

        cardContainer.getChildren().add(toggleCard);
    }

    protected void setupBindings() {
        // Update visibility based on ordained status and section visibility
        Runnable updateVisibility = () -> {
            boolean shouldBeVisible = visibleProperty.get() && isOrdainedProperty.get();
            container.setVisible(shouldBeVisible);
            container.setManaged(shouldBeVisible);
        };

        visibleProperty.addListener((obs, oldVal, newVal) -> updateVisibility.run());
        isOrdainedProperty.addListener((obs, oldVal, newVal) -> updateVisibility.run());

        // Update date/time display when ceremony datetime changes
        ceremonyDateTimeProperty.addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                dateTimeLabel.setText(newVal.format(DATE_TIME_FORMATTER));
                dateTimeLabel.setVisible(true);
            } else {
                dateTimeLabel.setText("");
                dateTimeLabel.setVisible(false);
            }
        });

        // Update location display when location changes
        ceremonyLocationProperty.addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                locationLabel.setText(newVal);
                locationLabel.setVisible(true);
            } else {
                locationLabel.setText("");
                locationLabel.setVisible(false);
            }
        });

        // Notify selection changed when toggle changes
        wantsOrdinationCeremonyProperty.addListener((obs, oldVal, newVal) -> {
            notifySelectionChanged();
        });

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
        return OrdinationCeremony;
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
    // HasOrdinationCeremonySection INTERFACE
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
        return visibleProperty.get() && isOrdainedProperty.get();
    }

    @Override
    public BooleanProperty isOrdainedProperty() {
        return isOrdainedProperty;
    }

    @Override
    public BooleanProperty wantsOrdinationCeremonyProperty() {
        return wantsOrdinationCeremonyProperty;
    }

    @Override
    public ObjectProperty<LocalDateTime> ceremonyDateTimeProperty() {
        return ceremonyDateTimeProperty;
    }

    @Override
    public StringProperty ceremonyLocationProperty() {
        return ceremonyLocationProperty;
    }

    @Override
    public void reset() {
        wantsOrdinationCeremonyProperty.set(false);
    }

    @Override
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }
}

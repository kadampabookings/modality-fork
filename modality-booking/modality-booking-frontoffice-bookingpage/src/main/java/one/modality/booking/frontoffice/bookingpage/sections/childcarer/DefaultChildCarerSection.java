package one.modality.booking.frontoffice.bookingpage.sections.childcarer;

import dev.webfx.extras.i18n.I18n;
import dev.webfx.extras.i18n.controls.I18nControls;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.BookingPageI18nKeys;
import one.modality.booking.frontoffice.bookingpage.components.BookingPageUIBuilder;
import one.modality.booking.frontoffice.bookingpage.components.StyledSectionHeader;
import one.modality.booking.frontoffice.bookingpage.standard.BookingSelectionState;


import static one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of the "Child Carer Selection" section.
 * Uses card-based selection matching the JSX reference design.
 *
 * <p>Design features:</p>
 * <ul>
 *   <li>Selectable cards for each carer option (household members + Someone else)</li>
 *   <li>Responsive VBox layout with full-width cards</li>
 *   <li>Theme-based colors via CSS classes</li>
 *   <li>Expandable external carer form using theme colors</li>
 *   <li>Styled policy checkbox card</li>
 * </ul>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-childcarer-card} - carer selection card</li>
 *   <li>{@code .bookingpage-childcarer-external-fields} - external carer form</li>
 *   <li>{@code .bookingpage-childcarer-policy} - policy checkbox card</li>
 *   <li>{@code .bookingpage-childcarer-checkbox} - checkbox indicator</li>
 *   <li>{@code .bookingpage-childcarer-indicator} - selection indicator</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see HasChildCarerSection
 */
public class DefaultChildCarerSection implements HasChildCarerSection {

    // === VISIBILITY ===
    protected final BooleanProperty visibleProperty = new SimpleBooleanProperty(false);

    // === CHILD INFORMATION ===
    protected int childAge = 0;
    protected String childName = "";

    // === HOUSEHOLD MEMBERS ===
    protected final ObservableList<AccountMember> accountMembers = FXCollections.observableArrayList();

    // === CARER 1 DATA ===
    protected final StringProperty carer1TypeProperty = new SimpleStringProperty();
    protected final ObjectProperty<Object> carer1PersonIdProperty = new SimpleObjectProperty<>();
    protected final StringProperty carer1NameProperty = new SimpleStringProperty("");
    protected final StringProperty carer1BookingRefProperty = new SimpleStringProperty("");

    // === CARER 2 DATA ===
    protected final StringProperty carer2TypeProperty = new SimpleStringProperty();
    protected final ObjectProperty<Object> carer2PersonIdProperty = new SimpleObjectProperty<>();
    protected final StringProperty carer2NameProperty = new SimpleStringProperty("");
    protected final StringProperty carer2BookingRefProperty = new SimpleStringProperty("");

    // === POLICY ===
    protected final BooleanProperty policyAcceptedProperty = new SimpleBooleanProperty(false);

    // === VALIDATION ===
    protected final BooleanProperty validProperty = new SimpleBooleanProperty(false);

    // === UI COMPONENTS ===
    protected final VBox container = new VBox();
    protected HBox sectionHeader;
    protected HBox infoBox;
    protected Label requirementLabel;
    protected VBox carer1Container;
    protected VBox carer2Container;
    protected HBox policyCard;

    // Carer 1 card tracking
    protected final Map<Object, HBox> carer1CardMap = new HashMap<>();
    protected HBox carer1ExternalCard;
    protected VBox carer1ExternalFields;
    protected TextField carer1NameField;
    protected TextField carer1RefField;
    protected VBox carer1CardContainer;

    // Carer 2 card tracking
    protected final Map<Object, HBox> carer2CardMap = new HashMap<>();
    protected HBox carer2ExternalCard;
    protected VBox carer2ExternalFields;
    protected TextField carer2NameField;
    protected TextField carer2RefField;
    protected VBox carer2CardContainer;

    // === DATA ===
    protected WorkingBookingProperties workingBookingProperties;
    protected BookingSelectionState selectionState;
    protected Runnable onSelectionChanged;

    public DefaultChildCarerSection() {
        buildUI();
        setupBindings();
        updateValidity();
    }

    protected void buildUI() {
        container.setAlignment(Pos.TOP_LEFT);
        container.setSpacing(16);
        container.getStyleClass().add(bookingpage_childcarer_section);

        // Section header with users icon
        sectionHeader = new StyledSectionHeader(BookingPageI18nKeys.ChildCarerSelection, StyledSectionHeader.ICON_USERS);
        VBox.setMargin(sectionHeader, new Insets(0, 0, 8, 0));

        // Info box explaining carer requirements
        infoBox = BookingPageUIBuilder.createInfoBox(BookingPageI18nKeys.ChildCarerInfo, BookingPageUIBuilder.InfoBoxType.INFO);
        VBox.setMargin(infoBox, new Insets(0, 0, 8, 0));

        // Requirement label (shows how many carers needed)
        requirementLabel = new Label();
        requirementLabel.getStyleClass().addAll(bookingpage_text_base, bookingpage_font_medium, bookingpage_text_primary);
        requirementLabel.setWrapText(true);
        VBox.setMargin(requirementLabel, new Insets(0, 0, 16, 0));

        // Carer slots container - responsive using FlowPane
        FlowPane carerSlotsRow = new FlowPane();
        carerSlotsRow.setHgap(24);
        carerSlotsRow.setVgap(24);
        carerSlotsRow.setAlignment(Pos.TOP_LEFT);
        carerSlotsRow.setPrefWrapLength(600); // Wrap after ~2 columns on wider screens

        // Carer 1 container
        carer1Container = createCarerSlotContainer(1);

        // Carer 2 container (may be hidden based on age)
        carer2Container = createCarerSlotContainer(2);

        carerSlotsRow.getChildren().addAll(carer1Container, carer2Container);

        // Policy acceptance card
        policyCard = createPolicyCheckboxCard();
        VBox.setMargin(policyCard, new Insets(16, 0, 0, 0));

        container.getChildren().addAll(sectionHeader, infoBox, requirementLabel, carerSlotsRow, policyCard);

        // Initially hidden
        container.setVisible(false);
        container.setManaged(false);
    }

    /**
     * Creates a carer slot container with card-based selection.
     * Each container has min/max width for responsive FlowPane wrapping.
     */
    protected VBox createCarerSlotContainer(int carerNumber) {
        VBox slotContainer = new VBox(12);
        slotContainer.setMinWidth(280);
        slotContainer.setMaxWidth(400);
        slotContainer.setPrefWidth(350);

        // Slot label
        String labelKey = carerNumber == 1 ? "Carer1Label" : "Carer2Label";
        Label slotLabel = I18nControls.newLabel(labelKey);
        slotLabel.getStyleClass().addAll(bookingpage_font_semibold, bookingpage_text_primary);

        // Card container (vertical stack - full width cards)
        VBox cardContainer = new VBox(8);

        if (carerNumber == 1) {
            carer1CardContainer = cardContainer;
        } else {
            carer2CardContainer = cardContainer;
        }

        // External carer fields (shown when "Someone else" is selected)
        VBox externalFields = createExternalCarerFields(carerNumber);
        externalFields.setVisible(false);
        externalFields.setManaged(false);

        if (carerNumber == 1) {
            carer1ExternalFields = externalFields;
        } else {
            carer2ExternalFields = externalFields;
        }

        slotContainer.getChildren().addAll(slotLabel, cardContainer, externalFields);
        return slotContainer;
    }

    /**
     * Rebuilds the card list for a carer slot.
     * Shows the same household members as the member selection (adults only) + "Someone else".
     */
    protected void rebuildCarerCards(int carerNumber) {
        VBox cardContainer = carerNumber == 1 ? carer1CardContainer : carer2CardContainer;
        Map<Object, HBox> cardMap = carerNumber == 1 ? carer1CardMap : carer2CardMap;

        if (cardContainer == null) return;

        cardContainer.getChildren().clear();
        cardMap.clear();

        StringProperty typeProperty = carerNumber == 1 ? carer1TypeProperty : carer2TypeProperty;
        ObjectProperty<Object> personIdProperty = carerNumber == 1 ? carer1PersonIdProperty : carer2PersonIdProperty;

        // Household member cards (same as member selection - adults only)
        for (AccountMember member : accountMembers) {
            HBox memberCard = createCarerOptionCard(
                member.getName(),
                null,
                member.getPersonId(),
                carerNumber,
                typeProperty,
                personIdProperty,
                false
            );
            cardMap.put(member.getPersonId(), memberCard);
            cardContainer.getChildren().add(memberCard);
        }

        // "Someone else" card
        HBox externalCard = createCarerOptionCard(
            null,
            BookingPageI18nKeys.SomeoneElse,
            "external",
            carerNumber,
            typeProperty,
            personIdProperty,
            true
        );
        if (carerNumber == 1) {
            carer1ExternalCard = externalCard;
        } else {
            carer2ExternalCard = externalCard;
        }
        cardContainer.getChildren().add(externalCard);

        // Update card states based on current selection
        updateCarerCardStates(carerNumber);
    }

    /**
     * Creates a selectable card for a carer option.
     * Cards use full available width and CSS classes for styling.
     * Uses BookingPageUIBuilder.createRadioIndicator() for consistent radio button styling.
     */
    protected HBox createCarerOptionCard(String displayName, Object i18nKey, Object personId,
                                         int carerNumber, StringProperty typeProperty,
                                         ObjectProperty<Object> personIdProperty, boolean isExternal) {
        HBox card = new HBox(12);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(12, 16, 12, 16));
        card.setMaxWidth(Double.MAX_VALUE);
        card.setCursor(Cursor.HAND);
        card.getStyleClass().addAll(bookingpage_selectable, bookingpage_bg_white, bookingpage_border_card, bookingpage_rounded, bookingpage_childcarer_card);

        // Avatar/Icon
        StackPane avatar = createAvatar(displayName, isExternal);

        // Name label
        Label nameLabel;
        if (i18nKey != null) {
            nameLabel = I18nControls.newLabel(i18nKey);
        } else {
            nameLabel = new Label(displayName);
        }
        nameLabel.getStyleClass().addAll(bookingpage_font_medium, bookingpage_text_primary);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        // Selection indicator using the existing radio indicator from BookingPageUIBuilder
        BooleanProperty selectedProperty = new SimpleBooleanProperty(false);
        StackPane radioIndicator = BookingPageUIBuilder.createRadioIndicator(selectedProperty);

        card.getChildren().addAll(avatar, nameLabel, radioIndicator);

        // Store properties for later updates
        card.getProperties().put("selectedProperty", selectedProperty);
        card.getProperties().put("personId", personId);
        card.getProperties().put("isExternal", isExternal);

        // Click handler - writes directly to SelectionState when available
        card.setOnMouseClicked(e -> {
            if (card.getStyleClass().contains(disabled)) return;

            if (isExternal) {
                // External carer selected
                typeProperty.set("external");
                personIdProperty.set(null);
                if (selectionState != null) {
                    if (carerNumber == 1) {
                        selectionState.setChildCarer1Type("external");
                        selectionState.setChildCarer1PersonId(null);
                        selectionState.setChildCarer1DocumentId(null);
                    } else {
                        selectionState.setChildCarer2Type("external");
                        selectionState.setChildCarer2PersonId(null);
                        selectionState.setChildCarer2DocumentId(null);
                    }
                }
            } else {
                // Household carer selected
                typeProperty.set("household");
                personIdProperty.set(personId);
                AccountMember member = findMemberByPersonId(personId);
                if (selectionState != null) {
                    if (carerNumber == 1) {
                        selectionState.setChildCarer1Type("household");
                        selectionState.setChildCarer1PersonId(personId);
                        selectionState.setChildCarer1Name(member != null ? member.getName() : "");
                        selectionState.setChildCarer1DocumentId(member != null ? member.getDocumentId() : null);
                    } else {
                        selectionState.setChildCarer2Type("household");
                        selectionState.setChildCarer2PersonId(personId);
                        selectionState.setChildCarer2Name(member != null ? member.getName() : "");
                        selectionState.setChildCarer2DocumentId(member != null ? member.getDocumentId() : null);
                    }
                }
            }
            updateCarerCardStates(carerNumber);
            showExternalFields(carerNumber, isExternal);
            updateValidity();
            notifySelectionChanged();
        });

        return card;
    }

    // Stroke-based Feather/Lucide icon for "users" (group icon)
    private static final String ICON_USERS = "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75";

    /**
     * Creates an avatar circle with initials or icon.
     */
    protected StackPane createAvatar(String name, boolean isExternal) {
        StackPane avatar = new StackPane();
        avatar.setMinSize(36, 36);
        avatar.setMaxSize(36, 36);

        Circle circle = new Circle(18);
        circle.getStyleClass().add(isExternal ? bookingpage_childcarer_avatar_external : bookingpage_childcarer_avatar);

        if (isExternal) {
            // Group icon for "Someone else" - stroke-based like other icons in the project
            SVGPath icon = new SVGPath();
            icon.setContent(ICON_USERS);
            icon.getStyleClass().add(bookingpage_icon_on_primary);
            icon.setStrokeWidth(2);
            icon.setScaleX(0.6);
            icon.setScaleY(0.6);
            avatar.getChildren().addAll(circle, icon);
        } else {
            // Initials for household members
            String initials = getInitials(name);
            Label initialsLabel = new Label(initials);
            initialsLabel.getStyleClass().addAll(bookingpage_font_semibold, bookingpage_text_secondary);
            avatar.getChildren().addAll(circle, initialsLabel);
        }

        return avatar;
    }

    /**
     * Gets initials from a name.
     */
    protected String getInitials(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    /**
     * Updates card states based on current selection.
     * Uses BooleanProperty to update the radio indicator state from BookingPageUIBuilder.
     */
    protected void updateCarerCardStates(int carerNumber) {
        StringProperty typeProperty = carerNumber == 1 ? carer1TypeProperty : carer2TypeProperty;
        ObjectProperty<Object> personIdProperty = carerNumber == 1 ? carer1PersonIdProperty : carer2PersonIdProperty;
        Map<Object, HBox> cardMap = carerNumber == 1 ? carer1CardMap : carer2CardMap;
        HBox externalCard = carerNumber == 1 ? carer1ExternalCard : carer2ExternalCard;

        String currentType = typeProperty.get();
        Object currentPersonId = personIdProperty.get();

        // Get the other carer's selection to disable duplicate selection
        Object otherCarerPersonId = carerNumber == 1 ? carer2PersonIdProperty.get() : carer1PersonIdProperty.get();

        // Update household member cards
        for (Map.Entry<Object, HBox> entry : cardMap.entrySet()) {
            Object personId = entry.getKey();
            HBox card = entry.getValue();
            BooleanProperty selectedProperty = (BooleanProperty) card.getProperties().get("selectedProperty");

            boolean isSelected = "household".equals(currentType) && personId.equals(currentPersonId);
            boolean isDisabled = personId.equals(otherCarerPersonId);

            // Update CSS classes on card
            card.getStyleClass().removeAll(selected, disabled);

            if (isDisabled) {
                card.getStyleClass().add(disabled);
                card.setCursor(Cursor.DEFAULT);
                if (selectedProperty != null) selectedProperty.set(false);
            } else if (isSelected) {
                card.getStyleClass().add(selected);
                card.setCursor(Cursor.HAND);
                if (selectedProperty != null) selectedProperty.set(true);
            } else {
                card.setCursor(Cursor.HAND);
                if (selectedProperty != null) selectedProperty.set(false);
            }
        }

        // Update external card
        if (externalCard != null) {
            BooleanProperty selectedProperty = (BooleanProperty) externalCard.getProperties().get("selectedProperty");
            boolean isSelected = "external".equals(currentType);

            externalCard.getStyleClass().removeAll(selected, disabled);

            if (isSelected) {
                externalCard.getStyleClass().add(selected);
                if (selectedProperty != null) selectedProperty.set(true);
            } else {
                if (selectedProperty != null) selectedProperty.set(false);
            }
        }
    }

    /**
     * Shows or hides the external carer fields.
     */
    protected void showExternalFields(int carerNumber, boolean show) {
        VBox fields = carerNumber == 1 ? carer1ExternalFields : carer2ExternalFields;
        if (fields != null) {
            fields.setVisible(show);
            fields.setManaged(show);
        }

        // Clear external fields when hiding
        if (!show) {
            if (carerNumber == 1) {
                carer1NameProperty.set("");
                carer1BookingRefProperty.set("");
                if (carer1NameField != null) carer1NameField.setText("");
                if (carer1RefField != null) carer1RefField.setText("");
            } else {
                carer2NameProperty.set("");
                carer2BookingRefProperty.set("");
                if (carer2NameField != null) carer2NameField.setText("");
                if (carer2RefField != null) carer2RefField.setText("");
            }
        }
    }

    /**
     * Creates the external carer fields container using theme CSS class.
     */
    protected VBox createExternalCarerFields(int carerNumber) {
        VBox fieldsContainer = new VBox(12);
        fieldsContainer.setPadding(new Insets(16));
        fieldsContainer.getStyleClass().add(bookingpage_childcarer_external_fields);

        // Name field (required)
        VBox nameFieldContainer = new VBox(4);
        Label nameLabel = I18nControls.newLabel(BookingPageI18nKeys.CarerNamePlaceholder);
        nameLabel.getStyleClass().addAll(bookingpage_font_medium, bookingpage_text_primary);

        // Required asterisk
        Label asterisk = new Label(" *");
        asterisk.getStyleClass().add(bookingpage_text_danger);
        HBox nameLabelRow = new HBox(nameLabel, asterisk);
        nameLabelRow.setAlignment(Pos.CENTER_LEFT);

        TextField nameField = new TextField();
        I18n.bindI18nPromptProperty(nameField.promptTextProperty(), BookingPageI18nKeys.CarerNamePlaceholder);
        nameField.getStyleClass().add(bookingpage_text_input);
        nameField.setPadding(new Insets(12, 14, 12, 14));
        nameField.setMaxWidth(Double.MAX_VALUE);

        nameFieldContainer.getChildren().addAll(nameLabelRow, nameField);

        // Booking reference field (optional)
        VBox refFieldContainer = new VBox(4);
        Label refLabel = I18nControls.newLabel(BookingPageI18nKeys.BookingReferencePlaceholder);
        refLabel.getStyleClass().addAll(bookingpage_font_medium, bookingpage_text_primary);

        TextField refField = new TextField();
        I18n.bindI18nPromptProperty(refField.promptTextProperty(), BookingPageI18nKeys.BookingReferencePlaceholder);
        refField.getStyleClass().add(bookingpage_text_input);
        refField.setPadding(new Insets(12, 14, 12, 14));
        refField.setMaxWidth(Double.MAX_VALUE);

        Label refHint = I18nControls.newLabel(BookingPageI18nKeys.BookingReferenceOptional);
        refHint.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_secondary);
        refHint.setWrapText(true);

        refFieldContainer.getChildren().addAll(refLabel, refField, refHint);

        fieldsContainer.getChildren().addAll(nameFieldContainer, refFieldContainer);

        // Store field references
        if (carerNumber == 1) {
            carer1NameField = nameField;
            carer1RefField = refField;
        } else {
            carer2NameField = nameField;
            carer2RefField = refField;
        }

        return fieldsContainer;
    }

    /**
     * Creates the policy acceptance checkbox card.
     */
    protected HBox createPolicyCheckboxCard() {
        HBox card = new HBox(12);
        card.setAlignment(Pos.TOP_LEFT);
        card.setPadding(new Insets(16));
        card.setCursor(Cursor.HAND);
        card.getStyleClass().addAll(bookingpage_selectable, bookingpage_bg_white, bookingpage_border_card, bookingpage_rounded);

        // Checkbox indicator
        Region checkbox = new Region();
        checkbox.setMinSize(22, 22);
        checkbox.setMaxSize(22, 22);
        checkbox.getStyleClass().add(bookingpage_childcarer_checkbox);

        // Policy text
        Label policyLabel = I18nControls.newLabel(BookingPageI18nKeys.ChildSafetyPolicy);
        policyLabel.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_primary);
        policyLabel.setWrapText(true);
        HBox.setHgrow(policyLabel, Priority.ALWAYS);

        card.getChildren().addAll(checkbox, policyLabel);

        // Click handler - writes directly to SelectionState when available
        card.setOnMouseClicked(e -> {
            boolean newValue = !policyAcceptedProperty.get();
            policyAcceptedProperty.set(newValue);
            if (selectionState != null) {
                selectionState.setChildPolicyAccepted(newValue);
            }
        });

        // Update styling when policy changes
        policyAcceptedProperty.addListener((obs, oldVal, newVal) -> {
            card.getStyleClass().remove(selected);
            checkbox.getStyleClass().remove(selected);
            if (newVal) {
                card.getStyleClass().add(selected);
                checkbox.getStyleClass().add(selected);
            }
        });

        return card;
    }

    protected void setupBindings() {
        // Visibility based on section visibility
        visibleProperty.addListener((obs, oldVal, newVal) -> {
            container.setVisible(newVal);
            container.setManaged(newVal);
            if (newVal) {
                rebuildCarerCards(1);
                rebuildCarerCards(2);
            }
            updateCarerSlotVisibility();
            updateValidity();
        });

        // Rebuild cards when household members change
        accountMembers.addListener((ListChangeListener<AccountMember>) change -> {
            if (visibleProperty.get()) {
                rebuildCarerCards(1);
                rebuildCarerCards(2);
            }
        });

        // Text field bindings for external carer fields
        setupExternalFieldBindings(1);
        setupExternalFieldBindings(2);

        // Cross-update card states when either carer selection changes
        carer1PersonIdProperty.addListener((obs, oldVal, newVal) -> {
            updateCarerCardStates(2);
        });

        carer2PersonIdProperty.addListener((obs, oldVal, newVal) -> {
            updateCarerCardStates(1);
        });

        // Policy acceptance
        policyAcceptedProperty.addListener((obs, oldVal, newVal) -> {
            updateValidity();
            notifySelectionChanged();
        });
    }

    /**
     * Sets up text field bindings for external carer fields.
     */
    protected void setupExternalFieldBindings(int carerNumber) {
        if (carerNumber == 1 && carer1NameField != null) {
            carer1NameField.textProperty().addListener((obs, oldVal, newVal) -> {
                carer1NameProperty.set(newVal);
                updateValidity();
                notifySelectionChanged();
            });
            carer1RefField.textProperty().addListener((obs, oldVal, newVal) -> {
                carer1BookingRefProperty.set(newVal);
                notifySelectionChanged();
            });
        } else if (carerNumber == 2 && carer2NameField != null) {
            carer2NameField.textProperty().addListener((obs, oldVal, newVal) -> {
                carer2NameProperty.set(newVal);
                updateValidity();
                notifySelectionChanged();
            });
            carer2RefField.textProperty().addListener((obs, oldVal, newVal) -> {
                carer2BookingRefProperty.set(newVal);
                notifySelectionChanged();
            });
        }
    }

    /**
     * Updates the visibility of carer slots based on child's age.
     */
    protected void updateCarerSlotVisibility() {
        int required = getRequiredCarerCount();
        carer2Container.setVisible(required >= 2);
        carer2Container.setManaged(required >= 2);

        // Update requirement label
        String childDisplayName = childName != null && !childName.isEmpty() ? childName : "the child";
        String carersText = required == 1 ? "1 carer" : "2 carers";
        requirementLabel.setText(I18n.getI18nText(BookingPageI18nKeys.ChildCarerRequirement, childDisplayName, carersText));
    }

    /**
     * Updates the validity based on current state.
     */
    protected void updateValidity() {
        if (!visibleProperty.get()) {
            validProperty.set(true);
            return;
        }

        int required = getRequiredCarerCount();
        boolean carer1Valid = hasCarer1();
        boolean carer2Valid = required < 2 || hasCarer2();
        boolean policyValid = policyAcceptedProperty.get();

        validProperty.set(carer1Valid && carer2Valid && policyValid);
    }

    protected void notifySelectionChanged() {
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    /**
     * Finds a AccountMember by personId.
     */
    protected AccountMember findMemberByPersonId(Object personId) {
        if (personId == null) return null;
        for (AccountMember member : accountMembers) {
            if (personId.equals(member.getPersonId())) {
                return member;
            }
        }
        return null;
    }

    // ========================================
    // BookingFormSection INTERFACE
    // ========================================

    @Override
    public Object getTitleI18nKey() {
        return BookingPageI18nKeys.ChildCarerSelection;
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
    // HasChildCarerSection INTERFACE
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
    public void setChildAge(int age) {
        this.childAge = age;
        updateCarerSlotVisibility();
        updateValidity();
    }

    @Override
    public int getChildAge() {
        return childAge;
    }

    @Override
    public void setChildName(String name) {
        this.childName = name;
        updateCarerSlotVisibility();
    }

    @Override
    public String getChildName() {
        return childName;
    }

    @Override
    public ObservableList<AccountMember> getAccountMembers() {
        return accountMembers;
    }

    @Override
    public void setAccountMembers(List<AccountMember> members) {
        accountMembers.clear();
        if (members != null) {
            accountMembers.addAll(members);
        }
    }

    @Override
    public void clearAccountMembers() {
        accountMembers.clear();
    }

    @Override
    public StringProperty carer1TypeProperty() {
        return carer1TypeProperty;
    }

    @Override
    public ObjectProperty<Object> carer1PersonIdProperty() {
        return carer1PersonIdProperty;
    }

    @Override
    public StringProperty carer1NameProperty() {
        return carer1NameProperty;
    }

    @Override
    public StringProperty carer1BookingRefProperty() {
        return carer1BookingRefProperty;
    }

    @Override
    public StringProperty carer2TypeProperty() {
        return carer2TypeProperty;
    }

    @Override
    public ObjectProperty<Object> carer2PersonIdProperty() {
        return carer2PersonIdProperty;
    }

    @Override
    public StringProperty carer2NameProperty() {
        return carer2NameProperty;
    }

    @Override
    public StringProperty carer2BookingRefProperty() {
        return carer2BookingRefProperty;
    }

    @Override
    public BooleanProperty policyAcceptedProperty() {
        return policyAcceptedProperty;
    }

    @Override
    public String getValidationMessage() {
        if (!visibleProperty.get()) {
            return null;
        }

        int required = getRequiredCarerCount();

        if (!hasCarer1()) {
            return I18n.getI18nText(BookingPageI18nKeys.CarerRequiredWarning, "Carer 1");
        }

        if (required >= 2 && !hasCarer2()) {
            return I18n.getI18nText(BookingPageI18nKeys.CarerRequiredWarning, "Carer 2");
        }

        if (!policyAcceptedProperty.get()) {
            return I18n.getI18nText(BookingPageI18nKeys.PolicyRequiredWarning);
        }

        return null;
    }

    @Override
    public void reset() {
        // Reset carer 1
        carer1TypeProperty.set(null);
        carer1PersonIdProperty.set(null);
        carer1NameProperty.set("");
        carer1BookingRefProperty.set("");
        if (carer1NameField != null) carer1NameField.setText("");
        if (carer1RefField != null) carer1RefField.setText("");
        showExternalFields(1, false);

        // Reset carer 2
        carer2TypeProperty.set(null);
        carer2PersonIdProperty.set(null);
        carer2NameProperty.set("");
        carer2BookingRefProperty.set("");
        if (carer2NameField != null) carer2NameField.setText("");
        if (carer2RefField != null) carer2RefField.setText("");
        showExternalFields(2, false);

        // Reset policy
        policyAcceptedProperty.set(false);

        // Rebuild cards to reset visual state
        if (visibleProperty.get()) {
            rebuildCarerCards(1);
            rebuildCarerCards(2);
        }

        updateValidity();
    }

    @Override
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }

    // ========================================
    // BookingSelectionState BINDING
    // ========================================

    /**
     * Binds this section to the centralized BookingSelectionState.
     * Overrides the default interface method to use focus-lost pattern for text fields
     * and to initialize UI from state when returning to the section.
     */
    @Override
    public void bindToSelectionState(BookingSelectionState state) {
        this.selectionState = state;
        if (state == null) return;

        // Set up focus-lost bindings for text fields (update state on focus lost, not every keystroke)
        setupFocusLostBinding(carer1NameField, () -> {
            if ("external".equals(state.getChildCarer1Type())) {
                state.setChildCarer1Name(carer1NameField.getText());
            }
        });
        setupFocusLostBinding(carer1RefField, () -> {
            if ("external".equals(state.getChildCarer1Type())) {
                state.setChildCarer1BookingRef(carer1RefField.getText());
            }
        });
        setupFocusLostBinding(carer2NameField, () -> {
            if ("external".equals(state.getChildCarer2Type())) {
                state.setChildCarer2Name(carer2NameField.getText());
            }
        });
        setupFocusLostBinding(carer2RefField, () -> {
            if ("external".equals(state.getChildCarer2Type())) {
                state.setChildCarer2BookingRef(carer2RefField.getText());
            }
        });

        // Listen to policy property to update validity
        state.childPolicyAcceptedProperty().addListener((obs, o, n) -> updateValidity());

        // Initialize UI from state (for returning to section)
        initializeFromState();
    }

    /**
     * Sets up focus-lost binding for a text field.
     * Updates SelectionState only when the field loses focus.
     */
    protected void setupFocusLostBinding(TextField field, Runnable updateCallback) {
        if (field != null) {
            field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused && selectionState != null) {
                    updateCallback.run();
                }
            });
        }
    }

    /**
     * Initializes the UI from the BookingSelectionState.
     * Called when binding to state to restore UI state when user returns to this section.
     */
    protected void initializeFromState() {
        if (selectionState == null) return;

        // Carer 1
        String type1 = selectionState.getChildCarer1Type();
        if (type1 != null && !type1.isEmpty()) {
            carer1TypeProperty.set(type1);
            carer1PersonIdProperty.set(selectionState.getChildCarer1PersonId());
            carer1NameProperty.set(selectionState.getChildCarer1Name());
            carer1BookingRefProperty.set(selectionState.getChildCarer1BookingRef());

            // Update UI
            if ("external".equals(type1)) {
                showExternalFields(1, true);
                if (carer1NameField != null) carer1NameField.setText(selectionState.getChildCarer1Name() != null ? selectionState.getChildCarer1Name() : "");
                if (carer1RefField != null) carer1RefField.setText(selectionState.getChildCarer1BookingRef() != null ? selectionState.getChildCarer1BookingRef() : "");
            }
            updateCarerCardStates(1);
        }

        // Carer 2
        String type2 = selectionState.getChildCarer2Type();
        if (type2 != null && !type2.isEmpty()) {
            carer2TypeProperty.set(type2);
            carer2PersonIdProperty.set(selectionState.getChildCarer2PersonId());
            carer2NameProperty.set(selectionState.getChildCarer2Name());
            carer2BookingRefProperty.set(selectionState.getChildCarer2BookingRef());

            // Update UI
            if ("external".equals(type2)) {
                showExternalFields(2, true);
                if (carer2NameField != null) carer2NameField.setText(selectionState.getChildCarer2Name() != null ? selectionState.getChildCarer2Name() : "");
                if (carer2RefField != null) carer2RefField.setText(selectionState.getChildCarer2BookingRef() != null ? selectionState.getChildCarer2BookingRef() : "");
            }
            updateCarerCardStates(2);
        }

        // Policy
        boolean policyAccepted = selectionState.isChildPolicyAccepted();
        policyAcceptedProperty.set(policyAccepted);

        updateValidity();
    }

    /**
     * Syncs any unsaved text field content to state.
     * Call this before navigating away (e.g., when user clicks Continue without losing focus).
     */
    public void syncToState() {
        if (selectionState == null) return;

        if (carer1NameField != null && "external".equals(selectionState.getChildCarer1Type())) {
            selectionState.setChildCarer1Name(carer1NameField.getText());
        }
        if (carer1RefField != null && "external".equals(selectionState.getChildCarer1Type())) {
            selectionState.setChildCarer1BookingRef(carer1RefField.getText());
        }
        if (carer2NameField != null && "external".equals(selectionState.getChildCarer2Type())) {
            selectionState.setChildCarer2Name(carer2NameField.getText());
        }
        if (carer2RefField != null && "external".equals(selectionState.getChildCarer2Type())) {
            selectionState.setChildCarer2BookingRef(carer2RefField.getText());
        }
    }
}

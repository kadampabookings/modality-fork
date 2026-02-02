package one.modality.booking.frontoffice.bookingpage.sections.member;

import dev.webfx.extras.i18n.I18n;
import dev.webfx.extras.i18n.controls.I18nControls;
import dev.webfx.extras.webtext.HtmlText;
import dev.webfx.kit.util.properties.FXProperties;
import dev.webfx.platform.uischeduler.UiScheduler;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import one.modality.base.shared.entities.Person;
import one.modality.booking.client.workingbooking.WorkingBooking;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.BookingPageI18nKeys;
import one.modality.booking.frontoffice.bookingpage.components.BookingPageUIBuilder;
import one.modality.booking.frontoffice.bookingpage.components.StyledSectionHeader;
import one.modality.booking.frontoffice.bookingpage.sections.childcarer.DefaultChildCarerSection;
import one.modality.booking.frontoffice.bookingpage.sections.childcarer.HasChildCarerSection.HouseholdMember;
import one.modality.booking.frontoffice.bookingpage.standard.BookingSelectionState;
import one.modality.booking.frontoffice.bookingpage.theme.BookingFormColorScheme;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors.*;

/**
 * Default implementation of the member selection section.
 * Displays household members and allows selection of who to book for.
 *
 * <p>Uses CSS for styling - colors come from CSS variables that can be
 * overridden by theme classes (e.g., .theme-wisdom-blue) on a parent container.</p>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-selectable-card} - member card container (generic selectable card)</li>
 *   <li>{@code .bookingpage-selectable-card.selected} - selected state</li>
 *   <li>{@code .bookingpage-selectable-card.disabled} - disabled state (already booked)</li>
 *   <li>{@code .booking-form-section-header} - section header</li>
 *   <li>{@code .booking-form-primary-btn} - primary button (via BookingPageUIBuilder)</li>
 *   <li>{@code .booking-form-back-btn} - back button (via BookingPageUIBuilder)</li>
 * </ul>
 *
 * @author Bruno Salmon
 */
public class DefaultMemberSelectionSection implements HasMemberSelectionSection {

    // === COLOR SCHEME ===
    // Kept for API compatibility - theming is now CSS-based
    protected final ObjectProperty<BookingFormColorScheme> colorScheme = new SimpleObjectProperty<>(BookingFormColorScheme.DEFAULT);

    // === VALIDITY ===
    protected final SimpleBooleanProperty validProperty = new SimpleBooleanProperty(false);

    // === SELECTED MEMBER ===
    protected final ObjectProperty<MemberInfo> selectedMemberProperty = new SimpleObjectProperty<>();

    // === MEMBERS LIST ===
    protected final ObservableList<MemberInfo> householdMembers = FXCollections.observableArrayList();
    protected final Set<Object> alreadyBookedPersonIds = new HashSet<>();
    protected final BooleanProperty hasAvailableMembers = new SimpleBooleanProperty(false);

    // === UI COMPONENTS ===
    protected final VBox container = new VBox();
    protected FlowPane memberGrid;
    protected HBox infoBox;
    protected VBox errorBox;
    protected Label errorLabel;
    protected Button backButton;
    protected Button continueButton;
    protected final Map<MemberInfo, VBox> memberCardMap = new HashMap<>();
    protected final Map<MemberInfo, StackPane> checkmarkBadgeMap = new HashMap<>();

    // === CALLBACKS ===
    protected Consumer<MemberInfo> onMemberSelected;
    protected Runnable onContinuePressed;
    protected Runnable onBackPressed;

    // === DATA ===
    protected WorkingBookingProperties workingBookingProperties;

    // === INLINE CHILD CARER ===
    protected boolean inlineChildCarerEnabled = false;
    protected VBox childCarerContainer;
    protected DefaultChildCarerSection childCarerSection;
    protected BookingSelectionState selectionState;

    public DefaultMemberSelectionSection() {
        buildUI();
        setupBindings();
    }

    protected void buildUI() {
        container.setAlignment(Pos.TOP_CENTER);
        container.setSpacing(0);
        container.getStyleClass().add("bookingpage-member-selection-section");

        // Title - styled via CSS
        Label title = createPageTitle();

        // Subtitle - styled via CSS
        Label subtitle = createPageSubtitle();

        // Section header - styled via CSS
        HBox sectionHeader = buildSectionHeader();

        // Info box with explanatory text - styled via CSS
        infoBox = buildInfoBox();

        // Member grid
        memberGrid = new FlowPane();
        memberGrid.setHgap(16);
        memberGrid.setVgap(16);
        memberGrid.setPrefWrapLength(600);

        // Error box (hidden by default) - styled via CSS
        errorBox = buildErrorBox();

        // Navigation button row - styled via CSS
        HBox buttonRow = buildButtonRow();

        container.getChildren().addAll(title, subtitle, sectionHeader, infoBox, memberGrid, errorBox, buttonRow);
        VBox.setMargin(subtitle, new Insets(0, 0, 40, 0));
        VBox.setMargin(sectionHeader, new Insets(0, 0, 16, 0));
        VBox.setMargin(infoBox, new Insets(0, 0, 24, 0));
        VBox.setMargin(memberGrid, new Insets(0, 0, 32, 0));
        VBox.setMargin(buttonRow, new Insets(40, 0, 0, 0));
    }

    protected HBox buildButtonRow() {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);

        // Back button - styled via CSS (stored as field for visibility control)
        backButton = buildBackButton();

        // Spacer
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Continue button - styled via CSS
        continueButton = buildContinueButton();

        row.getChildren().addAll(backButton, spacer, continueButton);
        return row;
    }

    /**
     * Sets the visibility of the back button.
     * Use this to hide the back button when Member Selection is the first step.
     *
     * @param visible true to show, false to hide
     */
    public void setBackButtonVisible(boolean visible) {
        if (backButton != null) {
            backButton.setVisible(visible);
            backButton.setManaged(visible);
        }
    }

    protected Button buildBackButton() {
        Button button = createBackButton();
        button.setOnAction(e -> {
            if (onBackPressed != null) {
                onBackPressed.run();
            }
        });
        return button;
    }

    protected Button buildContinueButton() {
        Button button = createPrimaryButton();
        button.setDisable(true);
        button.setOnAction(e -> {
            MemberInfo selected = selectedMemberProperty.get();
            if (selected != null && onContinuePressed != null) {
                if (onMemberSelected != null) {
                    onMemberSelected.accept(selected);
                }
                onContinuePressed.run();
            }
        });
        return button;
    }

    protected void setupBindings() {
        // Update validity and button state when member is selected
        selectedMemberProperty.addListener((obs, oldMember, newMember) -> {
            // Update card styles via CSS classes and checkmark visibility
            if (oldMember != null) {
                VBox oldCard = memberCardMap.get(oldMember);
                if (oldCard != null) {
                    oldCard.getStyleClass().remove(selected);
                }
                StackPane oldCheckmark = checkmarkBadgeMap.get(oldMember);
                if (oldCheckmark != null) {
                    oldCheckmark.setVisible(false);
                }
            }
            if (newMember != null) {
                VBox newCard = memberCardMap.get(newMember);
                if (newCard != null) {
                    newCard.getStyleClass().add(selected);
                }
                StackPane newCheckmark = checkmarkBadgeMap.get(newMember);
                if (newCheckmark != null) {
                    newCheckmark.setVisible(true);
                }
            }

            // Handle inline child carer if enabled
            if (inlineChildCarerEnabled && childCarerContainer != null) {
                updateChildCarerVisibility(newMember);
            }

            // Update validity (includes child carer validity if applicable)
            updateValidity();
        });

        // Rebuild cards when list changes (ensure UI thread for async member loading)
        householdMembers.addListener((ListChangeListener<MemberInfo>) change ->
            UiScheduler.runInUiThread(this::rebuildMemberCards));
    }

    protected HBox buildSectionHeader() {
        return new StyledSectionHeader(BookingPageI18nKeys.PeopleOnYourAccount, StyledSectionHeader.ICON_USERS);
    }

    protected VBox buildErrorBox() {
        VBox box = new VBox();
        box.setVisible(false);
        box.setManaged(false);

        HBox errorRow = new HBox(8);
        errorRow.setAlignment(Pos.CENTER_LEFT);
        errorRow.setPadding(new Insets(12, 16, 12, 16));
        errorRow.getStyleClass().add(bookingpage_error_box);

        Label warningIcon = new Label("⚠"); // ⚠ warning
        warningIcon.getStyleClass().add(bookingpage_text_base);

        errorLabel = new Label();
        errorLabel.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_danger);
        errorLabel.setWrapText(true);

        errorRow.getChildren().addAll(warningIcon, errorLabel);
        box.getChildren().add(errorRow);
        VBox.setMargin(box, new Insets(0, 0, 24, 0));

        return box;
    }

    /**
     * Builds the info box with explanatory text about adding members.
     * Uses HtmlText to support clickable links in the text.
     * Styled via CSS classes.
     */
    protected HBox buildInfoBox() {
        HBox box = new HBox(10);
        box.setAlignment(Pos.TOP_LEFT);
        box.setPadding(new Insets(12, 16, 12, 16));
        box.getStyleClass().add(bookingpage_warning_box);

        // Warning/info icon
        SVGPath icon = new SVGPath();
        icon.setContent("M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z");
        icon.setStroke(Color.web("#D97706"));
        icon.setStrokeWidth(2);
        icon.setFill(Color.TRANSPARENT);
        icon.setScaleX(0.8);
        icon.setScaleY(0.8);

        // Info text with HTML link support
        HtmlText infoText = new HtmlText();
        infoText.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_warning);
        // Update text when language changes
        FXProperties.runNowAndOnPropertiesChange(() -> {
            infoText.setText(I18n.getI18nText(BookingPageI18nKeys.MemberSelectionInfoText));
        }, I18n.dictionaryProperty());
        HBox.setHgrow(infoText, Priority.ALWAYS);

        box.getChildren().addAll(icon, infoText);
        return box;
    }

    protected void rebuildMemberCards() {
        memberGrid.getChildren().clear();
        memberCardMap.clear();
        checkmarkBadgeMap.clear();

        MemberInfo currentlySelected = selectedMemberProperty.get();
        Object selectedPersonId = currentlySelected != null ? currentlySelected.getPersonId() : null;

        MemberInfo matchingMember = null;

        for (MemberInfo member : householdMembers) {
            boolean isSelected = selectedPersonId != null && selectedPersonId.equals(member.getPersonId());
            VBox card = createMemberCard(member, isSelected);
            memberGrid.getChildren().add(card);
            memberCardMap.put(member, card);

            if (isSelected) {
                matchingMember = member;
            }
        }

        if (matchingMember != null && currentlySelected != matchingMember) {
            selectedMemberProperty.set(matchingMember);
        }
    }

    protected VBox createMemberCard(MemberInfo member, boolean isSelected) {
        boolean isBooked = isAlreadyBooked(member);
        boolean isBookable = member.isBookable() && !isBooked;
        MemberStatus status = member.getStatus();

        VBox card = new VBox(0);
        card.setMinWidth(280);
        card.setMaxWidth(350);
        card.setPadding(new Insets(20));
        card.setCursor(isBookable ? Cursor.HAND : Cursor.DEFAULT);
        card.getStyleClass().add(bookingpage_selectable_card); // Use selectable card class for proper theming

        // Apply state classes
        if (!isBookable) {
            card.getStyleClass().add(disabled);
        }
        if (isSelected && isBookable) {
            card.getStyleClass().add(selected);
        }

        // Content container (for StackPane wrapper with checkmark)
        VBox contentBox = new VBox(0);

        // Name - styled via CSS
        Label nameLabel = new Label(member.getName());
        nameLabel.getStyleClass().addAll(bookingpage_text_lg, bookingpage_font_semibold, bookingpage_text_dark);
        VBox.setMargin(nameLabel, new Insets(0, 0, 8, 0));

        // Email - styled via CSS
        Label emailLabel = new Label(member.getEmail());
        emailLabel.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_muted);

        contentBox.getChildren().addAll(nameLabel, emailLabel);

        // Child age badge (for members under 18)
        Person personEntity = member.getPersonEntity();
        if (personEntity != null && personEntity.getBirthDate() != null) {
            int age = calculateAge(personEntity.getBirthDate());
            if (age >= 0 && age < 18) {
                HBox childBadge = createChildAgeBadge(age);
                VBox.setMargin(childBadge, new Insets(8, 0, 0, 0));
                contentBox.getChildren().add(childBadge);
            }
        }

        // Status indicators
        if (isBooked) {
            VBox.setMargin(emailLabel, new Insets(0, 0, 8, 0));
            contentBox.getChildren().add(createStatusBadge("✗", BookingPageI18nKeys.AlreadyBookedForEvent, "#dc3545", "#dc3545")); // ✗
            card.setOpacity(0.6);
            card.getStyleClass().add("already-booked");
        } else if (status == MemberStatus.PENDING_INVITATION) {
            VBox.setMargin(emailLabel, new Insets(0, 0, 8, 0));
            contentBox.getChildren().add(createStatusBadge("⏳", BookingPageI18nKeys.Pending, "#6c757d", "#6c757d")); // ⏳
            card.setOpacity(0.7);
        } else if (status == MemberStatus.NEEDS_VALIDATION) {
            VBox.setMargin(emailLabel, new Insets(0, 0, 8, 0));
            contentBox.getChildren().add(createStatusBadge("⚠", BookingPageI18nKeys.NeedsValidation, "#dc3545", "#dc3545")); // ⚠
            card.setOpacity(0.7);
        }

        // Wrap content with checkmark badge for bookable members
        if (isBookable) {
            // Create checkmark badge (CSS-themed)
            StackPane checkmarkBadge = BookingPageUIBuilder.createCheckmarkBadgeCss(24);
            checkmarkBadge.setVisible(isSelected);
            checkmarkBadgeMap.put(member, checkmarkBadge); // Store reference for selection updates

            // StackPane wrapper to position checkmark in top-right corner
            StackPane wrapper = new StackPane(contentBox, checkmarkBadge);
            StackPane.setAlignment(checkmarkBadge, Pos.TOP_RIGHT);

            card.getChildren().add(wrapper);
        } else {
            card.getChildren().add(contentBox);
        }

        // Click effects (only if bookable)
        if (isBookable) {
            card.setOnMouseClicked(e -> handleMemberClick(member));
        }

        return card;
    }

    protected HBox createStatusBadge(String icon, Object textKey, String iconColor, String textColor) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(row, new Insets(8, 0, 0, 0));

        Label iconLabel = new Label(icon);
        iconLabel.setTextFill(Color.web(iconColor));
        iconLabel.getStyleClass().add(bookingpage_text_xs);

        Label textLabel = I18nControls.newLabel(textKey);
        textLabel.setTextFill(Color.web(textColor));
        textLabel.getStyleClass().addAll(bookingpage_font_medium, bookingpage_text_xs);
        textLabel.setWrapText(true);

        row.getChildren().addAll(iconLabel, textLabel);
        return row;
    }

    /**
     * Calculates age from birth date.
     */
    protected int calculateAge(LocalDate birthDate) {
        if (birthDate == null) return -1;
        LocalDate now = LocalDate.now();
        int age = now.getYear() - birthDate.getYear();
        if (now.getDayOfYear() < birthDate.getDayOfYear()) {
            age--;
        }
        return age;
    }

    /**
     * Creates a child age badge with amber styling.
     * Matches JSX: backgroundColor: #FEF3C7, color: #92400E
     */
    protected HBox createChildAgeBadge(int age) {
        HBox badge = new HBox();
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(4, 12, 4, 12));
        badge.setStyle("-fx-background-color: #FEF3C7; -fx-background-radius: 20;");

        Label label = I18nControls.newLabel(BookingPageI18nKeys.ChildAge, age);
        label.setStyle("-fx-text-fill: #92400E; -fx-font-size: 12px; -fx-font-weight: 500;");

        badge.getChildren().add(label);
        return badge;
    }

    protected void handleMemberClick(MemberInfo member) {
        if (isAlreadyBooked(member)) {
            showError();
            return;
        }

        clearError();
        selectedMemberProperty.set(member);
    }

    protected boolean isAlreadyBooked(MemberInfo member) {
        Object memberId = member.getPersonId();
        return alreadyBookedPersonIds.contains(memberId);
    }

    protected void showError() {
        I18nControls.bindI18nTextProperty(errorLabel, BookingPageI18nKeys.PersonAlreadyBooked);
        errorBox.setVisible(true);
        errorBox.setManaged(true);
    }

    protected void clearError() {
        errorLabel.setText("");
        errorBox.setVisible(false);
        errorBox.setManaged(false);
    }

    // ========================================
    // BUTTON HELPERS
    // ========================================

    protected Button createBackButton() {
        return BookingPageUIBuilder.createBackButton(BookingPageI18nKeys.Back);
    }

    protected Button createPrimaryButton() {
        return BookingPageUIBuilder.createPrimaryButton(BookingPageI18nKeys.Continue, colorScheme);
    }

    // ========================================
    // STYLING HELPERS
    // ========================================

    protected Label createPageTitle() {
        Label label = I18nControls.newLabel(BookingPageI18nKeys.WhoIsThisBookingFor);
        label.getStyleClass().addAll(bookingpage_text_2xl, bookingpage_font_bold, bookingpage_text_dark);
        label.setWrapText(true);
        label.setAlignment(Pos.CENTER);
        label.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(label, new Insets(0, 0, 10, 0));
        return label;
    }

    protected Label createPageSubtitle() {
        Label label = I18nControls.newLabel(BookingPageI18nKeys.SelectPersonOrAddNew);
        label.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_muted);
        label.setWrapText(true);
        label.setAlignment(Pos.CENTER);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    // ========================================
    // BookingFormSection INTERFACE
    // ========================================

    @Override
    public Object getTitleI18nKey() {
        return BookingPageI18nKeys.WhoIsThisBookingFor;
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

    /**
     * This section is only applicable for new bookings where user needs to select a household member.
     * For existing bookings, the member selection is handled in the ExistingBookingSection.
     */
    @Override
    public boolean isApplicableToBooking(WorkingBooking workingBooking) {
        // Skip for existing bookings - member already selected
        if (workingBooking != null && !workingBooking.isNewBooking()) {
            return false;
        }
        // Skip if member was explicitly selected (e.g., via ExistingBookingSection)
        // Note: We check the flag on WorkingBooking (not workingBookingProperties) because
        // isApplicableToBooking() is called before setWorkingBookingProperties() during navigation
        if (workingBooking != null && workingBooking.isMemberExplicitlySelected()) {
            return false;
        }
        // Show for new bookings where user needs to choose who to book for
        return true;
    }

    // ========================================
    // HasMemberSelectionSection INTERFACE
    // ========================================

    /**
     * @deprecated Color scheme is now handled via CSS classes on parent container.
     * Use theme classes like "theme-wisdom-blue" on a parent element instead.
     */
    @Deprecated
    @Override
    public ObjectProperty<BookingFormColorScheme> colorSchemeProperty() {
        return colorScheme;
    }

    /**
     * @deprecated Use CSS theme classes instead.
     */
    @Deprecated
    @Override
    public void setColorScheme(BookingFormColorScheme scheme) {
        this.colorScheme.set(scheme);
    }

    @Override
    public void setOnMemberSelected(Consumer<MemberInfo> callback) {
        this.onMemberSelected = callback;
    }

    @Override
    public void setOnBackPressed(Runnable callback) {
        this.onBackPressed = callback;
    }

    @Override
    public void setOnContinuePressed(Runnable callback) {
        this.onContinuePressed = callback;
    }

    @Override
    public void addMember(MemberInfo member) {
        householdMembers.add(member);
        updateHasAvailableMembers();
    }

    @Override
    public void clearMembers() {
        householdMembers.clear();
        updateHasAvailableMembers();
    }

    @Override
    public void clearSelection() {
        selectedMemberProperty.set(null);
        validProperty.set(false);
        rebuildMemberCards();
    }

    @Override
    public void setAlreadyBookedPersonIds(Set<Object> personIds) {
        alreadyBookedPersonIds.clear();
        if (personIds != null) {
            alreadyBookedPersonIds.addAll(personIds);
        }
        rebuildMemberCards();
        updateHasAvailableMembers();
    }

    @Override
    public void clearAlreadyBooked() {
        alreadyBookedPersonIds.clear();
        rebuildMemberCards();
        updateHasAvailableMembers();
    }

    @Override
    public ObservableBooleanValue hasAvailableMembersProperty() {
        return hasAvailableMembers;
    }

    /**
     * Updates the hasAvailableMembers property based on current members and bookings.
     */
    protected void updateHasAvailableMembers() {
        long availableCount = householdMembers.stream()
            .filter(m -> !isAlreadyBooked(m))
            .count();
        hasAvailableMembers.set(availableCount > 0);
    }

    // ========================================
    // INLINE CHILD CARER SUPPORT
    // ========================================

    /**
     * Enables inline child carer selection for this member selection section.
     * When enabled, selecting a child (under 18) will show the child carer
     * selection form directly below the member cards.
     *
     * <p>Call {@link #bindToSelectionState(BookingSelectionState)} before or after
     * calling this to ensure child carer data flows to the centralized state.</p>
     *
     * @param enabled true to enable inline child carer, false to disable
     */
    public void setInlineChildCarerEnabled(boolean enabled) {
        this.inlineChildCarerEnabled = enabled;
        if (enabled && childCarerContainer == null) {
            buildChildCarerSection();
        }
    }

    /**
     * Returns whether inline child carer selection is enabled.
     */
    public boolean isInlineChildCarerEnabled() {
        return inlineChildCarerEnabled;
    }

    /**
     * Binds this section to the centralized BookingSelectionState.
     * Also binds the inline child carer section if enabled.
     *
     * @param selectionState the centralized state to bind to
     */
    public void bindToSelectionState(BookingSelectionState selectionState) {
        this.selectionState = selectionState;

        // If child carer section exists, bind it to the same selection state
        if (childCarerSection != null && selectionState != null) {
            childCarerSection.bindToSelectionState(selectionState);
        }
    }

    /**
     * Returns the inline child carer section if enabled, null otherwise.
     */
    public DefaultChildCarerSection getChildCarerSection() {
        return childCarerSection;
    }

    /**
     * Builds the child carer section and adds it to the container.
     */
    protected void buildChildCarerSection() {
        if (childCarerContainer != null) return; // Already built

        childCarerContainer = new VBox(16);
        childCarerContainer.setVisible(false);
        childCarerContainer.setManaged(false);
        childCarerContainer.getStyleClass().add("bookingpage-inline-childcarer");
        VBox.setMargin(childCarerContainer, new Insets(24, 0, 0, 0));

        childCarerSection = new DefaultChildCarerSection();
        childCarerSection.setColorScheme(colorScheme.get());
        childCarerContainer.getChildren().add(childCarerSection.getView());

        // Bind to selection state for centralized data management
        if (selectionState != null) {
            childCarerSection.bindToSelectionState(selectionState);
        }

        // Insert after member grid, before error box
        int insertIndex = container.getChildren().indexOf(errorBox);
        if (insertIndex >= 0) {
            container.getChildren().add(insertIndex, childCarerContainer);
        } else {
            // Fallback: add before button row
            int buttonRowIndex = container.getChildren().size() - 1;
            container.getChildren().add(buttonRowIndex, childCarerContainer);
        }

        // Listen to child carer validity to update overall validity
        childCarerSection.validProperty().addListener((obs, oldVal, newVal) -> updateValidity());
    }

    /**
     * Updates child carer section visibility based on selected member.
     */
    protected void updateChildCarerVisibility(MemberInfo member) {
        if (childCarerContainer == null) return;

        if (member == null) {
            childCarerContainer.setVisible(false);
            childCarerContainer.setManaged(false);
            // Reset child carer data in selection state
            if (selectionState != null) {
                selectionState.resetChildCarerInfo();
            }
            return;
        }

        Person personEntity = member.getPersonEntity();
        boolean isChild = false;
        int age = 0;
        if (personEntity != null && personEntity.getBirthDate() != null) {
            age = calculateAge(personEntity.getBirthDate());
            isChild = age >= 0 && age < 18;
        }

        childCarerContainer.setVisible(isChild);
        childCarerContainer.setManaged(isChild);

        if (isChild) {
            childCarerSection.setChildAge(age);
            childCarerSection.setChildName(member.getName());
            // Pass adult household members (excluding the selected child) as potential carers
            childCarerSection.setHouseholdMembers(getAdultHouseholdMembers(member));
            childCarerSection.setVisible(true);
        } else {
            // Not a child - reset carer data in selection state
            childCarerSection.reset();
            childCarerSection.setVisible(false);
            if (selectionState != null) {
                selectionState.resetChildCarerInfo();
            }
        }
    }

    /**
     * Converts adult household members to HouseholdMember objects for the child carer section.
     * Excludes the selected child and members under 18.
     *
     * @param selectedChild The currently selected child member (to exclude from the list)
     * @return List of adult household members as HouseholdMember objects
     */
    protected List<HouseholdMember> getAdultHouseholdMembers(MemberInfo selectedChild) {
        List<HouseholdMember> adults = new ArrayList<>();
        Object selectedChildId = selectedChild != null ? selectedChild.getPersonId() : null;

        for (MemberInfo member : householdMembers) {
            // Skip the selected child
            if (selectedChildId != null && selectedChildId.equals(member.getPersonId())) {
                continue;
            }

            // Skip non-bookable members (pending, needs validation)
            if (!member.isBookable()) {
                continue;
            }

            // Check if member is an adult (18+)
            Person personEntity = member.getPersonEntity();
            if (personEntity != null && personEntity.getBirthDate() != null) {
                int age = calculateAge(personEntity.getBirthDate());
                if (age >= 0 && age < 18) {
                    continue; // Skip children
                }
            }

            // Convert MemberInfo to HouseholdMember
            // isSelf = true if this is the account owner (OWNER status)
            boolean isSelf = member.getStatus() == MemberStatus.OWNER;
            adults.add(new HouseholdMember(
                member.getPersonId(),
                member.getName(),
                isSelf,
                true // isAdult - we've already filtered adults
            ));
        }

        return adults;
    }

    /**
     * Updates the validity property considering inline child carer if enabled.
     */
    protected void updateValidity() {
        MemberInfo selected = selectedMemberProperty.get();
        boolean memberSelected = selected != null && !isAlreadyBooked(selected);

        // If inline child carer is enabled and visible, require its validity
        boolean childCarerValid = true;
        if (inlineChildCarerEnabled && childCarerContainer != null && childCarerContainer.isVisible()) {
            childCarerValid = childCarerSection != null && childCarerSection.validProperty().get();
        }

        boolean isValid = memberSelected && childCarerValid;
        validProperty.set(isValid);
        continueButton.setDisable(!isValid);
    }

}

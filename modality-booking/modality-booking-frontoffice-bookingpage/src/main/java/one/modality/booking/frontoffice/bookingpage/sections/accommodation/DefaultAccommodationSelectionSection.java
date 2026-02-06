package one.modality.booking.frontoffice.bookingpage.sections.accommodation;

import dev.webfx.extras.i18n.I18n;
import dev.webfx.extras.i18n.controls.I18nControls;
import dev.webfx.extras.responsive.ResponsiveDesign;
import dev.webfx.platform.uischeduler.UiScheduler;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import one.modality.base.shared.entities.Item;
import one.modality.base.shared.entities.ItemPolicy;
import one.modality.base.shared.entities.Rate;
import one.modality.base.shared.entities.ScheduledItem;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.BookingPageI18nKeys;
import one.modality.booking.frontoffice.bookingpage.components.BookingPageUIBuilder;
import one.modality.booking.frontoffice.bookingpage.components.StyledSectionHeader;
import one.modality.base.shared.entities.formatters.EventPriceFormatter;
import one.modality.ecommerce.policy.service.PolicyAggregate;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors.*;

/**
 * Default implementation of the accommodation selection section.
 * Displays accommodation/room options and allows selection.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Selectable cards for each accommodation option</li>
 *   <li>Shows total price (teaching + accommodation for full festival)</li>
 *   <li>"Sold Out" corner ribbon for unavailable rooms</li>
 *   <li>Constraint badges ("Full Festival Only", "Minimum 3 nights")</li>
 *   <li>"LIMITED" badge for low availability</li>
 *   <li>Selection checkmark animation</li>
 * </ul>
 *
 * <p>CSS classes used:</p>
 * <ul>
 *   <li>{@code .bookingpage-accommodation-section} - section container</li>
 *   <li>{@code .bookingpage-accommodation-card} - accommodation card</li>
 *   <li>{@code .bookingpage-accommodation-card.selected} - selected state</li>
 *   <li>{@code .bookingpage-accommodation-card.soldout} - sold out state</li>
 *   <li>{@code .bookingpage-badge-constraint} - constraint badge</li>
 *   <li>{@code .bookingpage-badge-limited} - limited availability badge</li>
 *   <li>{@code .bookingpage-ribbon-soldout} - sold out ribbon</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see HasAccommodationSelectionSection
 */
public class DefaultAccommodationSelectionSection implements HasAccommodationSelectionSection {

    // === VALIDITY ===
    protected final SimpleBooleanProperty validProperty = new SimpleBooleanProperty(false);

    // === SELECTED OPTION ===
    protected final ObjectProperty<AccommodationOption> selectedOptionProperty = new SimpleObjectProperty<>();

    // === ATTENDANCE TYPE (Onsite vs Day Visitor) ===
    protected final ObjectProperty<AttendanceType> attendanceTypeProperty = new SimpleObjectProperty<>();

    // === OPTIONS LIST ===
    protected final ObservableList<AccommodationOption> accommodationOptions = FXCollections.observableArrayList();

    // Pricing is always pre-calculated via WorkingBooking (see AccommodationOption.preCalculatedTotalPrice)

    // === UI COMPONENTS ===
    protected final VBox container = new VBox();
    protected FlowPane attendanceTypeContainer;  // Onsite vs Day Visitor cards
    protected VBox roomOptionsWrapper;  // Gray background wrapper for room options
    protected VBox optionsContainer;  // Changed from FlowPane to VBox for full-width cards
    protected final Map<AccommodationOption, VBox> optionCardMap = new HashMap<>();
    protected final Map<AccommodationOption, StackPane> checkmarkBadgeMap = new HashMap<>();
    // Attendance type card references for selection updates
    protected VBox onsiteCard;
    protected VBox dayVisitorCard;
    protected StackPane onsiteCheckmark;
    protected StackPane dayVisitorCheckmark;
    protected StackPane onsiteIconCircle;
    protected StackPane dayVisitorIconCircle;
    protected SVGPath onsiteIcon;
    protected SVGPath dayVisitorIcon;

    // === PRICE BREAKDOWN SUPPORT ===
    /** Map from itemId to breakdown items for displaying price details. */
    protected final Map<Object, List<PriceBreakdownItem>> breakdownMap = new HashMap<>();
    /** Set to true to display price breakdown on accommodation cards (for debugging). */
    protected static final boolean DEBUG_SHOW_PRICE_BREAKDOWN = false;

    // === RESPONSIVE DESIGN ===
    protected ResponsiveDesign responsiveDesign;
    protected boolean isMobileLayout = false;
    // Track header components for responsive layout switching
    protected final java.util.List<CardHeaderComponents> cardHeaderList = new java.util.ArrayList<>();

    /** Holds references to card header components for responsive layout switching */
    protected static class CardHeaderComponents {
        final VBox headerContainer;  // Container that holds either HBox or VBox layout
        final Label nameLabel;
        final VBox priceContainer;
        final StackPane checkmarkBadge;
        final boolean isAvailable;

        CardHeaderComponents(VBox headerContainer, Label nameLabel, VBox priceContainer, StackPane checkmarkBadge, boolean isAvailable) {
            this.headerContainer = headerContainer;
            this.nameLabel = nameLabel;
            this.priceContainer = priceContainer;
            this.checkmarkBadge = checkmarkBadge;
            this.isAvailable = isAvailable;
        }
    }

    /**
     * Represents a single line item in a price breakdown.
     * Used to show detailed pricing (teachings, accommodation, meals) on accommodation cards.
     */
    public static class PriceBreakdownItem {
        private final String category;     // "Teachings", "Accommodation", "Meals", etc.
        private final String dateRange;    // "Jul 1-8" or "8 nights"
        private final int price;           // Price in cents
        private final Integer remainingQuantity; // Remaining availability (null if not applicable)

        public PriceBreakdownItem(String category, String dateRange, int price) {
            this(category, dateRange, price, null);
        }

        public PriceBreakdownItem(String category, String dateRange, int price, Integer remainingQuantity) {
            this.category = category;
            this.dateRange = dateRange;
            this.price = price;
            this.remainingQuantity = remainingQuantity;
        }

        public String getCategory() { return category; }
        public String getDateRange() { return dateRange; }
        public int getPrice() { return price; }
        public Integer getRemainingQuantity() { return remainingQuantity; }
    }

    // === CALLBACKS ===
    protected Consumer<AccommodationOption> onOptionSelected;
    protected Runnable onContinuePressed;
    protected Runnable onBackPressed;

    // === DATA ===
    protected WorkingBookingProperties workingBookingProperties;

    public DefaultAccommodationSelectionSection() {
        buildUI();
        setupBindings();
    }

    protected void buildUI() {
        container.setAlignment(Pos.TOP_CENTER);
        container.setSpacing(0);
        container.getStyleClass().add(bookingpage_accommodation_section);

        // Attendance type selection (Onsite vs Day Visitor cards)
        attendanceTypeContainer = createAttendanceTypeCards();
        VBox.setMargin(attendanceTypeContainer, new Insets(0, 0, 32, 0));

        // Room options container (only shown when Onsite selected)
        roomOptionsWrapper = new VBox(20);
        roomOptionsWrapper.getStyleClass().add(bookingpage_room_options_container);
        roomOptionsWrapper.setPadding(new Insets(24));

        Label selectAccomLabel = I18nControls.newLabel(BookingPageI18nKeys.SelectYourAccommodation);
        selectAccomLabel.getStyleClass().addAll(bookingpage_text_lg, bookingpage_font_semibold);

        optionsContainer = new VBox(12);  // 12px gap between cards per JSX
        optionsContainer.setAlignment(Pos.TOP_CENTER);
        optionsContainer.setFillWidth(true);

        roomOptionsWrapper.getChildren().addAll(selectAccomLabel, optionsContainer);
        roomOptionsWrapper.setVisible(false);
        roomOptionsWrapper.setManaged(false);

        container.getChildren().addAll(attendanceTypeContainer, roomOptionsWrapper);

        // Setup responsive design for card layouts
        setupResponsiveDesign();
    }

    protected void setupBindings() {
        // Update validity based on attendance type and room selection
        attendanceTypeProperty.addListener((obs, oldType, newType) -> {
            updateAttendanceTypeUI(newType);
            updateValidity();

            // When Day Visitor is selected, auto-select the day visitor option
            if (newType == AttendanceType.DAY_VISITOR) {
                AccommodationOption dayVisitorOpt = findDayVisitorOption();
                if (dayVisitorOpt != null) {
                    selectedOptionProperty.set(dayVisitorOpt);
                }
            }
        });

        // Update validity and visual state when option is selected
        selectedOptionProperty.addListener((obs, oldOption, newOption) -> {
            // Update card styles via CSS classes and checkmark badge visibility
            if (oldOption != null) {
                VBox oldCard = optionCardMap.get(oldOption);
                if (oldCard != null) {
                    oldCard.getStyleClass().remove(selected);
                }
                StackPane oldCheckmark = checkmarkBadgeMap.get(oldOption);
                if (oldCheckmark != null) {
                    oldCheckmark.setVisible(false);
                }
            }
            if (newOption != null) {
                VBox newCard = optionCardMap.get(newOption);
                if (newCard != null) {
                    newCard.getStyleClass().add(selected);
                }
                StackPane newCheckmark = checkmarkBadgeMap.get(newOption);
                if (newCheckmark != null) {
                    newCheckmark.setVisible(true);
                }
            }

            updateValidity();

            // Notify callback
            if (onOptionSelected != null && newOption != null) {
                onOptionSelected.accept(newOption);
            }
        });

        // Rebuild cards when list changes
        accommodationOptions.addListener((ListChangeListener<AccommodationOption>) change ->
            UiScheduler.runInUiThread(this::rebuildOptionCards));
    }

    /**
     * Updates the validity property based on current attendance type and room selection.
     */
    protected void updateValidity() {
        AttendanceType type = attendanceTypeProperty.get();
        if (type == null) {
            validProperty.set(false);
        } else if (type == AttendanceType.DAY_VISITOR) {
            validProperty.set(true);
        } else {
            // ONSITE: need a room selected
            AccommodationOption selected = selectedOptionProperty.get();
            validProperty.set(selected != null && selected.isAvailable() && !selected.isDayVisitor());
        }
    }

    /**
     * Updates the attendance type card UI (selected state, checkmarks, icons).
     */
    protected void updateAttendanceTypeUI(AttendanceType newType) {
        boolean isOnsite = newType == AttendanceType.ONSITE;
        boolean isDayVisitor = newType == AttendanceType.DAY_VISITOR;

        // Update Onsite card
        if (onsiteCard != null) {
            if (isOnsite) {
                onsiteCard.getStyleClass().add(selected);
            } else {
                onsiteCard.getStyleClass().remove(selected);
            }
        }
        if (onsiteCheckmark != null) {
            onsiteCheckmark.setVisible(isOnsite);
        }
        // Update icon circle background via CSS class toggle
        if (onsiteIconCircle != null) {
            if (isOnsite) {
                onsiteIconCircle.getStyleClass().add(selected);
            } else {
                onsiteIconCircle.getStyleClass().remove(selected);
            }
        }
        // Update icon stroke color directly
        if (onsiteIcon != null) {
            onsiteIcon.setStroke(isOnsite ? Color.WHITE : Color.web("#6B7280"));
        }

        // Update Day Visitor card
        if (dayVisitorCard != null) {
            if (isDayVisitor) {
                dayVisitorCard.getStyleClass().add(selected);
            } else {
                dayVisitorCard.getStyleClass().remove(selected);
            }
        }
        if (dayVisitorCheckmark != null) {
            dayVisitorCheckmark.setVisible(isDayVisitor);
        }
        // Update icon circle background via CSS class toggle
        if (dayVisitorIconCircle != null) {
            if (isDayVisitor) {
                dayVisitorIconCircle.getStyleClass().add(selected);
            } else {
                dayVisitorIconCircle.getStyleClass().remove(selected);
            }
        }
        // Update icon stroke color directly
        if (dayVisitorIcon != null) {
            dayVisitorIcon.setStroke(isDayVisitor ? Color.WHITE : Color.web("#6B7280"));
        }

        // Show/hide room options based on attendance type
        if (roomOptionsWrapper != null) {
            roomOptionsWrapper.setVisible(isOnsite);
            roomOptionsWrapper.setManaged(isOnsite);
        }
    }

    /**
     * Finds the Day Visitor option in the options list.
     */
    protected AccommodationOption findDayVisitorOption() {
        return accommodationOptions.stream()
            .filter(AccommodationOption::isDayVisitor)
            .findFirst()
            .orElse(null);
    }

    // ========================================
    // ATTENDANCE TYPE CARDS (Onsite vs Day Visitor)
    // ========================================

    /**
     * Creates the attendance type selection cards (Onsite vs Day Visitor).
     * Cards use FlowPane for responsive wrapping but take full available width.
     */
    protected FlowPane createAttendanceTypeCards() {
        FlowPane flowPane = new FlowPane();
        flowPane.setHgap(20);
        flowPane.setVgap(20);
        flowPane.setAlignment(Pos.CENTER);

        // Create Onsite card
        onsiteCard = createAttendanceTypeCard(
            AttendanceType.ONSITE,
            BookingPageI18nKeys.Onsite,
            BookingPageI18nKeys.OnsiteDescription,
            true  // isHouse icon
        );

        // Create Day Visitor card
        dayVisitorCard = createAttendanceTypeCard(
            AttendanceType.DAY_VISITOR,
            BookingPageI18nKeys.DayVisitor,
            BookingPageI18nKeys.DayVisitorDescription,
            false  // isSun icon
        );

        flowPane.getChildren().addAll(onsiteCard, dayVisitorCard);

        // Bind card widths to take equal share of container width (minus gap)
        flowPane.widthProperty().addListener((obs, oldVal, newVal) -> {
            double containerWidth = newVal.doubleValue();
            if (containerWidth > 500) {
                // Desktop: two cards side by side, each taking half minus gap
                double cardWidth = (containerWidth - 20) / 2;
                onsiteCard.setPrefWidth(cardWidth);
                dayVisitorCard.setPrefWidth(cardWidth);
            } else {
                // Mobile: cards stack, each takes full width
                onsiteCard.setPrefWidth(containerWidth);
                dayVisitorCard.setPrefWidth(containerWidth);
            }
        });

        return flowPane;
    }

    /**
     * Creates a single attendance type card.
     */
    protected VBox createAttendanceTypeCard(AttendanceType type, Object titleI18nKey, Object descI18nKey, boolean isHouseIcon) {
        VBox card = new VBox(12);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(24));
        card.setMinWidth(200);
        card.setMaxWidth(Double.MAX_VALUE);
        card.getStyleClass().add(bookingpage_attendance_card);

        // Icon circle (56px) - styled via CSS class
        StackPane iconCircle = new StackPane();
        iconCircle.setPrefSize(56, 56);
        iconCircle.setMinSize(56, 56);
        iconCircle.setMaxSize(56, 56);
        iconCircle.getStyleClass().add(bookingpage_attendance_icon_circle);

        // Icon SVG - MUST set stroke/fill properties for visibility
        SVGPath icon = new SVGPath();
        if (isHouseIcon) {
            // House icon (stroke-based outline)
            icon.setContent("M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6");
        } else {
            // Sun icon (stroke-based outline)
            icon.setContent("M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z");
        }
        // Set stroke properties for icon visibility
        icon.setStroke(Color.web("#6B7280"));
        icon.setStrokeWidth(1.5);
        icon.setStrokeLineCap(StrokeLineCap.ROUND);
        icon.setStrokeLineJoin(StrokeLineJoin.ROUND);
        icon.setFill(Color.TRANSPARENT);
        icon.setScaleX(1.2);
        icon.setScaleY(1.2);
        iconCircle.getChildren().add(icon);

        // Store references for selection updates
        if (isHouseIcon) {
            onsiteIconCircle = iconCircle;
            onsiteIcon = icon;
        } else {
            dayVisitorIconCircle = iconCircle;
            dayVisitorIcon = icon;
        }

        // Title
        Label titleLabel = I18nControls.newLabel(titleI18nKey);
        titleLabel.getStyleClass().addAll(bookingpage_text_lg, bookingpage_font_semibold);

        // Description
        Label descLabel = I18nControls.newLabel(descI18nKey);
        descLabel.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_muted);
        descLabel.setWrapText(true);
        descLabel.setAlignment(Pos.CENTER);

        // Content
        VBox content = new VBox(12);
        content.setAlignment(Pos.CENTER);
        content.getChildren().addAll(iconCircle, titleLabel, descLabel);

        // Checkmark badge (24px, top-right when selected)
        StackPane checkmark = BookingPageUIBuilder.createCheckmarkBadgeCss(24);
        checkmark.setVisible(false);

        // Store reference for selection updates
        if (isHouseIcon) {
            onsiteCheckmark = checkmark;
        } else {
            dayVisitorCheckmark = checkmark;
        }

        // Wrapper with checkmark positioning
        StackPane wrapper = new StackPane(content, checkmark);
        StackPane.setAlignment(checkmark, Pos.TOP_RIGHT);
        StackPane.setMargin(checkmark, new Insets(-8, -8, 0, 0));

        card.getChildren().add(wrapper);

        // Click handler
        card.setOnMouseClicked(e -> {
            attendanceTypeProperty.set(type);
        });

        return card;
    }

    protected void rebuildOptionCards() {
        optionsContainer.getChildren().clear();
        optionCardMap.clear();
        checkmarkBadgeMap.clear();
        cardHeaderList.clear();

        AccommodationOption currentlySelected = selectedOptionProperty.get();
        Object selectedItemId = currentlySelected != null ? currentlySelected.getItemId() : null;

        AccommodationOption matchingOption = null;

        // Filter to only room options:
        // - Exclude day visitor (handled by attendance type selection)
        // - Exclude share accommodation options
        List<AccommodationOption> roomOpts = accommodationOptions.stream()
            .filter(opt -> !opt.isDayVisitor())
            .filter(opt -> {
                Item item = opt.getItemEntity();
                // Exclude share_mate items (Share Accommodation option)
                return item == null || !Boolean.TRUE.equals(item.isShare_mate());
            })
            .collect(Collectors.toList());

        // Add room option cards
        for (AccommodationOption option : roomOpts) {
            boolean isSelected = selectedItemId != null && selectedItemId.equals(option.getItemId());
            VBox card = createOptionCard(option, isSelected);
            optionsContainer.getChildren().add(card);
            optionCardMap.put(option, card);

            if (isSelected) {
                matchingOption = option;
            }
        }

        if (matchingOption != null && currentlySelected != matchingOption) {
            selectedOptionProperty.set(matchingOption);
        }

        // Reapply current layout after rebuilding cards
        applyCardLayout(isMobileLayout);
    }

    protected VBox createOptionCard(AccommodationOption option, boolean isSelected) {
        boolean isSoldOut = option.getAvailability() == AvailabilityStatus.SOLD_OUT;
        boolean isAvailable = option.isAvailable();

        VBox card = new VBox(8);
        card.setMaxWidth(Double.MAX_VALUE);  // Full width cards
        // Padding is applied to contentBox instead of card when soldout,
        // so the ribbon can extend to card edges
        if (!isSoldOut) {
            card.setPadding(new Insets(20));
        }
        card.getStyleClass().add(bookingpage_selectable_card);

        // Apply CSS classes for different states (styling handled in CSS)
        if (isSoldOut) {
            card.getStyleClass().addAll(soldout, disabled);
        } else if (isSelected) {
            card.getStyleClass().add(selected);
        }

        // Content container - has padding when soldout (since card doesn't)
        VBox contentBox = new VBox(8);
        if (isSoldOut) {
            contentBox.setPadding(new Insets(20));
        }

        // === HEADER ROW: Room name (left) + Total Price (right) ===
        // Using VBox as container for responsive layout switching (horizontal/vertical)
        VBox headerContainer = new VBox(8);

        // Room name - 16px, semibold
        Label nameLabel = new Label(option.getName());
        nameLabel.getStyleClass().addAll(bookingpage_text_lg, bookingpage_font_semibold);
        if (isSoldOut) {
            nameLabel.getStyleClass().add(bookingpage_text_muted_light);
        } else {
            nameLabel.getStyleClass().add(bookingpage_text_dark);
        }
        nameLabel.setWrapText(true);

        // Total price with per person/room indicator
        int totalPrice = calculateTotalPrice(option);

        // Price container (VBox with price and per person/room text)
        VBox priceContainer = new VBox(2);

        Label priceLabel = new Label(formatPrice(totalPrice));
        priceLabel.getStyleClass().addAll(bookingpage_text_2xl, bookingpage_font_bold);
        if (isSoldOut) {
            priceLabel.getStyleClass().addAll(bookingpage_text_muted_light, bookingpage_text_strikethrough);
        } else {
            priceLabel.getStyleClass().add(bookingpage_text_dark);
        }

        // Per person / Per room indicator (skip for Day Visitor)
        if (!option.isDayVisitor()) {
            Object pricingTypeKey = option.isPerPerson() ? BookingPageI18nKeys.PerPerson : BookingPageI18nKeys.PerRoom;
            Label pricingTypeLabel = I18nControls.newLabel(pricingTypeKey);
            pricingTypeLabel.getStyleClass().addAll(bookingpage_text_xs, bookingpage_text_muted);
            priceContainer.getChildren().addAll(priceLabel, pricingTypeLabel);
        } else {
            priceContainer.getChildren().add(priceLabel);
        }

        // Store checkmark badge reference (32px, will be created later for available cards)
        StackPane checkmarkBadge = null;
        if (isAvailable) {
            checkmarkBadge = BookingPageUIBuilder.createCheckmarkBadgeCss(32);
            checkmarkBadge.setVisible(isSelected);
            checkmarkBadgeMap.put(option, checkmarkBadge);
        }

        // Track header components for responsive layout switching
        cardHeaderList.add(new CardHeaderComponents(headerContainer, nameLabel, priceContainer, checkmarkBadge, isAvailable));

        contentBox.getChildren().add(headerContainer);

        // === BADGES ROW: Constraint badge (separate row, not inline with name) ===
        if (option.hasConstraint()) {
            HBox badgesRow = new HBox(8);
            badgesRow.setAlignment(Pos.CENTER_LEFT);
            HBox constraintBadge = createConstraintBadge(option, isSoldOut);
            badgesRow.getChildren().add(constraintBadge);
            contentBox.getChildren().add(badgesRow);
        }

        // === DATE RESTRICTION BADGES ROW ===
        if (option.hasDateRestrictions()) {
            FlowPane restrictionBadges = new FlowPane();
            restrictionBadges.setHgap(8);
            restrictionBadges.setVgap(4);

            if (!option.isEarlyArrivalAllowed()) {
                HBox earlyBadge = createDateRestrictionBadge(BookingPageI18nKeys.NoEarlyArrival, isSoldOut);
                restrictionBadges.getChildren().add(earlyBadge);
            }
            if (!option.isLateDepartureAllowed()) {
                HBox lateBadge = createDateRestrictionBadge(BookingPageI18nKeys.NoLateDeparture, isSoldOut);
                restrictionBadges.getChildren().add(lateBadge);
            }

            contentBox.getChildren().add(restrictionBadges);
        }

        // === DESCRIPTION (at bottom, 13px muted) ===
        if (option.getDescription() != null && !option.getDescription().isEmpty()) {
            Label descLabel = new Label(option.getDescription());
            descLabel.getStyleClass().add(bookingpage_text_sm);
            if (isSoldOut) {
                descLabel.getStyleClass().add(bookingpage_text_muted_disabled);
            } else {
                descLabel.getStyleClass().add(bookingpage_text_muted);
            }
            descLabel.setWrapText(true);
            contentBox.getChildren().add(descLabel);
        }

        // === SHARING NOTE for "Per room" accommodations with capacity > 1 ===
        // Explains that one person books the room, roommates use "Share Accommodation"
        if (!option.isDayVisitor() && !option.isPerPerson()) {
            Item item = option.getItemEntity();
            Integer capacity = item != null ? item.getCapacity() : null;
            if (capacity != null && capacity > 1) {
                Label sharingNote = I18nControls.newLabel(BookingPageI18nKeys.RoomBookingSharingNote);
                sharingNote.getStyleClass().add(bookingpage_text_sm);
                if (isSoldOut) {
                    sharingNote.getStyleClass().add(bookingpage_text_muted_disabled);
                } else {
                    sharingNote.getStyleClass().add(bookingpage_text_muted);
                }
                sharingNote.setWrapText(true);
                contentBox.getChildren().add(sharingNote);
            }
        }

        // Wrap content with checkmark badge or sold out ribbon
        if (isAvailable) {
            // Use the checkmark badge already created and stored in checkmarkBadgeMap
            StackPane existingCheckmarkBadge = checkmarkBadgeMap.get(option);

            // StackPane wrapper to position checkmark in top-right corner
            StackPane wrapper = new StackPane(contentBox, existingCheckmarkBadge);
            StackPane.setAlignment(existingCheckmarkBadge, Pos.TOP_RIGHT);
            StackPane.setMargin(existingCheckmarkBadge, new Insets(-8, -8, 0, 0));

            card.getChildren().add(wrapper);
        } else {
            // Add sold out ribbon
            StackPane wrapper = new StackPane(contentBox);
            wrapper.setMaxWidth(Double.MAX_VALUE);  // Fill card width so ribbon reaches edge
            if (isSoldOut) {
                Node ribbon = createSoldOutRibbon();
                wrapper.getChildren().add(ribbon);
                StackPane.setAlignment(ribbon, Pos.TOP_RIGHT);

                // Apply rounded rectangle clip to card to contain ribbon within borders
                // Match border-radius of 12px
                Rectangle clip = new Rectangle();
                clip.setArcWidth(24); // 2x border-radius for arc
                clip.setArcHeight(24);
                clip.widthProperty().bind(card.widthProperty());
                clip.heightProperty().bind(card.heightProperty());
                card.setClip(clip);
            }
            card.getChildren().add(wrapper);
        }

        // Click handler (only if available)
        if (isAvailable) {
            card.setOnMouseClicked(e -> handleOptionClick(option));
        }

        return card;
    }

    protected HBox createPriceRow(int totalPrice, int pricePerNight) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        // Total price
        Label totalLabel = new Label(formatPrice(totalPrice));
        totalLabel.getStyleClass().addAll(bookingpage_text_xl, bookingpage_font_bold, bookingpage_text_primary);

        // "total" text
        Label totalTextLabel = I18nControls.newLabel(BookingPageI18nKeys.Total);
        totalTextLabel.getStyleClass().addAll(bookingpage_text_sm, bookingpage_text_muted);

        row.getChildren().addAll(totalLabel, totalTextLabel);

        // Per night breakdown (if applicable)
        if (pricePerNight > 0) {
            String perNightText = " " + I18n.getI18nText(BookingPageI18nKeys.PerNightFormat, formatPrice(pricePerNight));
            Label breakdownLabel = new Label(perNightText);
            breakdownLabel.getStyleClass().addAll(bookingpage_text_xs, bookingpage_text_muted);
            row.getChildren().add(breakdownLabel);
        }

        return row;
    }

    /**
     * Creates the price breakdown box displaying individual line items (teachings, accommodation, meals).
     *
     * @param option the accommodation option
     * @param isSoldOut whether the option is sold out (affects styling)
     * @return a VBox containing the breakdown rows, or null if no breakdown is available
     */
    protected VBox createBreakdownBox(AccommodationOption option, boolean isSoldOut) {
        // Get the breakdown for this option
        List<PriceBreakdownItem> breakdown = getBreakdownForOption(option.getItemId());
        if (breakdown == null || breakdown.isEmpty()) {
            return null;
        }

        VBox breakdownBox = new VBox(4);
        breakdownBox.setPadding(new Insets(8, 0, 0, 0));

        for (PriceBreakdownItem item : breakdown) {
            HBox row = new HBox();
            row.setAlignment(Pos.CENTER_LEFT);

            // Category label (left side)
            String categoryText = item.getCategory();
            if (item.getDateRange() != null && !item.getDateRange().isEmpty()) {
                categoryText = categoryText + " (" + item.getDateRange() + ")";
            }
            Label categoryLabel = new Label(categoryText);
            categoryLabel.getStyleClass().add(bookingpage_text_sm);
            if (isSoldOut) {
                categoryLabel.getStyleClass().add(bookingpage_text_muted_disabled);
            } else {
                categoryLabel.getStyleClass().add(bookingpage_text_muted);
            }

            // Spacer
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            // Price label (right side)
            Label priceLabel = new Label(formatPrice(item.getPrice()));
            priceLabel.getStyleClass().add(bookingpage_text_sm);
            if (isSoldOut) {
                priceLabel.getStyleClass().add(bookingpage_text_muted_disabled);
            } else {
                priceLabel.getStyleClass().add(bookingpage_text_muted);
            }

            row.getChildren().addAll(categoryLabel, spacer, priceLabel);
            breakdownBox.getChildren().add(row);
        }

        return breakdownBox;
    }

    protected HBox createConstraintBadge(AccommodationOption option, boolean isSoldOut) {
        HBox badge = new HBox(5);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(4, 10, 4, 10));
        badge.setMaxWidth(Region.USE_PREF_SIZE);
        badge.getStyleClass().add(bookingpage_badge_constraint);

        // Apply disabled class for sold out state - CSS handles colors
        if (isSoldOut) {
            badge.getStyleClass().add(disabled);
        }

        // Info icon (circle with "i" - using SVG path) - CSS handles colors
        SVGPath infoIcon = new SVGPath();
        // Circle with 'i' icon path
        infoIcon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z");
        infoIcon.setScaleX(0.5);
        infoIcon.setScaleY(0.5);
        infoIcon.getStyleClass().add(bookingpage_badge_constraint_icon);

        // Text
        String constraintText = option.getConstraintLabel();
        if (constraintText == null) {
            constraintText = option.getConstraintType() == ConstraintType.FULL_EVENT_ONLY
                ? I18n.getI18nText(BookingPageI18nKeys.FullFestivalOnly)
                : I18n.getI18nText(BookingPageI18nKeys.MinNights, option.getMinNights());
        }
        Label textLabel = new Label(constraintText);
        textLabel.getStyleClass().add(bookingpage_badge_constraint_text);

        badge.getChildren().addAll(infoIcon, textLabel);
        return badge;
    }

    /**
     * Creates a date restriction badge (No Early Arrival / No Late Departure).
     * Uses the same visual style as constraint badges.
     *
     * @param i18nKey the i18n key for the badge text
     * @param isSoldOut whether the option is sold out (affects styling)
     * @return an HBox containing the badge
     */
    protected HBox createDateRestrictionBadge(Object i18nKey, boolean isSoldOut) {
        HBox badge = new HBox(5);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(4, 10, 4, 10));
        badge.setMaxWidth(Region.USE_PREF_SIZE);
        badge.getStyleClass().add(bookingpage_badge_constraint);

        if (isSoldOut) {
            badge.getStyleClass().add(disabled);
        }

        // Info icon (same as constraint badge)
        SVGPath infoIcon = new SVGPath();
        infoIcon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z");
        infoIcon.setScaleX(0.5);
        infoIcon.setScaleY(0.5);
        infoIcon.getStyleClass().add(bookingpage_badge_constraint_icon);

        // Text from i18n key
        Label textLabel = I18nControls.newLabel(i18nKey);
        textLabel.getStyleClass().add(bookingpage_badge_constraint_text);

        badge.getChildren().addAll(infoIcon, textLabel);
        return badge;
    }

    protected HBox createLimitedBadge() {
        HBox badge = new HBox(4);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(2, 6, 2, 6));
        badge.getStyleClass().add(bookingpage_badge_limited);

        Label icon = new Label("\u26A0"); // Warning sign
        icon.getStyleClass().addAll(bookingpage_text_xs);

        Label textLabel = I18nControls.newLabel(BookingPageI18nKeys.LimitedAvailability);
        textLabel.getStyleClass().addAll(bookingpage_text_xs, bookingpage_font_medium);

        badge.getChildren().addAll(icon, textLabel);
        return badge;
    }

    protected Node createSoldOutRibbon() {
        // Create a diagonal corner ribbon in the top-right corner (per JSX mockup)
        // Style: warm gray background (#78716c), light text (#fafaf9), rotated 45deg
        StackPane ribbon = new StackPane();
        ribbon.getStyleClass().add(bookingpage_soldout_ribbon);
        ribbon.setAlignment(Pos.CENTER);  // Center the text in the ribbon

        // Text styling - font size defined in CSS class
        Label ribbonText = I18nControls.newLabel(BookingPageI18nKeys.SoldOut);
        ribbonText.getStyleClass().add(bookingpage_soldout_ribbon_text);

        // Padding for the ribbon - asymmetric to center text in visible area
        // More padding on left to shift text towards visible center
        ribbon.setPadding(new Insets(4, 35, 4, 55));

        ribbon.getChildren().add(ribbonText);
        ribbon.setMaxWidth(Region.USE_PREF_SIZE);
        ribbon.setMaxHeight(Region.USE_PREF_SIZE);

        // Rotate for diagonal effect (45deg per JSX)
        ribbon.setRotate(45);

        // Position: extend to right edge, but low enough for text to be visible
        ribbon.setTranslateX(30);
        ribbon.setTranslateY(18);

        return ribbon;
    }

    protected void handleOptionClick(AccommodationOption option) {
        if (!option.isAvailable()) {
            return;
        }
        selectedOptionProperty.set(option);
    }

    // ========================================
    // RESPONSIVE DESIGN
    // ========================================

    /**
     * Sets up responsive design to switch card header layouts based on container width.
     */
    protected void setupResponsiveDesign() {
        responsiveDesign = new ResponsiveDesign(container);

        // Desktop layout (width >= 450): horizontal name + price layout
        responsiveDesign.addResponsiveLayout(
            width -> width >= 450,
            () -> {
                isMobileLayout = false;
                applyCardLayout(false);
            }
        );

        // Mobile layout (width < 450): vertical stacked layout
        responsiveDesign.addResponsiveLayout(
            width -> width < 450,
            () -> {
                isMobileLayout = true;
                applyCardLayout(true);
            }
        );

        responsiveDesign.start();
    }

    /**
     * Applies the card header layout (horizontal or vertical) to all option cards.
     *
     * @param mobile true for vertical mobile layout, false for horizontal desktop layout
     */
    protected void applyCardLayout(boolean mobile) {
        for (CardHeaderComponents header : cardHeaderList) {
            header.headerContainer.getChildren().clear();

            if (mobile) {
                // Mobile: stack name and price vertically
                header.nameLabel.setAlignment(Pos.CENTER_LEFT);
                header.priceContainer.setAlignment(Pos.CENTER_LEFT);

                header.headerContainer.setSpacing(8);
                header.headerContainer.getChildren().addAll(header.nameLabel, header.priceContainer);
            } else {
                // Desktop: name and price side by side with spacer
                header.nameLabel.setAlignment(Pos.CENTER_LEFT);
                header.priceContainer.setAlignment(Pos.TOP_RIGHT);

                HBox hbox = new HBox();
                hbox.setAlignment(Pos.TOP_LEFT);
                // Reserve space for checkmark badge on available cards
                if (header.isAvailable) {
                    hbox.setPadding(new Insets(0, 40, 0, 0));
                }

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox.setHgrow(header.nameLabel, Priority.SOMETIMES);

                hbox.getChildren().addAll(header.nameLabel, spacer, header.priceContainer);
                header.headerContainer.getChildren().add(hbox);
            }
        }
    }

    protected int calculateTotalPrice(AccommodationOption option) {
        // Price is always pre-calculated via WorkingBooking by the form class
        return option.hasPreCalculatedPrice() ? option.getPreCalculatedTotalPrice() : 0;
    }

    protected String formatPrice(int priceInCents) {
        return EventPriceFormatter.formatWithCurrency(priceInCents, workingBookingProperties != null ? workingBookingProperties.getEvent() : null);
    }

    // ========================================
    // BookingFormSection INTERFACE
    // ========================================

    @Override
    public Object getTitleI18nKey() {
        return BookingPageI18nKeys.AccommodationOptions;
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

    @Override
    public String getValidationMessage() {
        AttendanceType type = attendanceTypeProperty.get();
        if (type == null) {
            return null; // No attendance type selected yet - don't show warning
        }
        if (type == AttendanceType.DAY_VISITOR) {
            return null; // Day visitor is always valid
        }
        // ONSITE: check if room is selected
        AccommodationOption selected = selectedOptionProperty.get();
        if (selected == null || !selected.isAvailable() || selected.isDayVisitor()) {
            return I18n.getI18nText(BookingPageI18nKeys.PleaseSelectAccommodation);
        }
        return null; // Valid room selected
    }

    // ========================================
    // HasAccommodationSelectionSection INTERFACE
    // ========================================

    @Override
    public void addAccommodationOption(AccommodationOption option) {
        accommodationOptions.add(option);
    }

    @Override
    public void clearOptions() {
        accommodationOptions.clear();
        selectedOptionProperty.set(null);
        clearBreakdowns();
    }

    // === PRICE BREAKDOWN METHODS ===

    /**
     * Sets the price breakdown for a specific accommodation option.
     *
     * @param itemId the accommodation item ID (or special IDs like "DAY_VISITOR", "SHARE_ACCOMMODATION")
     * @param breakdown the list of breakdown items
     */
    public void setBreakdownForOption(Object itemId, List<PriceBreakdownItem> breakdown) {
        breakdownMap.put(itemId, breakdown);
    }

    /**
     * Gets the price breakdown for a specific accommodation option.
     *
     * @param itemId the accommodation item ID
     * @return the list of breakdown items, or null if none set
     */
    public List<PriceBreakdownItem> getBreakdownForOption(Object itemId) {
        return breakdownMap.get(itemId);
    }

    /**
     * Clears all stored breakdowns (called automatically by clearOptions).
     */
    public void clearBreakdowns() {
        breakdownMap.clear();
    }

    @Override
    public void setSelectedOption(Object itemId) {
        for (AccommodationOption option : accommodationOptions) {
            if (option.getItemId() != null && option.getItemId().equals(itemId)) {
                selectedOptionProperty.set(option);
                return;
            }
        }
    }

    @Override
    public ObjectProperty<AccommodationOption> selectedOptionProperty() {
        return selectedOptionProperty;
    }

    @Override
    public ObjectProperty<AttendanceType> attendanceTypeProperty() {
        return attendanceTypeProperty;
    }

    @Override
    public void setAttendanceType(AttendanceType type) {
        attendanceTypeProperty.set(type);
    }

    @Override
    public void setOnOptionSelected(Consumer<AccommodationOption> callback) {
        this.onOptionSelected = callback;
    }

    @Override
    public void setOnContinuePressed(Runnable callback) {
        this.onContinuePressed = callback;
    }

    @Override
    public void setOnBackPressed(Runnable callback) {
        this.onBackPressed = callback;
    }

    // ========================================
    // DATA POPULATION FROM POLICY AGGREGATE
    // ========================================

    /**
     * Populates accommodation options from PolicyAggregate data.
     * Checks availability using ScheduledItem.guestsAvailability and
     * constraints using ItemPolicy.minDay.
     *
     * @param policyAggregate the policy data containing scheduledItems and itemPolicies
     * @param limitedThreshold availability count below which to show as LIMITED (e.g., 5)
     */
    @Override
    public void populateFromPolicyAggregate(PolicyAggregate policyAggregate, int limitedThreshold) {
        clearOptions();

        if (policyAggregate == null) {
            return;
        }

        // Group accommodation ScheduledItems by Item
        List<ScheduledItem> accommodationItems = policyAggregate.filterAccommodationScheduledItems();
        Map<Item, List<ScheduledItem>> itemScheduledItems = accommodationItems.stream()
            .filter(si -> si.getItem() != null)
            .collect(Collectors.groupingBy(ScheduledItem::getItem));

        // Sort entries by Item.getOrd() to display in correct order
        List<Map.Entry<Item, List<ScheduledItem>>> sortedEntries = itemScheduledItems.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey().getOrd() != null ? e.getKey().getOrd() : Integer.MAX_VALUE))
            .collect(Collectors.toList());

        for (Map.Entry<Item, List<ScheduledItem>> entry : sortedEntries) {
            Item item = entry.getKey();
            List<ScheduledItem> scheduledItems = entry.getValue();

            // Calculate minimum availability across all days
            int minAvailability = scheduledItems.stream()
                .mapToInt(si -> si.getGuestsAvailability() != null ? si.getGuestsAvailability() : 0)
                .min()
                .orElse(0);

            // Determine availability status
            AvailabilityStatus status;
            if (minAvailability <= 0) {
                status = AvailabilityStatus.SOLD_OUT;
            } else if (minAvailability <= limitedThreshold) {
                status = AvailabilityStatus.LIMITED;
            } else {
                status = AvailabilityStatus.AVAILABLE;
            }

            // Get constraint from ItemPolicy
            ItemPolicy itemPolicy = policyAggregate.getItemPolicy(item);
            ConstraintType constraintType = ConstraintType.NONE;
            String constraintLabel = null;
            int minNights = 0;

            if (itemPolicy != null && itemPolicy.getMinDay() != null && itemPolicy.getMinDay() > 0) {
                constraintType = ConstraintType.MIN_NIGHTS;
                minNights = itemPolicy.getMinDay();
                constraintLabel = I18n.getI18nText(BookingPageI18nKeys.MinNights, minNights);
            }

            // Get price and perPerson flag from rates (accommodation daily rate)
            // Try to find rate for this item - first try with null site, then try all rates
            Rate itemRate = policyAggregate.filterDailyRatesStreamOfSiteAndItem(null, item)
                .findFirst()
                .orElseGet(() -> {
                    // Fallback: search all daily rates for this item regardless of site
                    return policyAggregate.getDailyRatesStream()
                        .filter(r -> r.getItem() != null && r.getItem().getPrimaryKey() != null
                            && r.getItem().getPrimaryKey().equals(item.getPrimaryKey()))
                        .findFirst()
                        .orElse(null);
                });

            int pricePerNight = itemRate != null && itemRate.getPrice() != null ? itemRate.getPrice() : 0;
            // Per person = true (default), Per room = false
            boolean perPerson = itemRate == null || !Boolean.FALSE.equals(itemRate.isPerPerson());

            // Get item name and description
            String name = item.getName() != null ? item.getName() : "";
            // Note: Item doesn't have a direct description field, use empty string
            // In a production implementation, this could be fetched from a related entity
            String description = "";

            // Create AccommodationOption with perPerson info
            AccommodationOption option = new AccommodationOption(
                item.getPrimaryKey(),
                item,
                name,
                description,
                pricePerNight,
                status,
                constraintType,
                constraintLabel,
                minNights,
                false, // isDayVisitor - handled separately
                null,  // imageUrl
                perPerson  // per person or per room pricing
            );

            addAccommodationOption(option);
        }
    }
}

package one.modality.booking.frontoffice.bookingpage.sections.options;

import dev.webfx.extras.i18n.controls.I18nControls;
import dev.webfx.kit.util.properties.FXProperties;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import one.modality.base.shared.entities.BookablePeriod;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.formatters.EventPriceFormatter;
import one.modality.booking.client.workingbooking.WorkingBookingProperties;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;
import one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors;
import one.modality.booking.frontoffice.bookingpage.BookingPageI18nKeys;
import one.modality.booking.frontoffice.bookingpage.components.BookingPageUIBuilder;
import one.modality.booking.frontoffice.bookingpage.components.StyledSectionHeader;


import java.time.LocalDate;
import java.util.function.Consumer;

import static one.modality.booking.frontoffice.bookingpage.BookingPageCssSelectors.*;

/**
 * Default rate type/pricing section for online event booking forms.
 * Displays programme info with selectable rate options (Standard, optionally Member).
 *
 * <p>This section is UI-only - it displays data provided by the model layer and notifies
 * callbacks on user interaction. It does NOT load or book ScheduledItems itself.</p>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@link #setShowMemberRate(boolean)} - Show Member rate card (default: false)</li>
 *   <li>{@link #setProgrammeInfo(BookablePeriod, String, LocalDate, LocalDate)} - Set programme display</li>
 *   <li>{@link #setStandardPrice(int)} and {@link #setMemberPrice(int)} - Set prices</li>
 * </ul>
 *
 * <p>Uses CSS for styling - colors come from CSS variables that can be
 * overridden by theme classes (e.g., .theme-wisdom-blue) on a parent container.</p>
 *
 * @author Bruno Salmon
 * @see HasRateTypeSection
 */
public class DefaultRateTypeSection implements HasRateTypeSection, BookingFormSection {

    // === UI Components ===
    protected final VBox container = new VBox(20);
    protected StyledSectionHeader header;
    protected VBox cardsContainer;
    protected VBox standardCard;
    protected VBox memberCard;
    protected Label standardPriceLabel;
    protected Label memberPriceLabel;

    // Programme info labels (stored for updating)
    protected Label standardProgrammeTitleLabel;
    protected Label standardDateRangeLabel;
    protected Label memberProgrammeTitleLabel;
    protected Label memberDateRangeLabel;

    // === Configuration ===
    protected boolean showMemberRate = false;  // Default: show only Standard rate
    protected boolean uiBuilt = false;

    // === State ===
    protected final ObjectProperty<RateType> selectedRateType = new SimpleObjectProperty<>(RateType.STANDARD);
    protected final SimpleBooleanProperty validProperty = new SimpleBooleanProperty(true);
    protected final ObjectProperty<BookablePeriod> selectedPeriod = new SimpleObjectProperty<>();

    // === Data ===
    protected WorkingBookingProperties workingBookingProperties;
    protected String programmeTitle;
    protected String dateRangeText;
    protected int standardPrice = 0;
    protected int memberPrice = 0;

    // === Callbacks ===
    protected Consumer<RateType> onRateTypeChanged;
    protected Consumer<BookablePeriod> onPackageSelected;
    protected Consumer<WorkingBookingProperties> onDataLoadRequested;

    public DefaultRateTypeSection() {
        buildUI();
    }

    // ========================================
    // Configuration Methods
    // ========================================

    /**
     * Sets whether to show the Member rate card.
     * Must be called before the section is displayed.
     *
     * @param show true to show both Standard and Member cards, false for Standard only
     */
    public void setShowMemberRate(boolean show) {
        this.showMemberRate = show;
        if (uiBuilt) {
            rebuildCards();
        }
    }

    /**
     * Returns whether the Member rate card is shown.
     */
    public boolean isShowMemberRate() {
        return showMemberRate;
    }

    /**
     * Sets the programme information to display on the cards.
     * Called by the model layer after loading programme data.
     *
     * @param period the BookablePeriod (for i18n binding)
     * @param title the programme title text
     * @param startDate the start date
     * @param endDate the end date
     */
    public void setProgrammeInfo(BookablePeriod period, String title, LocalDate startDate, LocalDate endDate) {
        this.selectedPeriod.set(period);
        this.programmeTitle = title;

        if (startDate != null && endDate != null) {
            this.dateRangeText = BookingPageUIBuilder.formatDateRangeShort(startDate, endDate);
        }

        // Update card labels
        updateProgrammeLabels();
    }

    // ========================================
    // UI Building
    // ========================================

    protected void buildUI() {
        // Section header
        header = new StyledSectionHeader(
                BookingPageI18nKeys.YourPricingTier,
                StyledSectionHeader.ICON_TAG
        );
        // Rate cards container (VBox for full-width stacked cards)
        cardsContainer = new VBox(12);
        cardsContainer.setAlignment(Pos.TOP_LEFT);
        cardsContainer.setMaxWidth(Double.MAX_VALUE);

        // Build cards based on configuration
        rebuildCards();

        container.getChildren().addAll(header, cardsContainer);
        container.getStyleClass().add(booking_form_rate_type_section);
        container.setMinWidth(0);
        container.setMaxWidth(Double.MAX_VALUE);

        uiBuilt = true;
    }

    /**
     * Rebuilds the rate cards based on current configuration.
     */
    protected void rebuildCards() {
        cardsContainer.getChildren().clear();

        // Always create Standard card
        standardCard = createRateCard(BookingPageI18nKeys.StandardRate, RateType.STANDARD);
        cardsContainer.getChildren().add(standardCard);

        // Optionally create Member card
        if (showMemberRate) {
            memberCard = createRateCard(BookingPageI18nKeys.MemberRate, RateType.MEMBER);
            cardsContainer.getChildren().add(memberCard);
        } else {
            memberCard = null;
            memberPriceLabel = null;
            memberProgrammeTitleLabel = null;
            memberDateRangeLabel = null;
        }

        // Update programme labels with current data
        updateProgrammeLabels();
        // Update price labels with current data
        updatePriceLabels();
    }

    /**
     * Creates a rate card with programme info and price.
     * Card is full-width with responsive content and text wrapping.
     */
    protected VBox createRateCard(Object titleKey, RateType rateType) {
        VBox card = new VBox(0);
        card.setPadding(new Insets(20, 24, 20, 24));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);  // Full width
        card.getStyleClass().add(bookingpage_card);
        card.setCursor(Cursor.HAND);

        // Track selection state for this card
        SimpleBooleanProperty isSelected = new SimpleBooleanProperty(selectedRateType.get() == rateType);
        selectedRateType.addListener((obs, old, newVal) -> isSelected.set(newVal == rateType));

        // Update card styling based on selection
        FXProperties.runNowAndOnPropertyChange(selected -> {
            if (selected) {
                if (!card.getStyleClass().contains(BookingPageCssSelectors.selected)) {
                    card.getStyleClass().add(BookingPageCssSelectors.selected);
                }
            } else {
                card.getStyleClass().remove(BookingPageCssSelectors.selected);
            }
        }, isSelected);

        // Horizontal layout: left content + price + checkmark on right
        HBox cardContent = new HBox(16);
        cardContent.setAlignment(Pos.CENTER_LEFT);
        cardContent.setMaxWidth(Double.MAX_VALUE);

        // Left side: badge, title, dates
        VBox leftContent = new VBox(8);
        leftContent.setAlignment(Pos.CENTER_LEFT);
        leftContent.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(leftContent, Priority.ALWAYS);

        // Rate badge
        Label rateBadge = I18nControls.newLabel(titleKey);
        rateBadge.setPadding(new Insets(4, 10, 4, 10));
        rateBadge.getStyleClass().add(booking_form_rate_badge);

        // Programme title - with text wrapping for responsiveness
        Label programmeTitleLabel = new Label();
        programmeTitleLabel.getStyleClass().addAll(bookingpage_text_xl, bookingpage_font_semibold, bookingpage_text_dark);
        programmeTitleLabel.setWrapText(true);
        programmeTitleLabel.setMaxWidth(Double.MAX_VALUE);

        // Date range - with text wrapping for responsiveness
        Label dateRangeLabel = new Label();
        dateRangeLabel.getStyleClass().addAll(bookingpage_text_base, bookingpage_text_muted);
        dateRangeLabel.setWrapText(true);
        dateRangeLabel.setMaxWidth(Double.MAX_VALUE);

        leftContent.getChildren().addAll(rateBadge, programmeTitleLabel, dateRangeLabel);

        // Right side: Checkmark and Price (fixed width, doesn't grow)
        VBox rightContent = new VBox(8);
        rightContent.setAlignment(Pos.CENTER_RIGHT);
        rightContent.setMinWidth(Region.USE_PREF_SIZE);  // Prevent shrinking

        // Checkmark badge - only visible when selected
        StackPane checkmarkBadge = BookingPageUIBuilder.createCheckmarkBadgeCss(28);
        checkmarkBadge.setVisible(isSelected.get());
        isSelected.addListener((obs, old, val) -> checkmarkBadge.setVisible(val));

        // Price label
        Label priceLabel = new Label();
        priceLabel.getStyleClass().addAll(bookingpage_price_large, bookingpage_text_dark);

        rightContent.getChildren().addAll(checkmarkBadge, priceLabel);

        cardContent.getChildren().addAll(leftContent, rightContent);
        card.getChildren().add(cardContent);

        // Store references for updating later
        if (rateType == RateType.STANDARD) {
            standardPriceLabel = priceLabel;
            standardProgrammeTitleLabel = programmeTitleLabel;
            standardDateRangeLabel = dateRangeLabel;
        } else {
            memberPriceLabel = priceLabel;
            memberProgrammeTitleLabel = programmeTitleLabel;
            memberDateRangeLabel = dateRangeLabel;
        }

        // Click handler for selection
        card.setOnMouseClicked(e -> {
            selectRateType(rateType);
            e.consume();
        });

        return card;
    }

    /**
     * Selects a rate type and notifies the callback.
     * Note: Applying the rate to WorkingBooking should be done by the model layer
     * in response to the onRateTypeChanged callback.
     */
    protected void selectRateType(RateType rateType) {
        selectedRateType.set(rateType);

        // Notify listener - model layer will apply to WorkingBooking
        if (onRateTypeChanged != null) {
            onRateTypeChanged.accept(rateType);
        }
    }

    /**
     * Updates programme title and date range labels on all cards.
     */
    protected void updateProgrammeLabels() {
        if (standardProgrammeTitleLabel != null) {
            standardProgrammeTitleLabel.setText(programmeTitle != null ? programmeTitle : "");
        }
        if (standardDateRangeLabel != null) {
            standardDateRangeLabel.setText(dateRangeText != null ? dateRangeText : "");
        }
        if (memberProgrammeTitleLabel != null) {
            memberProgrammeTitleLabel.setText(programmeTitle != null ? programmeTitle : "");
        }
        if (memberDateRangeLabel != null) {
            memberDateRangeLabel.setText(dateRangeText != null ? dateRangeText : "");
        }
    }

    /**
     * Updates price labels on all cards.
     */
    protected void updatePriceLabels() {
        if (standardPriceLabel != null && standardPrice > 0) {
            standardPriceLabel.setText(formatPrice(standardPrice));
        }
        if (memberPriceLabel != null && memberPrice > 0) {
            memberPriceLabel.setText(formatPrice(memberPrice));
        }
    }

    /**
     * Formats a price using event-aware currency.
     */
    protected String formatPrice(int priceInCents) {
        Event event = getEvent();
        return EventPriceFormatter.formatWithCurrency(priceInCents, event);
    }

    /**
     * Gets the event for currency detection.
     */
    protected Event getEvent() {
        if (workingBookingProperties != null && workingBookingProperties.getWorkingBooking() != null) {
            return workingBookingProperties.getWorkingBooking().getEvent();
        }
        return null;
    }

    // ========================================
    // BookingFormSection Implementation
    // ========================================

    @Override
    public Object getTitleI18nKey() {
        return BookingPageI18nKeys.YourPricingTier;
    }

    @Override
    public Node getView() {
        return container;
    }

    @Override
    public void setWorkingBookingProperties(WorkingBookingProperties workingBookingProperties) {
        this.workingBookingProperties = workingBookingProperties;
        // Notify the model layer to load data and set programme info/prices
        if (onDataLoadRequested != null && workingBookingProperties != null) {
            onDataLoadRequested.accept(workingBookingProperties);
        }
    }

    @Override
    public ObservableBooleanValue validProperty() {
        return validProperty;
    }

    // ========================================
    // HasRateTypeSection Implementation
    // ========================================

    @Override
    public RateType getSelectedRateType() {
        return selectedRateType.get();
    }

    /**
     * Returns the selected rate type property for binding.
     */
    public ObjectProperty<RateType> selectedRateTypeProperty() {
        return selectedRateType;
    }

    @Override
    public void setOnPackageSelected(Consumer<BookablePeriod> handler) {
        this.onPackageSelected = handler;
    }

    @Override
    public void setOnRateTypeChanged(Consumer<RateType> handler) {
        this.onRateTypeChanged = handler;
    }

    /**
     * Sets a callback to be invoked when WorkingBookingProperties becomes available.
     * The model layer should use this to load programme data and set it on the section.
     *
     * @param handler callback that receives the WorkingBookingProperties
     */
    public void setOnDataLoadRequested(Consumer<WorkingBookingProperties> handler) {
        this.onDataLoadRequested = handler;
    }

    /**
     * Sets the displayed price for Standard rate.
     * Price should be calculated externally using WorkingBooking + PriceCalculator.
     *
     * @param priceInCents the price in cents
     */
    @Override
    public void setStandardPrice(int priceInCents) {
        this.standardPrice = priceInCents;
        if (standardPriceLabel != null) {
            standardPriceLabel.setText(formatPrice(priceInCents));
        }
    }

    /**
     * Sets the displayed price for Member rate.
     * Price should be calculated externally using WorkingBooking + PriceCalculator.
     * Only effective if showMemberRate is true.
     *
     * @param priceInCents the price in cents
     */
    @Override
    public void setMemberPrice(int priceInCents) {
        this.memberPrice = priceInCents;
        if (showMemberRate && memberPriceLabel != null) {
            memberPriceLabel.setText(formatPrice(priceInCents));
        }
    }

    /**
     * Returns the selected BookablePeriod.
     */
    public BookablePeriod getSelectedPeriod() {
        return selectedPeriod.get();
    }

    /**
     * Returns the selected BookablePeriod property for binding.
     */
    public ObjectProperty<BookablePeriod> selectedPeriodProperty() {
        return selectedPeriod;
    }
}

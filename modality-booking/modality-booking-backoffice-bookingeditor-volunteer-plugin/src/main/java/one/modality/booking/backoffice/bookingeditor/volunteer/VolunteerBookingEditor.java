package one.modality.booking.backoffice.bookingeditor.volunteer;

import dev.webfx.extras.styles.bootstrap.Bootstrap;
import dev.webfx.kit.util.properties.FXProperties;
import dev.webfx.kit.util.properties.ObservableLists;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.platform.util.collection.HashList;
import dev.webfx.platform.util.time.Times;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import one.modality.base.shared.entities.*;
import one.modality.base.shared.entities.util.Attendances;
import one.modality.base.shared.entities.util.ScheduledItems;
import one.modality.base.shared.knownitems.KnownItemFamily;
import one.modality.booking.backoffice.bookingeditor.family.FamilyBookingEditorBase;
import one.modality.booking.client.selecteditemsselector.box.BoxScheduledItemsSelector;
import one.modality.booking.client.workingbooking.ArrivalDepartureTime;
import one.modality.booking.client.workingbooking.WorkingBooking;
import one.modality.ecommerce.policy.service.PolicyAggregate;

import java.time.LocalDate;
import java.util.List;

/**
 * @author Bruno Salmon
 */
final class VolunteerBookingEditor extends FamilyBookingEditorBase {

    private final BoxScheduledItemsSelector boxScheduledItemsSelector = new BoxScheduledItemsSelector(true, false);
    private final List<LocalDate> attendanceDates = new HashList<>();
    private final CheckBox breakfastCheckBox = new CheckBox("Breakfast");
    private final CheckBox lunchCheckBox = new CheckBox("Lunch");
    private final CheckBox dinnerCheckBox = new CheckBox("Dinner");
    private final CheckBox accommodationCheckBox = new CheckBox("Accommodation");
    private final RadioButton vegetarianDietRadioButton = new RadioButton("Vegetarian");
    private final RadioButton vegetarianWheatFreeDietRadioButton = new RadioButton("Vegetarian/Wheat-free");
    private final RadioButton veganDietRadioButton = new RadioButton("Vegan");
    private final RadioButton veganWheatFreeDietRadioButton = new RadioButton("Vegan/Wheat-free");
    private final ToggleGroup dietToggleGroup = new ToggleGroup();
    private final TextArea requestTextArea = new TextArea();
    private final RadioButton arrivalBreakfastRadioButton = new RadioButton();
    private final RadioButton arrivalLunchRadioButton = new RadioButton();
    private final RadioButton arrivalDinnerRadioButton = new RadioButton();
    private final RadioButton arrivalAccommodationRadioButton = new RadioButton();
    private final ToggleGroup arrivalToggleGroup = new ToggleGroup();
    private final RadioButton departureBreakfastRadioButton = new RadioButton();
    private final RadioButton departureLunchRadioButton = new RadioButton();
    private final RadioButton departureDinnerRadioButton = new RadioButton();
    private final RadioButton departureAccommodationRadioButton = new RadioButton();
    private final ToggleGroup departureToggleGroup = new ToggleGroup();
    private final ItemFamily dietFamily;
    private final Item vegetarianItem, vegetarianWheatFreeItem, veganItem, veganWheatFreeItem;
    private ArrivalDepartureTime arrivalTime;
    private ArrivalDepartureTime departureTime;

    VolunteerBookingEditor(WorkingBooking workingBooking) {
        super(workingBooking, KnownItemFamily.ACCOMMODATION);
        workingBooking.enableDocumentChangesLog();
        PolicyAggregate policyAggregate = workingBooking.getPolicyAggregate();
        EntityStore entityStore = workingBooking.getDocument().getStore();
        // Hardcoded vegetarian and vegan items for MKMC (to improve later)
        dietFamily = entityStore.getOrCreateEntity(ItemFamily.class, KnownItemFamily.DIET.getPrimaryKey());
        dietFamily.setOrd(50);
        vegetarianItem = createDietItem("Vegetarian", 399);
        vegetarianWheatFreeItem = createDietItem("Vegetarian/Wheat-free", 400);
        veganItem = createDietItem("Vegan", 873);
        veganWheatFreeItem = createDietItem("Vegan/Wheat-free", 872);
        if (workingBooking.isNewBooking()) {
            Event event = policyAggregate.getEvent();
            setAttendanceDates(ScheduledItems.toDates(Collections.filter(getPolicyFamilyScheduledItems(),si -> Times.isBetween(si.getDate(), event.getStartDate(), event.getEndDate()))));
            arrivalTime = ArrivalDepartureTime.DINNER;
            departureTime = ArrivalDepartureTime.LUNCH;
            bookAccommodation(true);
            bookBreakfast(true);
            bookLunch(true);
            bookDinner(true);
            bookDiet(vegetarianItem);
        } else {
            setAttendanceDates(Attendances.toDates(workingBooking.getBookedAttendances()));
            arrivalTime = workingBooking.getArrivalTime();
            departureTime = workingBooking.getDepartureTime();
        }
        // Final subclasses should call this method
        initiateUiAndSyncFromWorkingBooking();
    }

    @Override
    public Node buildUi() {
        GridPane gridPane = new GridPane(50, 10);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHalignment(HPos.CENTER);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHalignment(HPos.CENTER);
        gridPane.getColumnConstraints().setAll(
            new ColumnConstraints(), // Options
            c1, // Arrival time
            c2  // Departure time
        );
        gridPane.add(Bootstrap.strong(new Label("Options")), 0, 0);
        gridPane.add(Bootstrap.strong(new Label("Arrival time")), 1, 0);
        gridPane.add(Bootstrap.strong(new Label("Departure time")), 2, 0);
        gridPane.add(breakfastCheckBox, 0, 1);
        gridPane.add(arrivalBreakfastRadioButton, 1, 1);
        gridPane.add(departureBreakfastRadioButton, 2, 1);
        gridPane.add(lunchCheckBox, 0, 2);
        gridPane.add(arrivalLunchRadioButton, 1, 2);
        gridPane.add(departureLunchRadioButton, 2, 2);
        gridPane.add(dinnerCheckBox, 0, 3);
        gridPane.add(arrivalDinnerRadioButton, 1, 3);
        gridPane.add(departureDinnerRadioButton, 2, 3);
        gridPane.add(accommodationCheckBox, 0, 4);
        gridPane.add(arrivalAccommodationRadioButton, 1, 4);
        gridPane.add(departureAccommodationRadioButton, 2, 4);
        VBox vBox = new VBox(10,
            // We make the day texts bold in the box selector. Note: works on the web but not on OpenJFX
            Bootstrap.strong(boxScheduledItemsSelector.buildUi()),
            gridPane,
            new HBox(20,
                vegetarianDietRadioButton,
                vegetarianWheatFreeDietRadioButton,
                veganDietRadioButton,
                veganWheatFreeDietRadioButton
            )
        );
        if (workingBooking.isNewBooking())
            vBox.getChildren().add(new VBox(5, new Label("Request"), requestTextArea));
        return embedInFamilyFrame(vBox);
    }

    @Override
    protected void initiateUiAndSyncFromWorkingBooking() {
        boxScheduledItemsSelector.setSelectableScheduledItems(getPolicyFamilyScheduledItems(), false);
        boxScheduledItemsSelector.getSelectedDates().setAll(attendanceDates);
        // We keep the working booking in sync with the selected dates - this keeps hasChangesProperty up to date in
        // WorkingBookingProperties which is used to reflect the user changes and enable the Save button.
        ObservableLists.runOnListChange(ignored ->
                syncWorkingBookingFromUi()
            , boxScheduledItemsSelector.getSelectedDates());
        breakfastCheckBox.setSelected(workingBooking.isBreakfastBooked());
        FXProperties.runOnPropertyChange(this::bookBreakfast, breakfastCheckBox.selectedProperty());
        lunchCheckBox.setSelected(workingBooking.isLunchBooked());
        FXProperties.runOnPropertyChange(this::bookLunch, lunchCheckBox.selectedProperty());
        dinnerCheckBox.setSelected(workingBooking.isDinnerBooked());
        FXProperties.runOnPropertyChange(this::bookDinner, dinnerCheckBox.selectedProperty());
        accommodationCheckBox.setSelected(workingBooking.isAccommodationBooked());
        FXProperties.runOnPropertyChange(this::bookAccommodation, accommodationCheckBox.selectedProperty());
        arrivalBreakfastRadioButton.setToggleGroup(arrivalToggleGroup);
        arrivalLunchRadioButton.setToggleGroup(arrivalToggleGroup);
        arrivalDinnerRadioButton.setToggleGroup(arrivalToggleGroup);
        arrivalAccommodationRadioButton.setToggleGroup(arrivalToggleGroup);
        switch (arrivalTime) {
            case BREAKFAST: arrivalBreakfastRadioButton.setSelected(true); break;
            case LUNCH: arrivalLunchRadioButton.setSelected(true); break;
            case DINNER: arrivalDinnerRadioButton.setSelected(true); break;
            case ACCOMMODATION: arrivalAccommodationRadioButton.setSelected(true); break;
        }
        FXProperties.runOnPropertyChange(this::syncArrivalBoundaryFromUi, arrivalToggleGroup.selectedToggleProperty());
        departureBreakfastRadioButton.setToggleGroup(departureToggleGroup);
        departureLunchRadioButton.setToggleGroup(departureToggleGroup);
        departureDinnerRadioButton.setToggleGroup(departureToggleGroup);
        departureAccommodationRadioButton.setToggleGroup(departureToggleGroup);
        switch (departureTime) {
            case BREAKFAST: departureBreakfastRadioButton.setSelected(true); break;
            case LUNCH: departureLunchRadioButton.setSelected(true); break;
            case DINNER: departureDinnerRadioButton.setSelected(true); break;
            case ACCOMMODATION: departureAccommodationRadioButton.setSelected(true); break;
        }
        FXProperties.runOnPropertyChange(this::syncDepartureBoundaryFromUi, departureToggleGroup.selectedToggleProperty());
        vegetarianDietRadioButton.setToggleGroup(dietToggleGroup);
        vegetarianWheatFreeDietRadioButton.setToggleGroup(dietToggleGroup);
        veganDietRadioButton.setToggleGroup(dietToggleGroup);
        veganWheatFreeDietRadioButton.setToggleGroup(dietToggleGroup);
        if (workingBooking.isItemBooked(veganItem)) veganDietRadioButton.setSelected(true);
        else if (workingBooking.isItemBooked(vegetarianWheatFreeItem)) vegetarianWheatFreeDietRadioButton.setSelected(true);
        else {
            if (workingBooking.isItemBooked(veganWheatFreeItem)) veganWheatFreeDietRadioButton.setSelected(true);
            else vegetarianDietRadioButton.setSelected(true);
        }
        FXProperties.runOnPropertyChange(this::syncDietFromUi, dietToggleGroup.selectedToggleProperty());
    }

    private void syncArrivalBoundaryFromUi() {
        if (arrivalBreakfastRadioButton.isSelected()) arrivalTime = ArrivalDepartureTime.BREAKFAST;
        if (arrivalLunchRadioButton.isSelected()) arrivalTime = ArrivalDepartureTime.LUNCH;
        if (arrivalDinnerRadioButton.isSelected()) arrivalTime = ArrivalDepartureTime.DINNER;
        if (arrivalAccommodationRadioButton.isSelected()) arrivalTime = ArrivalDepartureTime.ACCOMMODATION;
        syncWorkingBookingFromUi();
    }

    private void syncDepartureBoundaryFromUi() {
        if (departureBreakfastRadioButton.isSelected()) departureTime = ArrivalDepartureTime.BREAKFAST;
        if (departureLunchRadioButton.isSelected()) departureTime = ArrivalDepartureTime.LUNCH;
        if (departureDinnerRadioButton.isSelected()) departureTime = ArrivalDepartureTime.DINNER;
        if (departureAccommodationRadioButton.isSelected()) departureTime = ArrivalDepartureTime.ACCOMMODATION;
        syncWorkingBookingFromUi();
    }

    @Override
    public void syncWorkingBookingFromUi() {
        setAttendanceDates(boxScheduledItemsSelector.getSelectedDates());
        bookAccommodation(accommodationCheckBox.isSelected());
        bookBreakfast(breakfastCheckBox.isSelected());
        bookLunch(lunchCheckBox.isSelected());
        bookDinner(dinnerCheckBox.isSelected());
        bookDiet(getSelectedDietItem());
        if (workingBooking.isNewBooking()) {
            String request = requestTextArea.getText().trim();
            if (!request.isEmpty())
                workingBooking.addRequest(request);
        }
    }

    private void setAttendanceDates(List<LocalDate> newDates) {
        Collections.setAll(attendanceDates, newDates);
        attendanceDates.sort(LocalDate::compareTo);
    }

     private void bookAccommodation(boolean book) {
        List<ScheduledItem> dormitoryScheduledItems = getPolicyFamilyScheduledItems();
        workingBooking.bookAccommodation(book, dormitoryScheduledItems, attendanceDates, arrivalTime, departureTime);
    }

    private void bookBreakfast(boolean book) {
        workingBooking.bookBreakfast(book, attendanceDates, arrivalTime);
    }

    private void bookLunch(boolean book) {
        workingBooking.bookLunch(book, attendanceDates, arrivalTime, departureTime);
    }

    private void bookDinner(boolean book) {
        workingBooking.bookDinner(book, attendanceDates, arrivalTime, departureTime);
    }

    private Item getSelectedDietItem() {
        if (veganDietRadioButton.isSelected()) return veganItem;
        if (vegetarianWheatFreeDietRadioButton.isSelected()) return vegetarianWheatFreeItem;
        if (veganWheatFreeDietRadioButton.isSelected()) return veganWheatFreeItem;
        return vegetarianItem;
    }

    private void syncDietFromUi() {
        bookDiet(getSelectedDietItem());
    }

    private void bookDiet(Item selectedDietItem) {
        boolean anyMealsBooked = workingBooking.isAnyMealsBooked();
        workingBooking.bookDiet(anyMealsBooked && Entities.samePrimaryKey(selectedDietItem, vegetarianItem), vegetarianItem);
        workingBooking.bookDiet(anyMealsBooked && Entities.samePrimaryKey(selectedDietItem, vegetarianWheatFreeItem), vegetarianWheatFreeItem);
        workingBooking.bookDiet(anyMealsBooked && Entities.samePrimaryKey(selectedDietItem, veganItem), veganItem);
        workingBooking.bookDiet(anyMealsBooked && Entities.samePrimaryKey(selectedDietItem, veganWheatFreeItem), veganWheatFreeItem);
    }

    private Item createDietItem(String name, Object primaryKey) {
        EntityStore store = dietFamily.getStore();
        Item dietOption = store.getOrCreateEntity(Item.class, primaryKey);
        dietOption.setName(name);
        dietOption.setFamily(dietFamily);
        dietOption.setTemporal(false);
        dietOption.setOrd(0);
        return dietOption;
    }

}



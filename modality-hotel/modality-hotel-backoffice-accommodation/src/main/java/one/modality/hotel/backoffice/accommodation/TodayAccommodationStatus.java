package one.modality.hotel.backoffice.accommodation;

import dev.webfx.extras.time.format.LocalizedTime;
import dev.webfx.kit.util.properties.FXProperties;
import dev.webfx.kit.util.properties.ObservableLists;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import one.modality.base.client.gantt.fx.today.FXToday;
import one.modality.base.client.time.BackOfficeTimeFormats;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Bruno Salmon
 */
public final class TodayAccommodationStatus {

    private final TodayResourceDayLoader todayResourceDayLoader;

    public TodayAccommodationStatus(AccommodationPresentationModel pm) {
        todayResourceDayLoader = TodayResourceDayLoader.getOrCreate(pm);
    }

    public void startLogic(Object mixin) {
        todayResourceDayLoader.startLogic(mixin);
    }

    private ObservableList<ResourceDay> todayResourceDays() {
        return todayResourceDayLoader.getTodayResourceDays();
    }

    private long countRoomsOccupied() {
        return todayResourceDays().stream()
                .filter(resourceDay -> resourceDay.getBooked() > 0)
                .count();
    }

    private long countAllRooms() {
        return todayResourceDays().size();
    }

    private int countAllBeds() {
        return todayResourceDays().stream()
                .mapToInt(ResourceDay::getMax)
                .sum();
    }

    private long countBedsAvailable() {
        return countAllBeds() - countGuests();
    }

    private long countGuests() {
        return todayResourceDays().stream()
                .mapToInt(ResourceDay::getBooked)
                .sum();
    }

    public GridPane createStatusBar() {
        GridPane statusBar = new GridPane();
        statusBar.setAlignment(Pos.CENTER); // Makes a difference for the Web version (otherwise children appears on top)
        FXProperties.runNowAndOnPropertyChange(() -> updateStatusBar(statusBar), FXToday.todayProperty());
        ObservableLists.runOnListChange(c -> updateStatusBar(statusBar), todayResourceDays());
        return statusBar;
    }

    private void updateStatusBar(GridPane statusBar) {
        long numRoomsOccupied = countRoomsOccupied();
        long numRoomsAvailable = countAllRooms() - numRoomsOccupied;
        int numBeds = countAllBeds();
        long numBedsAvailable = countBedsAvailable();
        long numBedsOccupied = numBeds - numBedsAvailable;
        long numGuests = countGuests();

        statusBar.getChildren().clear();
        statusBar.add(buildLabel("Status today " + formatToday()), 0, 0);
        statusBar.add(buildLabel("Rooms occupied: " + numRoomsOccupied), 1, 0);
        statusBar.add(buildLabel("Rooms available: " + numRoomsAvailable), 2, 0);
        statusBar.add(buildLabel("Beds occupied: " + numBedsOccupied), 3, 0);
        statusBar.add(buildLabel("Beds available: " + numBedsAvailable), 4, 0);
        statusBar.add(buildLabel("Guests: " + numGuests), 5, 0);

        updateColumnWidths(statusBar);
    }

    private static Label buildLabel(String text) {
        Label label = new Label(text);
        GridPane.setHalignment(label, HPos.CENTER);
        return label;
    }

    private static String formatToday() {
        return LocalizedTime.formatLocalDate(FXToday.getToday(), BackOfficeTimeFormats.ACCOMMODATION_STATUS_DATE_FORMAT);
    }

    private static void updateColumnWidths(GridPane statusBar) {
        int numColumns = statusBar.getColumnCount();
        double columnPercentageWidth = 100.0 / numColumns;
        double percentageRemaining = 100.0;
        List<ColumnConstraints> columnConstraints = new ArrayList<>(numColumns);
        for (int i = 0; i < numColumns - 1; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(columnPercentageWidth);
            columnConstraints.add(column);
            percentageRemaining -= columnPercentageWidth;
        }
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(percentageRemaining);
        columnConstraints.add(column);
        statusBar.getColumnConstraints().setAll(columnConstraints);
    }

}

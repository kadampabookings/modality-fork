package one.modality.hotel.backoffice.accommodation;

import dev.webfx.extras.time.layout.bar.TimeBarUtil;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;
import one.modality.base.shared.entities.ResourceConfiguration;

/**
 * @author Bruno Salmon
 */
public abstract class ResourceDayGantt extends AccommodationGantt<ResourceDayBlock> {

    // The user has the option to enable/disable the blocks grouping (when disabled, TimeBarUtil will not group the
    // blocks, but simply map each block to a 1-day-long bar, so the user will see all these blocks)
    private final BooleanProperty blocksGroupingProperty = new SimpleBooleanProperty();

    public ResourceDayGantt(AccommodationPresentationModel pm, ObservableList<ResourceDay> resourceDays, ObservableList<ResourceConfiguration> providedParentRooms) {
        super(pm, null, providedParentRooms, 13);
        TimeBarUtil.convertToBlocksThenGroupToBars(
                resourceDays, // the observable list of ResourceDay cells (config × date) to take as input
                ResourceDay::getDate, // the date reader that will be used to date each block
                ResourceDayBlock::new, // the factory that creates blocks, initially 1 instance per cell, but then grouped into bars
                ganttLayout.getChildren(), // the final list of bars that will receive the result of grouping blocks
                blocksGroupingProperty); // optional property to eventually disable the blocks grouping (=> 1 bar per block if disabled)
        ganttLayout.setChildFixedHeight(40);
        parentsCanvasDrawer
                .setHorizontalStroke(Color.BLACK)
                .setVerticalStroke(Color.BLACK);
    }

    public BooleanProperty blocksGroupingProperty() {
        return blocksGroupingProperty;
    }
}

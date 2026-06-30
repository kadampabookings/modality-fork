package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import one.modality.base.shared.entities.markers.EntityHasDocumentLine;
import one.modality.base.shared.entities.markers.EntityHasLocalDate;
import one.modality.base.shared.entities.markers.EntityHasScheduledItem;

/**
 * @author Bruno Salmon
 */
public interface Attendance extends Entity,
    EntityHasDocumentLine,
    EntityHasLocalDate,
    EntityHasScheduledItem {

    String attended = "attended";
    String videoAccessEnabled = "videoAccessEnabled";

    default Boolean isAttended() {
        return getBooleanFieldValue(attended);
    }

    default void setAttended(Boolean value) {
        setFieldValue(attended, value);
    }

    default Boolean isVideoAccessEnabled() {
        return getBooleanFieldValue(videoAccessEnabled);
    }

    default void setVideoAccessEnabled(Boolean value) {
        setFieldValue(videoAccessEnabled, value);
    }

}
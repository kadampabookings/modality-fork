package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

import java.time.LocalDate;

/**
 * Special staffing need for a given date range and work area.
 * Used to flag periods requiring extra volunteers beyond the baseline.
 */
public interface VolunteeringSpecialNeeds extends Entity, EntityHasOrganization {

    String area = "area";
    String startDate = "startDate";
    String endDate = "endDate";
    String extraCount = "extraCount";
    String reason = "reason";
    String priority = "priority";
    String removed = "removed";

    default void setArea(Object value) { setForeignField(area, value); }
    default EntityId getAreaId() { return getForeignEntityId(area); }
    default VolunteeringArea getArea() { return getForeignEntity(area); }

    default void setStartDate(LocalDate value) { setFieldValue(startDate, value); }
    default LocalDate getStartDate() { return getLocalDateFieldValue(startDate); }

    default void setEndDate(LocalDate value) { setFieldValue(endDate, value); }
    default LocalDate getEndDate() { return getLocalDateFieldValue(endDate); }

    default void setExtraCount(Integer value) { setFieldValue(extraCount, value); }
    default Integer getExtraCount() { return getIntegerFieldValue(extraCount); }

    default void setReason(String value) { setFieldValue(reason, value); }
    default String getReason() { return getStringFieldValue(reason); }

    default void setPriority(String value) { setFieldValue(priority, value); }
    default String getPriority() { return getStringFieldValue(priority); }

    default void setRemoved(Boolean value) { setFieldValue(removed, value); }
    default Boolean isRemoved() { return getBooleanFieldValue(removed); }
}

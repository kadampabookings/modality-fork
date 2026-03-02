package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

import java.time.LocalDate;

/**
 * Links a volunteer application to a work area for a specific date range.
 * A volunteer can have multiple assignments (different areas, different dates).
 */
public interface VolunteeringAssignment extends Entity, EntityHasOrganization {

    String application = "application";
    String area = "area";
    String startDate = "startDate";
    String endDate = "endDate";

    default void setApplication(Object value) { setForeignField(application, value); }
    default EntityId getApplicationId() { return getForeignEntityId(application); }
    default VolunteeringApplication getApplication() { return getForeignEntity(application); }

    default void setArea(Object value) { setForeignField(area, value); }
    default EntityId getAreaId() { return getForeignEntityId(area); }
    default VolunteeringArea getArea() { return getForeignEntity(area); }

    default void setStartDate(LocalDate value) { setFieldValue(startDate, value); }
    default LocalDate getStartDate() { return getLocalDateFieldValue(startDate); }

    default void setEndDate(LocalDate value) { setFieldValue(endDate, value); }
    default LocalDate getEndDate() { return getLocalDateFieldValue(endDate); }

    String removed = "removed";
    default void setRemoved(Boolean value) { setFieldValue(removed, value); }
    default Boolean isRemoved() { return getBooleanFieldValue(removed); }
}

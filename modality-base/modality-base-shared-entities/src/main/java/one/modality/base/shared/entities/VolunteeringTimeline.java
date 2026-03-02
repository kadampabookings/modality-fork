package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

import java.time.LocalDate;

/**
 * Activity event log entry for a volunteer application.
 * Records status changes, actions, and other notable events.
 */
public interface VolunteeringTimeline extends Entity, EntityHasOrganization {

    String application = "application";
    String date = "date";
    String event = "event";
    String status = "status";
    String user = "user";

    default void setApplication(Object value) { setForeignField(application, value); }
    default EntityId getApplicationId() { return getForeignEntityId(application); }
    default VolunteeringApplication getApplication() { return getForeignEntity(application); }

    default void setDate(LocalDate value) { setFieldValue(date, value); }
    default LocalDate getDate() { return getLocalDateFieldValue(date); }

    default void setEvent(String value) { setFieldValue(event, value); }
    default String getEvent() { return getStringFieldValue(event); }

    default void setStatus(String value) { setFieldValue(status, value); }
    default String getStatus() { return getStringFieldValue(status); }

    default void setUser(String value) { setFieldValue(user, value); }
    default String getUser() { return getStringFieldValue(user); }
}

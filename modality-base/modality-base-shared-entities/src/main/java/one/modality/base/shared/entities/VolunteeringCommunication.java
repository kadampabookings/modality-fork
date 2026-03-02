package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

import java.time.LocalDate;

/**
 * Communication log entry for a volunteer application.
 * Records emails, phone calls, in-person meetings, and coordinator notes.
 */
public interface VolunteeringCommunication extends Entity, EntityHasOrganization {

    String application = "application";
    String date = "date";
    String type = "type";
    String direction = "direction";
    String subject = "subject";
    String content = "content";
    String user = "user";
    String removed = "removed";

    default void setApplication(Object value) { setForeignField(application, value); }
    default EntityId getApplicationId() { return getForeignEntityId(application); }
    default VolunteeringApplication getApplication() { return getForeignEntity(application); }

    default void setDate(LocalDate value) { setFieldValue(date, value); }
    default LocalDate getDate() { return getLocalDateFieldValue(date); }

    default void setType(String value) { setFieldValue(type, value); }
    default String getType() { return getStringFieldValue(type); }

    default void setDirection(String value) { setFieldValue(direction, value); }
    default String getDirection() { return getStringFieldValue(direction); }

    default void setSubject(String value) { setFieldValue(subject, value); }
    default String getSubject() { return getStringFieldValue(subject); }

    default void setContent(String value) { setFieldValue(content, value); }
    default String getContent() { return getStringFieldValue(content); }

    default void setUser(String value) { setFieldValue(user, value); }
    default String getUser() { return getStringFieldValue(user); }

    default void setRemoved(Boolean value) { setFieldValue(removed, value); }
    default Boolean isRemoved() { return getBooleanFieldValue(removed); }
}

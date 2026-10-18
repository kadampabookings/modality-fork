package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;

import java.time.LocalDate;

/**
 * Entity representing user access authorization within an organization.
 * Links a user to a role within an organization, optionally scoped to a specific event or to every
 * event of one type (V0133). At most one of the two scope keys is set — a database CHECK
 * (access_one_scope_key) refuses a row carrying both, since the membership queries read such a row as
 * neither "that event as well" nor "that event only if of that type".
 *
 * @author Claude Code
 */
public interface AuthorizationOrganizationUserAccess extends Entity {

    // Field name constants
    String ORGANIZATION = "organization";
    String USER = "user";
    String EVENT = "event";
    String EVENT_TYPE = "eventType";
    String ROLE = "role";
    String READ_ONLY = "readOnly";
    String DATE = "date";

    // Organization (required)

    default void setOrganization(Object value) {
        setForeignField(ORGANIZATION, value);
    }

    default EntityId getOrganizationId() {
        return getForeignEntityId(ORGANIZATION);
    }

    default Organization getOrganization() {
        return getForeignEntity(ORGANIZATION);
    }

    // User (required)

    default void setUser(Object value) {
        setForeignField(USER, value);
    }

    default EntityId getUserId() {
        return getForeignEntityId(USER);
    }

    default Person getUser() {
        return getForeignEntity(USER);
    }

    // Event (optional - if null, access applies to entire organization)

    default void setEvent(Object value) {
        setForeignField(EVENT, value);
    }

    default EntityId getEventId() {
        return getForeignEntityId(EVENT);
    }

    default Event getEvent() {
        return getForeignEntity(EVENT);
    }

    // Event type (optional - every event of this type, the level between the organization and one
    // event). Mutually exclusive with EVENT; see the class comment.

    default void setEventType(Object value) {
        setForeignField(EVENT_TYPE, value);
    }

    default EntityId getEventTypeId() {
        return getForeignEntityId(EVENT_TYPE);
    }

    default EventType getEventType() {
        return getForeignEntity(EVENT_TYPE);
    }

    // Role (required)

    default void setRole(Object value) {
        setForeignField(ROLE, value);
    }

    default EntityId getRoleId() {
        return getForeignEntityId(ROLE);
    }

    default AuthorizationRole getRole() {
        return getForeignEntity(ROLE);
    }

    // Read Only flag (required, defaults to false)

    default void setReadOnly(Boolean readOnly) {
        setFieldValue(READ_ONLY, readOnly);
    }

    default Boolean isReadOnly() {
        return getBooleanFieldValue(READ_ONLY);
    }

    // Date (auto-populated on insert)

    default void setDate(LocalDate date) {
        setFieldValue(DATE, date);
    }

    default LocalDate getDate() {
        return getLocalDateFieldValue(DATE);
    }
}

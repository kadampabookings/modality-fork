package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import one.modality.base.shared.entities.markers.EntityHasEvent;
import one.modality.base.shared.entities.markers.EntityHasPerson;

import java.time.Instant;

/**
 * Liveness heartbeat for the chat. One row per (person, event), re-touched
 * (never deleted) every ~30s by the backoffice Support-chats page while an
 * agent has it open. The frontoffice widget subscribes to the event's rows
 * and derives "team online" from heartbeats fresher than a staleness window
 * — replacing the localStorage hack of the design prototype.
 *
 * <p>A null {@code event} is reserved for future organization-wide (staff
 * chat) presence.
 *
 * @author Bruno Salmon
 */
public interface ChatPresence extends Entity, EntityHasEvent, EntityHasPerson {

    String lastSeenAt = "lastSeenAt";

    default void setLastSeenAt(Instant value) {
        setFieldValue(lastSeenAt, value);
    }

    default Instant getLastSeenAt() {
        return getInstantFieldValue(lastSeenAt);
    }
}

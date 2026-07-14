package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import one.modality.base.shared.entities.markers.EntityHasEvent;
import one.modality.base.shared.entities.markers.EntityHasPerson;

import java.time.Instant;

/**
 * Liveness heartbeat for the chat. One row per (person, event, agent),
 * re-touched (never deleted) every ~30s — by the backoffice Support-chats
 * page for agents ({@code agent=true}) and by the frontoffice livestream
 * page for viewers ({@code agent=false}). Each surface reads the OTHER
 * role's rows and derives onlineness from heartbeats fresher than a
 * staleness window: the frontoffice widget's "team online" pill from agent
 * rows, the backoffice inbox's viewer dot from viewer rows. The role flag
 * is part of the unique key because the same person can hold both rows at
 * once (an agent watching the stream while working the inbox).
 *
 * <p>A null {@code event} is reserved for future organization-wide (staff
 * chat) presence.
 *
 * @author Bruno Salmon
 */
public interface ChatPresence extends Entity, EntityHasEvent, EntityHasPerson {

    String lastSeenAt = "lastSeenAt";
    String agent = "agent";

    default void setLastSeenAt(Instant value) {
        setFieldValue(lastSeenAt, value);
    }

    default Instant getLastSeenAt() {
        return getInstantFieldValue(lastSeenAt);
    }

    default void setAgent(Boolean value) {
        setFieldValue(agent, value);
    }

    default Boolean isAgent() {
        return getBooleanFieldValue(agent);
    }
}

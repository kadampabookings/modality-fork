package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasPerson;

import java.time.Instant;

/**
 * One person's emoji reaction to one {@link ChatMessage}.
 *
 * <p>A row per (message, person, emoji), unique on that triple: reacting
 * inserts, un-reacting deletes, and a repeated tap is idempotent rather than
 * a duplicate. Unlike {@link ChatMessage} these rows ARE deleted — that is
 * the un-react — so nothing here may be treated as an audit trail.
 *
 * <p>Deliberately not part of the message stream: reactions must not touch
 * unread counts, inbox previews or the offline-notification emails, all of
 * which are derived from messages. Both surfaces aggregate the counts
 * client-side from their push subscription; the server maintains no total.
 *
 * <p>{@code emoji} stores the character itself rather than a code, so old
 * rows stay readable however the clients' palette evolves.
 *
 * @author Bruno Salmon
 */
public interface ChatMessageReaction extends Entity, EntityHasPerson {

    String message = "message";
    String emoji = "emoji";
    String createdAt = "createdAt";

    default void setMessage(Object value) {
        setForeignField(message, value);
    }

    default EntityId getMessageId() {
        return getForeignEntityId(message);
    }

    default ChatMessage getMessage() {
        return getForeignEntity(message);
    }

    default void setEmoji(String value) {
        setFieldValue(emoji, value);
    }

    default String getEmoji() {
        return getStringFieldValue(emoji);
    }

    default void setCreatedAt(Instant value) {
        setFieldValue(createdAt, value);
    }

    default Instant getCreatedAt() {
        return getInstantFieldValue(createdAt);
    }
}

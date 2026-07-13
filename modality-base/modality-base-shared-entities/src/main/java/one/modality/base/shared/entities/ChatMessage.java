package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasPerson;

import java.time.Instant;

/**
 * One message in a {@link Conversation}. Append-only — messages are never
 * edited or deleted by the chat surfaces (retention/cleanup is a separate
 * back-office concern).
 *
 * <p>{@code person} is the author; a null person with {@code kind='system'}
 * is a system line (context attached / assigned / resolved ...) rendered
 * centered in both threads. Message text is plain text — clients must render
 * it escaped, never as HTML.
 *
 * @author Bruno Salmon
 */
public interface ChatMessage extends Entity, EntityHasPerson {

    String conversation = "conversation";
    String kind = "kind";
    String content = "content";
    String createdAt = "createdAt";

    // kind values
    String KIND_TEXT = "text";
    String KIND_SYSTEM = "system";

    default void setConversation(Object value) {
        setForeignField(conversation, value);
    }

    default EntityId getConversationId() {
        return getForeignEntityId(conversation);
    }

    default Conversation getConversation() {
        return getForeignEntity(conversation);
    }

    default void setKind(String value) {
        setFieldValue(kind, value);
    }

    default String getKind() {
        return getStringFieldValue(kind);
    }

    default void setContent(String value) {
        setFieldValue(content, value);
    }

    default String getContent() {
        return getStringFieldValue(content);
    }

    default void setCreatedAt(Instant value) {
        setFieldValue(createdAt, value);
    }

    default Instant getCreatedAt() {
        return getInstantFieldValue(createdAt);
    }
}

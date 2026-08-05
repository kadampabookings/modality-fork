package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasPerson;

import java.time.Instant;

/**
 * One message in a {@link Conversation}. Append-mostly — messages are never
 * deleted by the chat surfaces (retention/cleanup is a separate back-office
 * concern); the only in-place update is a back-office agent correcting
 * their OWN text message, which stamps {@code editedAt} so both threads
 * render an "(edited)" marker instead of silently swapping text the other
 * side may already have read. {@code editedAt} is display-only (stamped by
 * the editing client) — never compare it against {@code createdAt} or the
 * participants' read cursors.
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
    String editedAt = "editedAt";
    String replyTo = "replyTo";

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

    /**
     * The message this one answers (quote-reply), or null — which is the
     * case for every message written before the feature existed. Points
     * within the same conversation; the quoted message is never deleted,
     * so a reply's context cannot go missing.
     */
    default void setReplyTo(Object value) {
        setForeignField(replyTo, value);
    }

    default EntityId getReplyToId() {
        return getForeignEntityId(replyTo);
    }

    default ChatMessage getReplyTo() {
        return getForeignEntity(replyTo);
    }

    default void setEditedAt(Instant value) {
        setFieldValue(editedAt, value);
    }

    default Instant getEditedAt() {
        return getInstantFieldValue(editedAt);
    }
}

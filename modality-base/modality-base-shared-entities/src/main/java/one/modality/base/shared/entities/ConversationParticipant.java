package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasPerson;

import java.time.Instant;

/**
 * Membership + read state of one person in one {@link Conversation}.
 *
 * <p>For support conversations the viewer row is inserted at creation and an
 * agent row on assignment / first reply; for future staff conversations every
 * member gets a row. {@link #lastReadAt} is the person's read cursor —
 * messages created after it are unread for them; each client updates its own
 * row on open/focus.
 *
 * <p>This table is also the intended anchor for the frontoffice read-scoping
 * query interceptor ("a person may read a conversation iff they participate
 * in it").
 *
 * @author Bruno Salmon
 */
public interface ConversationParticipant extends Entity, EntityHasPerson {

    String conversation = "conversation";
    String role = "role";
    String joinedAt = "joinedAt";
    String lastReadAt = "lastReadAt";

    // role values
    String ROLE_VIEWER = "viewer";
    String ROLE_AGENT = "agent";
    String ROLE_MEMBER = "member";

    default void setConversation(Object value) {
        setForeignField(conversation, value);
    }

    default EntityId getConversationId() {
        return getForeignEntityId(conversation);
    }

    default Conversation getConversation() {
        return getForeignEntity(conversation);
    }

    default void setRole(String value) {
        setFieldValue(role, value);
    }

    default String getRole() {
        return getStringFieldValue(role);
    }

    default void setJoinedAt(Instant value) {
        setFieldValue(joinedAt, value);
    }

    default Instant getJoinedAt() {
        return getInstantFieldValue(joinedAt);
    }

    default void setLastReadAt(Instant value) {
        setFieldValue(lastReadAt, value);
    }

    default Instant getLastReadAt() {
        return getInstantFieldValue(lastReadAt);
    }
}

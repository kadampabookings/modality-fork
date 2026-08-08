package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasDocument;
import one.modality.base.shared.entities.markers.EntityHasEvent;
import one.modality.base.shared.entities.markers.EntityHasOrganization;
import one.modality.base.shared.entities.markers.EntityHasScheduledItem;

import java.time.Instant;

/**
 * A chat thread. One shared model for every chat surface, discriminated by
 * {@link #type}:
 * <ul>
 *   <li>{@code support} — a viewer ↔ event-team conversation opened from the
 *   frontoffice livestream page (replaces the external tawk.to chat). Scoped
 *   to an event (+ session), owned by {@link #viewerPerson}, and driven
 *   through the queue lifecycle {@code queued → open → closed} (or
 *   {@code message} for the leave-a-message fallback when no agent is
 *   online) by the backoffice Support-chats page.</li>
 *   <li>{@code staff} — reserved for the future internal staff chat
 *   (organization-scoped, membership via {@link ConversationParticipant},
 *   named by {@link #title}; the support lifecycle columns stay null).</li>
 * </ul>
 *
 * <p>Messages live in {@link ChatMessage}; membership and per-person read
 * cursors in {@link ConversationParticipant}. There are deliberately no
 * server-maintained aggregates (unread counts, last-message-at) — both
 * surfaces derive them client-side from push-mode subscriptions, the same
 * pattern the support wizard uses over {@link SupportReport}.
 *
 * @author Bruno Salmon
 */
public interface Conversation extends Entity, EntityHasDocument, EntityHasEvent, EntityHasOrganization, EntityHasScheduledItem {

    String type = "type";
    String issue = "issue";
    String viewerPerson = "viewerPerson";
    String status = "status";
    String assignee = "assignee";
    String title = "title";
    String viewerDevice = "viewerDevice";
    String needsFix = "needsFix";
    String devIssue = "devIssue";
    String viewerLang = "viewerLang";
    String createdAt = "createdAt";
    String closedAt = "closedAt";

    // type discriminator values
    String TYPE_SUPPORT = "support";
    String TYPE_STAFF = "staff";

    // status lifecycle values (support conversations only)
    String STATUS_QUEUED = "queued";
    String STATUS_OPEN = "open";
    String STATUS_CLOSED = "closed";
    String STATUS_MESSAGE = "message";

    default void setType(String value) {
        setFieldValue(type, value);
    }

    default String getType() {
        return getStringFieldValue(type);
    }

    default void setIssue(Object value) {
        setForeignField(issue, value);
    }

    default EntityId getIssueId() {
        return getForeignEntityId(issue);
    }

    default Issue getIssue() {
        return getForeignEntity(issue);
    }

    default void setViewerPerson(Object value) {
        setForeignField(viewerPerson, value);
    }

    default EntityId getViewerPersonId() {
        return getForeignEntityId(viewerPerson);
    }

    default Person getViewerPerson() {
        return getForeignEntity(viewerPerson);
    }

    default void setStatus(String value) {
        setFieldValue(status, value);
    }

    default String getStatus() {
        return getStringFieldValue(status);
    }

    default void setAssignee(Object value) {
        setForeignField(assignee, value);
    }

    default EntityId getAssigneeId() {
        return getForeignEntityId(assignee);
    }

    default Person getAssignee() {
        return getForeignEntity(assignee);
    }

    default void setTitle(String value) {
        setFieldValue(title, value);
    }

    default String getTitle() {
        return getStringFieldValue(title);
    }

    default void setViewerDevice(String value) {
        setFieldValue(viewerDevice, value);
    }

    default String getViewerDevice() {
        return getStringFieldValue(viewerDevice);
    }

    default void setViewerLang(String value) {
        setFieldValue(viewerLang, value);
    }

    default String getViewerLang() {
        return getStringFieldValue(viewerLang);
    }

    default void setCreatedAt(Instant value) {
        setFieldValue(createdAt, value);
    }

    default Instant getCreatedAt() {
        return getInstantFieldValue(createdAt);
    }

    default void setClosedAt(Instant value) {
        setFieldValue(closedAt, value);
    }

    default Instant getClosedAt() {
        return getInstantFieldValue(closedAt);
    }

    /**
     * The agent's "this smells like something we must fix" flag, raised
     * before anyone knows WHICH dev issue it is — the triage queue.
     */
    default void setNeedsFix(Boolean value) {
        setFieldValue(needsFix, value);
    }

    default Boolean isNeedsFix() {
        return getBooleanFieldValue(needsFix);
    }

    /**
     * The dev issue this conversation turned out to be an instance of, or
     * null. MANY conversations point at ONE issue — the status lives
     * there, so marking it fixed cannot leave two conversations
     * disagreeing about the same problem.
     */
    default void setDevIssue(Object value) {
        setForeignField(devIssue, value);
    }

    default EntityId getDevIssueId() {
        return getForeignEntityId(devIssue);
    }

    default DevIssue getDevIssue() {
        return getForeignEntity(devIssue);
    }
}

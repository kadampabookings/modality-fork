package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasDocument;
import one.modality.base.shared.entities.markers.EntityHasEvent;
import one.modality.base.shared.entities.markers.EntityHasOrganization;
import one.modality.base.shared.entities.markers.EntityHasPerson;

import java.time.Instant;

/**
 * An opt-in association between a {@link PushSubscription} and a notification
 * context (booking, event, organization, etc.). A single subscription (one
 * device) can have many recipient rows — a user who books two events on the
 * same device has one PushSubscription and two PushSubscriptionRecipient rows.
 * <p>
 * The server's send-time query joins recipients to subscriptions to figure
 * out which devices to push to for a given context. Identity is keyed by the
 * combination of {@link #email} (always populated, even for guests) and the
 * relevant context FK ({@link #document}, {@link #event}, {@link #organization}).
 *
 * @author Bruno Salmon
 */
public interface PushSubscriptionRecipient extends Entity,
        EntityHasDocument,
        EntityHasEvent,
        EntityHasOrganization,
        EntityHasPerson {

    String subscription = "subscription";
    String context = "context";
    String email = "email";
    String createdAt = "createdAt";
    /**
     * Set to the moment the user clicked unsubscribe; null on active opt-ins.
     * Soft-delete rather than DELETE so we can track opt-out rates over time.
     * All send-side queries must filter {@code unsubscribedAt is null}.
     */
    String unsubscribedAt = "unsubscribedAt";

    default PushSubscription getSubscription() { return getForeignEntity(subscription); }
    default EntityId getSubscriptionId() { return getForeignEntityId(subscription); }
    default void setSubscription(Object value) { setForeignField(subscription, value); }

    default String getContext() { return getStringFieldValue(context); }
    default void setContext(String value) { setFieldValue(context, value); }

    default String getEmail() { return getStringFieldValue(email); }
    default void setEmail(String value) { setFieldValue(email, value); }

    default Instant getCreatedAt() { return getInstantFieldValue(createdAt); }

    default Instant getUnsubscribedAt() { return getInstantFieldValue(unsubscribedAt); }
    default void setUnsubscribedAt(Instant value) { setFieldValue(unsubscribedAt, value); }
}

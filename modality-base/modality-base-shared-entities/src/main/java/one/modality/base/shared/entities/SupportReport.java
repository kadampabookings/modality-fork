package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasEvent;
import one.modality.base.shared.entities.markers.EntityHasPerson;

import java.time.Instant;

/**
 * A single viewer's report that something is wrong with a live experience —
 * a livestream, the login flow, the audio library, etc. Designed as a
 * lightweight, append-mostly row: the wizard inserts one on report,
 * sets {@link #undoneAt} on undo, and the catalog counter is computed
 * client-side from the broadcast list of active (undoneAt-null) rows.
 *
 * <p>The {@code issue} foreign key points to a row in the {@link Issue}
 * catalog table, which itself carries the {@code module} discriminator
 * (livestream / login / audioLibrary / ...) and the issue's {@code code}.
 * The {@code event} / {@code scheduledItem} fields are optional — set
 * for livestream/audio contexts (scheduledItem identifies the specific
 * session being reported); other modules leave them null.
 *
 * @author Bruno Salmon
 */
public interface SupportReport extends Entity, EntityHasEvent, EntityHasPerson {

    String issue = "issue";
    String reportedAt = "reportedAt";
    String scheduledItem = "scheduledItem";
    String undoneAt = "undoneAt";

    default void setScheduledItem(Object value) {
        setForeignField(scheduledItem, value);
    }

    default EntityId getScheduledItemId() {
        return getForeignEntityId(scheduledItem);
    }

    default ScheduledItem getScheduledItem() {
        return getForeignEntity(scheduledItem);
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

    default void setReportedAt(Instant value) {
        setFieldValue(reportedAt, value);
    }

    default Instant getReportedAt() {
        return getInstantFieldValue(reportedAt);
    }

    default void setUndoneAt(Instant value) {
        setFieldValue(undoneAt, value);
    }

    default Instant getUndoneAt() {
        return getInstantFieldValue(undoneAt);
    }
}

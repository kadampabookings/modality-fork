package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import one.modality.base.shared.entities.markers.EntityHasEvent;
import one.modality.base.shared.entities.markers.EntityHasItem;
import one.modality.base.shared.entities.markers.EntityHasSite;

/**
 * The registration team's manual "sold out" override: the row's PRESENCE forces an item sold out for
 * an event, whatever its computed availability says. There is no boolean — toggling on inserts,
 * toggling off deletes.
 *
 * Operational state, deliberately not an ItemPolicy field. It has a different writer (registration,
 * not the admins who configure policy), a different lifecycle (flipped during the booking window,
 * not at setup), and a scope that is always event+item. It was also the only field registration ever
 * wrote — everything else in a policy is read-only to them.
 *
 * Keeping it on ItemPolicy actively blocked centralising policy into wider scopes: both the KBS3
 * hook and KBS2's ResourcesGraphicActivity find their rows with `where scope.event = ?`, so the
 * button only existed where an event-scoped ItemPolicy happened to exist, and removing those
 * duplicated rows made the control silently disappear.
 *
 * A null site means every site the item is offered at for the event.
 *
 * @author Bruno Salmon
 */
public interface SoldOutItem extends Entity,
    EntityHasEvent,
    EntityHasItem,
    EntityHasSite
{
}

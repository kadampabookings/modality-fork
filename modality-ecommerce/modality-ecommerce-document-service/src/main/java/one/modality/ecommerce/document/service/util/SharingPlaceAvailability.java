package one.modality.ecommerce.document.service.util;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Item;
import one.modality.base.shared.entities.ItemFamily;
import one.modality.base.shared.entities.ItemPolicy;
import one.modality.base.shared.entities.ScheduledItem;
import one.modality.ecommerce.policy.service.LoadPolicyArgument;
import one.modality.ecommerce.policy.service.PolicyAggregate;
import one.modality.ecommerce.policy.service.PolicyService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Whether a sharing accommodation option still has a bed to offer (room-mate plan §1c, step 6).
 *
 * <p>A sharing option has no inventory of its own: a sharer takes the free bed in a room someone they
 * know has booked. So its availability is derived — the beds still free in this event's booked rooms
 * of the types it pairs with (all types when it pairs with none), leaving out rooms whose ItemPolicy
 * sets {@code minOccupancy} 1 since their booker paid for the room to themselves, less every sharing place
 * in the event not linked to a room yet, each of which already claims one of those beds. That last figure is
 * event-wide because a sharing item often has no scheduled item of its own to carry a per-item one; it can
 * only err towards offering too little, never a bed twice.
 *
 * <p>The figures are {@link ScheduledItem#getFreeSharedBeds()} and {@link ScheduledItem#getPendingSharers()},
 * computed by the policy service. When they are missing (an older server) the answer is null and nobody is
 * refused. A room type with no capacity set is not a whole-room type and contributes no bed.
 *
 * <p>The booking form's TypeScript twin is {@code getSharingFreeBeds} in {@code policy-helpers.ts}:
 * keep the two in step, so the server never refuses what the card offered.
 */
public final class SharingPlaceAvailability {

    /** KnownItemFamily.ACCOMMODATION's code, which the policy's scheduled items load as item.family.code. */
    private static final String ACCOMMODATION_FAMILY_CODE = "acco";

    private SharingPlaceAvailability() {
    }

    /** What a sharing option offers: its free beds (null when unknown) and the room items those beds are in. */
    private record Offer(Integer freeBeds, List<Object> roomItemPks) {
    }

    /** Free beds a sharing option may take, or null when that cannot be known — callers must not refuse on a null. */
    public static Integer freeBedsFor(PolicyAggregate policy, ItemPolicy sharingItemPolicy) {
        return offerFor(policy, sharingItemPolicy).freeBeds();
    }

    private static Offer offerFor(PolicyAggregate policy, ItemPolicy sharingItemPolicy) {
        // A sharing option whose sharer brings the space -- a family tent, a campervan -- takes no bed
        // the event ever counted, so there is nothing to derive and nothing to refuse (V0103). Null is
        // what callers already read as "cannot be known", and they must not refuse on it.
        if (sharingItemPolicy != null && Boolean.TRUE.equals(sharingItemPolicy.isSharingNeedsNoBed()))
            return new Offer(null, new ArrayList<>());
        // The free-bed figure is per room item and identical on every date of it, so one row per item is enough.
        Map<Object, ScheduledItem> roomRowByItemPk = new HashMap<>();
        // The waiting places are event-wide and the same on every accommodation row.
        Integer pendingSharers = null;
        for (ScheduledItem si : policy.getScheduledItems()) {
            Item item = si.getItem();
            // By family CODE: this module does not read modality.base.shared.knownitems, and adding that
            // dependency would mean regenerating WebFX-managed files for one constant.
            ItemFamily family = item == null ? null : item.getFamily();
            if (family == null || !ACCOMMODATION_FAMILY_CODE.equals(family.getCode()))
                continue;
            if (si.getPendingSharers() != null)
                pendingSharers = pendingSharers == null ? si.getPendingSharers() : Math.max(pendingSharers, si.getPendingSharers());
            if (!Boolean.TRUE.equals(item.isShare_mate()))
                roomRowByItemPk.put(item.getPrimaryKey(), si);
        }
        List<Object> roomItemPks = new ArrayList<>();
        if (pendingSharers == null)
            return new Offer(null, roomItemPks); // an older server: no figures at all

        EntityId[] pairedIds = pairedIdsOf(sharingItemPolicy);
        int total = 0;
        for (Map.Entry<Object, ScheduledItem> entry : roomRowByItemPk.entrySet()) {
            Object roomPk = entry.getKey();
            if (!pairsWith(pairedIds, roomPk))
                continue;
            ItemPolicy roomPolicy = policy.getItemPolicy(entry.getValue().getItem());
            if (roomPolicy != null && Objects.equals(roomPolicy.getMinOccupancy(), 1))
                continue; // booked for sole occupancy: its second bed is not on offer
            Integer free = entry.getValue().getFreeSharedBeds();
            if (free == null)
                continue; // no capacity: not a whole-room type (a dormitory, a per-person room), so no bed to share
            total += Math.max(0, free);
            roomItemPks.add(roomPk);
        }
        return new Offer(Math.max(0, total - pendingSharers), roomItemPks);
    }

    /**
     * Loads the event's policy once and returns the first requested sharing item that may not be booked, or
     * null when all of them may.
     *
     * <p>{@code requestedLinesByItemPk} counts the sharing lines THIS submit adds to the event, per item. They
     * are not in the database yet, so the free-bed figures cannot see them: an option is refused when the
     * lines drawing on its beds — always its own, plus those of every requested option whose pairing overlaps
     * its rooms — outnumber them. Otherwise one crafted submit could put several sharers on one free bed. Its
     * own lines count even when it offers no room at all, or an option with nothing to offer would never be
     * refused.
     *
     * <p>Also refused: a sharing item this event's policy does not offer at all — the booking form only ever
     * offers the policy's own, so only a crafted request sends another. Never refused: an option whose
     * availability cannot be known.
     *
     * <p>With {@code countBeds} false only that offered check runs — for a line an invite link stands in for,
     * whose bed was checked on its own, but whose item must still be one the event offers.
     */
    public static Future<Object> firstOverbooked(Object eventPk, Map<Object, Integer> requestedLinesByItemPk, boolean countBeds) {
        return PolicyService.loadPolicy(new LoadPolicyArgument(eventPk)).map(policy -> {
            policy.rebuildEntities(EntityStore.create());
            Map<Object, ItemPolicy> offeredByItemPk = new LinkedHashMap<>();
            for (Object itemPk : requestedLinesByItemPk.keySet()) {
                ItemPolicy offered = null;
                for (ItemPolicy sharingItemPolicy : policy.getSharingAccommodationItemPolicies())
                    if (Entities.samePrimaryKey(sharingItemPolicy.getItem(), itemPk))
                        offered = sharingItemPolicy;
                if (offered == null)
                    return itemPk;
                offeredByItemPk.put(itemPk, offered);
            }
            if (!countBeds)
                return null;
            for (Map.Entry<Object, ItemPolicy> entry : offeredByItemPk.entrySet()) {
                Offer offer = offerFor(policy, entry.getValue());
                if (offer.freeBeds() == null)
                    continue;
                int drawing = 0;
                for (Map.Entry<Object, Integer> requested : requestedLinesByItemPk.entrySet())
                    if (Objects.equals(requested.getKey(), entry.getKey())
                        || overlaps(pairedIdsOf(offeredByItemPk.get(requested.getKey())), offer.roomItemPks()))
                        drawing += requested.getValue();
                if (drawing > offer.freeBeds())
                    return entry.getKey();
            }
            return null;
        });
    }

    private static EntityId[] pairedIdsOf(ItemPolicy itemPolicy) {
        if (itemPolicy == null)
            return new EntityId[0];
        return new EntityId[] {
            itemPolicy.getPairedItem1Id(), itemPolicy.getPairedItem2Id(),
            itemPolicy.getPairedItem3Id(), itemPolicy.getPairedItem4Id() };
    }

    /** Whether a pairing covers any of {@code roomItemPks}. */
    private static boolean overlaps(EntityId[] pairedIds, List<Object> roomItemPks) {
        for (Object roomPk : roomItemPks)
            if (pairsWith(pairedIds, roomPk))
                return true;
        return false;
    }

    /** Whether a pairing covers {@code itemPk} — an empty pairing ("any shareable accommodation") covers everything. */
    private static boolean pairsWith(EntityId[] pairedIds, Object itemPk) {
        boolean paired = false;
        for (EntityId id : pairedIds) {
            if (id == null)
                continue;
            paired = true;
            if (Entities.samePrimaryKey(id, itemPk))
                return true;
        }
        return !paired;
    }
}

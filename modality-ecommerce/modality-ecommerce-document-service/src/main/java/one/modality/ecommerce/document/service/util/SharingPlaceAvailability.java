package one.modality.ecommerce.document.service.util;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Item;
import one.modality.base.shared.entities.ItemFamily;
import one.modality.base.shared.entities.Rate;
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
     * <p>Also refused: an item this event does not offer as a sharing place at all — neither a configured
     * sharing option of its policy, nor a room eligible for a virtual one ({@link #virtualOfferFor}). The
     * booking form only ever offers those, so only a crafted request sends another. Never refused: an option
     * whose availability cannot be known.
     *
     * <p>With {@code countBeds} false only that offered check runs — for a line an invite link stands in for,
     * whose bed was checked on its own, but whose item must still be one the event offers.
     */
    public static Future<Object> firstOverbooked(Object eventPk, Map<Object, Integer> requestedLinesByItemPk, boolean countBeds) {
        return PolicyService.loadPolicy(new LoadPolicyArgument(eventPk)).map(policy -> {
            policy.rebuildEntities(EntityStore.create());
            Map<Object, Offer> offerByItemPk = new LinkedHashMap<>();
            for (Object itemPk : requestedLinesByItemPk.keySet()) {
                ItemPolicy offered = null;
                for (ItemPolicy sharingItemPolicy : policy.getSharingAccommodationItemPolicies())
                    if (Entities.samePrimaryKey(sharingItemPolicy.getItem(), itemPk))
                        offered = sharingItemPolicy;
                // A configured sharing option, or -- where the organisation configured none for that room --
                // the room's own VIRTUAL one. Null from either means this event does not offer the item as a
                // sharing place at all, which is the check that stands whether or not beds are counted.
                Offer offer = offered != null ? offerFor(policy, offered) : virtualOfferFor(policy, itemPk);
                if (offer == null)
                    return itemPk;
                offerByItemPk.put(itemPk, offer);
            }
            if (!countBeds)
                return null;
            for (Map.Entry<Object, Offer> entry : offerByItemPk.entrySet()) {
                Offer offer = entry.getValue();
                if (offer.freeBeds() == null)
                    continue;
                int drawing = 0;
                for (Map.Entry<Object, Integer> requested : requestedLinesByItemPk.entrySet())
                    // Two options draw on the same beds when the ROOMS they draw from overlap. Asked of the
                    // rooms rather than of the pairings (which is how this read until virtual options existed),
                    // because a virtual option has no pairing to ask -- it IS one room. The two agree wherever
                    // a pairing exists, since an option's rooms are exactly the ones its pairing covers, less
                    // those every offer excludes anyway. They differ in one case, deliberately: an option that
                    // needs no bed (V0103) has no rooms, so it no longer counts against another option's beds.
                    // It never had beds of its own checked either -- counting its draw on someone else's was
                    // the flag contradicting itself, and refused sharers who should have been let in.
                    if (Objects.equals(requested.getKey(), entry.getKey())
                        || intersects(offerByItemPk.get(requested.getKey()).roomItemPks(), offer.roomItemPks()))
                        drawing += requested.getValue();
                if (drawing > offer.freeBeds())
                    return entry.getKey();
            }
            return null;
        });
    }

    /**
     * What a VIRTUAL sharing option offers, or null when this item is not one (room-mate plan Part B).
     *
     * <p>A virtual option is the absence of configuration: where no sharing Item was set up for a room, the
     * mate's line names the ROOM's item and says what it is on the line ({@code document_line.share_mate}).
     * So there is no {@link ItemPolicy} to read an offer from — the offer is that one room's own free beds,
     * less the event's unlinked sharing places, exactly as a configured option paired with that one room
     * would have offered.
     *
     * <p>Eligibility mirrors {@code getAccommodationOptions}' synthesis in {@code policy-helpers.ts}, and must
     * keep mirroring it, or the server refuses what the card offered:
     * <ul>
     *   <li>an accommodation item of this event, not a sharing item itself;</li>
     *   <li>more than one bed — one bed cannot be shared, and an item with no capacity is not a whole-room
     *       type (the same test {@code V0117} governs a mate's quantity by, so the three agree);</li>
     *   <li>not sold for sole occupancy ({@code minOccupancy} 1), whose booker hosts nobody;</li>
     *   <li><b>no configured sharing option pairs with it, for anyone.</b> Where the organisation said how
     *       that room is shared, that statement governs — including its own availability, which may be
     *       stricter. Counted over every sharing option the EVENT offers, never the ones one booker can see:
     *       the visible list is age-filtered, so testing it would conjure a virtual option for an adult where
     *       only a children's one was deliberately offered.</li>
     * </ul>
     *
     * <p>The same tests the card applies, deliberately — see {@link #isVirtualSharingRoom}, which holds the
     * rule and says why this side must be the strict one. The day-visitor item needs no test of its own: it
     * is attendance without a bed, so it carries no capacity and is already excluded.
     */
    private static Offer virtualOfferFor(PolicyAggregate policy, Object roomItemPk) {
        ScheduledItem roomRow = null;
        Integer pendingSharers = null;
        for (ScheduledItem si : policy.getScheduledItems()) {
            Item item = si.getItem();
            ItemFamily family = item == null ? null : item.getFamily();
            if (family == null || !ACCOMMODATION_FAMILY_CODE.equals(family.getCode()))
                continue;
            if (si.getPendingSharers() != null)
                pendingSharers = pendingSharers == null ? si.getPendingSharers() : Math.max(pendingSharers, si.getPendingSharers());
            if (roomRow == null && !Boolean.TRUE.equals(item.isShare_mate()) && Entities.samePrimaryKey(item, roomItemPk))
                roomRow = si;
        }
        if (roomRow == null)
            return null; // not an accommodation item of this event, or a sharing item (handled by its policy)
        Item room = roomRow.getItem();
        // Registration's own switch, which the card obeys too. It matters more here than for a configured
        // option: V0118 stops these lines reaching the defer-allocate trigger, so the sold_out_item check
        // that ran inside allocation (V0030/V0082) no longer stands behind this one. Without it, ticking a
        // room type sold out would stop its public sales while still accepting shares of it.
        if (policy.isItemForcedSoldOut(room, roomRow.getSite()))
            return null;
        ItemPolicy roomPolicy = policy.getItemPolicy(room);
        boolean configuredPairingCovers = false;
        for (ItemPolicy sharingItemPolicy : policy.getSharingAccommodationItemPolicies())
            if (pairsWith(pairedIdsOf(sharingItemPolicy), roomItemPk))
                configuredPairingCovers = true;
        if (!isVirtualSharingRoom(room.getCapacity(),
            roomPolicy == null ? null : roomPolicy.getMinOccupancy(), configuredPairingCovers,
            isPerPersonRoom(policy, roomRow), isApplicableInPerson(roomPolicy)))
            return null;
        List<Object> roomItemPks = new ArrayList<>();
        roomItemPks.add(roomItemPk);
        if (pendingSharers == null)
            return new Offer(null, roomItemPks); // an older server: no figures, so nobody is refused
        Integer free = roomRow.getFreeSharedBeds();
        if (free == null)
            return new Offer(null, roomItemPks);
        return new Offer(Math.max(0, free - pendingSharers), roomItemPks);
    }

    /**
     * The rule deciding whether a room carries a virtual sharing option, as facts rather than entities, so
     * it can be checked on its own — see {@code SharingPlaceAvailabilityCheck}.
     *
     * <p>Its twin is the synthesis in {@code getAccommodationOptions} ({@code policy-helpers.ts}) and the
     * two must apply the SAME tests. An earlier draft let the server be the looser side, reasoning that a
     * strict card only risks refusing a bed it offered. That is the wrong way round for this rule: it
     * decides which items may be priced at ZERO, so a claim the server accepts and the card never offers
     * is a free booking waiting for a crafted submit. On staging, 37 live room bookings across 22 events
     * carry 38 free shared beds on per-person items at £10.60–£70.00 per person per night — beds the form
     * shows to nobody. The server is therefore at least as strict as the card, in both directions.
     *
     * @param capacity the room's own capacity: more than one bed, since one bed cannot be shared, and an
     *                 item with no capacity at all is not a whole-room type. The same test {@code V0117}
     *                 governs a mate's quantity by, so the rule, the trigger and the card agree on which
     *                 rooms have a bed to share.
     * @param minOccupancy the room's {@code ItemPolicy.minOccupancy}: 1 means its booker paid to have it to
     *                     themselves and hosts nobody.
     * @param configuredPairingCovers whether any sharing option THIS EVENT offers pairs with the room —
     *                     counted over the event's own options, never the age-filtered ones a booker sees,
     *                     or an event offering only a children's sharing option would conjure an adult one
     *                     that was deliberately never configured. An option with no pairing covers every
     *                     room, so a single unpaired one suppresses them all.
     */
    static boolean isVirtualSharingRoom(Integer capacity, Integer minOccupancy, boolean configuredPairingCovers,
                                        boolean perPersonRoom, boolean applicableInPerson) {
        return capacity != null && capacity > 1
            && !Objects.equals(minOccupancy, 1)
            && !configuredPairingCovers
            && !perPersonRoom
            && applicableInPerson;
    }

    /**
     * Whether the room is sold per person rather than as a whole room, by its first applicable daily rate —
     * the same rate and the same default the card reads ({@code getAccommodationOptions}: no daily rate at
     * all means per person, so such an item is not a whole room to spare a bed of either).
     *
     * <p>A per-person room has no bed to give away: everyone in it has paid for their own place. Letting a
     * sharing claim name one is how a bed sold at a per-person rate becomes free.
     */
    private static boolean isPerPersonRoom(PolicyAggregate policy, ScheduledItem roomRow) {
        for (Rate rate : policy.getDailyRates())
            if (Entities.samePrimaryKey(rate.getItem(), roomRow.getItem())
                && Entities.samePrimaryKey(rate.getSite(), roomRow.getSite()))
                return !Boolean.FALSE.equals(rate.isPerPerson()); // unset reads as per person, as on the card
        return true; // no daily rate for this site and item: not a whole-room type
    }

    /**
     * Whether the room's policy offers it to people attending in person at all. Accommodation is in-person
     * by definition, so an item whose policy opts out is never shown as a room — and must not be reachable
     * as a bed in one. Unset means offered, as {@code isItemPolicyApplicableToMode} reads it.
     */
    private static boolean isApplicableInPerson(ItemPolicy roomPolicy) {
        return roomPolicy == null || !Boolean.FALSE.equals(roomPolicy.isApplicableToInPerson());
    }

    private static EntityId[] pairedIdsOf(ItemPolicy itemPolicy) {
        if (itemPolicy == null)
            return new EntityId[0];
        return new EntityId[] {
            itemPolicy.getPairedItem1Id(), itemPolicy.getPairedItem2Id(),
            itemPolicy.getPairedItem3Id(), itemPolicy.getPairedItem4Id() };
    }

    /**
     * Whether two offers draw from any room in common.
     *
     * <p>By {@link Entities#samePrimaryKey}, never by {@code equals}: a configured offer's rooms are the
     * primary keys of loaded entities while a virtual offer's is the key the submit sent, so the same room
     * arrives here as two number types, and a room that compared unequal to itself would let two sharers
     * onto one bed.
     */
    private static boolean intersects(List<Object> someRoomItemPks, List<Object> otherRoomItemPks) {
        for (Object roomPk : someRoomItemPks)
            for (Object otherRoomPk : otherRoomItemPks)
                if (Entities.samePrimaryKey(roomPk, otherRoomPk))
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

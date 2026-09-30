-- V0117: the database owns a share-mate line's quantity ON AN ACCOMMODATION ROOM — 0 unlinked, 1 linked.
--
-- A sharing place is one person. The room booker's own line carries the headcount (their bed plus the
-- beds they hold for mates, share_owner_quantity); a mate's line counts 1 once it is linked to that
-- booker and nothing before, because until someone links it there is no bed — only a claim on one
-- (Bruno). Allocation measures occupancy as sum(document_line.quantity) per resource and date, so this
-- is the field that decides whether a mate holds a bed.
--
-- Three things it deliberately does NOT touch, each because the rule is not true of them:
--
-- 1. A line whose ITEM is itself a sharing option. "Sharing a room" has no capacity, so it never took a
--    bed however its quantity read, and the old mechanism keeps working untouched. That matters beyond
--    tidiness: KBS2 books those items and knows nothing of this rule. The split on staging is clean — of
--    466 unlinked mates on sharing items, 6 hold a resource; of 378 on real rooms, 274 do.
-- 2. A line flagged share_owner as WELL as share_mate. 17 live lines are (6 of them unlinked, carrying up
--    to 3), and their quantity is a room booker's headcount: forcing it to 0 or 1 would erase beds the
--    booking holds and let the same room be allocated to somebody else. Contradictory as the pair of
--    flags is, the owner reading is the one that governs beds, so it wins.
-- 3. A line outside the accommodation family. 35 live share-mate lines are meals, teaching, diet, tax and
--    transport, where `quantity` counts meals or passes, not beds, and zeroing it would be nonsense. The
--    reasoning here is entirely about beds, so its scope is beds.
-- 4. A room with no second bed to share. A mate in a one-bed room is a contradiction, so the flag there
--    can only be a mistake — 14 live lines carry it on a capacity-1 room, every one occupying that room,
--    including bookings 93 and 105 at event 1957 (single rooms, flag ticked in error, which registration
--    then re-priced by hand — that custom price is the only reason the database is not charging them 0).
--    Governing them would release the room they are sitting in. A capacity that is not set at all is left
--    alone for the same reason: it is not a whole-room type, so a bed in it is not this rule's business.
--
-- What it is for: a mate's line may then name the ROOM's own item (room-mate plan Part B, "virtual
-- sharing options") without taking a bed from the inventory, which is what the separate sharing item did
-- by having no capacity. No allocation trigger needs to learn about the line's flag, so the sold-out gate
-- stays keyed on the catalogue flag that only staff can set.
--
-- Writing it here rather than in the client is what keeps it honest. The rule is a fact about the domain,
-- so it belongs where it cannot be forgotten — the registration team can swap a booker and a mate around,
-- and the quantities did not follow. And a quantity a client could set is a price a client could set,
-- since compute_document_line_pricing_quantity multiplies the rate by it.
--
-- On price: for an uncharged mate — every share-mate line in the database today — the price is already 0
-- through the share_mate branch, so nothing moves. It would NOT be true of a CHARGED mate
-- (share_mate_charged), whose line is priced by this very quantity, and whose owner's per-person price
-- subtracts the sum of its charged mates' quantities. No such line exists yet; if one is ever created,
-- this rule has to be revisited with it.

CREATE OR REPLACE FUNCTION trigger_document_line_share_mate_quantity() RETURNS trigger AS $$
DECLARE
    governed bool;
BEGIN
    -- A mate's bed, and only that: not a sharing option's own item, not a line whose quantity counts
    -- something other than beds, and not a room without a second bed to share.
    select not coalesce(i.share_mate, false) and f.code = 'acco' and coalesce(i.capacity, 0) > 1
      into governed
      from item i join item_family f on f.id = i.family_id
     where i.id = NEW.item_id;
    IF coalesce(governed, false) AND NOT coalesce(NEW.share_owner, false) THEN
        NEW.quantity := CASE WHEN NEW.share_mate_owner_document_line_id IS NULL THEN 0 ELSE 1 END;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Installed the way V0114 installs its own trigger on this table, and for the reason given there: every
-- pending script runs in one transaction, so an untimed ACCESS EXCLUSIVE on document_line (1.37M rows,
-- constant booking traffic) can roll back the whole deploy's batch. WHEN (NEW.share_mate) keeps the
-- function off the 99.3% of rows that are not sharing places at all.
DO $$
BEGIN
    SET LOCAL lock_timeout = '3s';
    DROP TRIGGER IF EXISTS share_mate_quantity ON public.document_line;
    CREATE TRIGGER share_mate_quantity
        BEFORE INSERT OR UPDATE OF share_mate, share_mate_owner_document_line_id, quantity, item_id, share_owner
        ON public.document_line
        FOR EACH ROW WHEN (NEW.share_mate)
        EXECUTE FUNCTION public.trigger_document_line_share_mate_quantity();
    RAISE NOTICE 'V0117: share_mate_quantity installed';
EXCEPTION
    WHEN lock_not_available THEN
        RAISE WARNING 'V0117: document_line was busy, so share_mate_quantity was NOT installed. A virtual sharing option would take a bed it has no right to, so Part B must not be switched on until this is run by hand at a quieter moment';
    WHEN insufficient_privilege THEN
        RAISE WARNING 'V0117: the migrating role may not create a trigger on document_line; share_mate_quantity was NOT installed';
END $$;

-- No rows are corrected here. The mechanism ships first and is tested on staging; the data is corrected
-- afterwards, as its own step, once the rule has been seen to work (Bruno). What is waiting: 93 LINKED
-- mates on rooms whose quantity is not 1 — recognisably swaps, each of the 61 over-counted ones carrying
-- a share_owner_quantity equal to its quantity while linked to an owner whose own quantity is 1 — and 378
-- UNLINKED mates on rooms, 29 of them live and holding a resource, which are a registration decision
-- rather than a correction: dropping those to 0 turns a bed someone believes they have into a claim on
-- one, and lets the next booker into the room.
--
-- Be clear about one consequence, because it is not really a deferral: the trigger settles any such row
-- the moment anything touches its item, flags, link or quantity. So the legacy population shrinks from
-- here one row at a time, silently and without a history record, whether or not a bulk correction is ever
-- run. Correcting them deliberately is therefore better than leaving them to be corrected by accident.

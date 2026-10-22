-- A booking line may only be put into a room of its OWN site.
--
-- WHY: the deferred allocation (deferred_allocate_document_line) re-picks a line's room among its own site's rooms,
-- so for most lines a room of another site is replaced at commit. But defer_allocate skips some lines -- share-mate
-- lines, items flagged share_mate, and the French festivals' pdej/din -- and those keep the room written verbatim.
-- Any writer could then put a line booked at centre A's site into centre B's room, corrupting that room's occupancy
-- and sold-out counts: the document service (a back-office room move), a generic client write, KBS2's back office.
-- A trigger covers them all at once.
--
-- SAFE ON TODAY'S DATA (staging = production copy, 2026-10-22): 410 of 330,644 roomed lines break the rule, the
-- newest created 2019-11-12, none since. The rule judges only a CHANGE of room or site, so those old rows are never
-- re-judged -- see the IS DISTINCT FROM below.
--
-- The SITE only, not the item: 41 lines created in 2026 legitimately sit in a room of another item (room-mate
-- lines, deliberate placements into another type's room -- the allocation's sold-out checks expect it).
--
-- What it does NOT watch: moving a ROOM to another site, or re-pointing a CONFIGURATION at a room of another site.
-- Neither writes document_line, so lines already in that room would silently mismatch. Rare administrator acts;
-- left out deliberately.
--
-- How it meets the other triggers (all checked before writing this):
--   * on_item_changed_cascade_document_lines writes item_id only -- not watched here;
--   * the deferred allocation writes resource_configuration_id on every allocation, always a room of the line's own
--     site, so it passes; its allocation-rule branch does not check the site, and a rule pointing at another site's
--     room would now FAIL the booking loudly instead of mis-allocating it quietly;
--   * on_share_linked_copy_info and on_owner_changes_cascade_mates copy the owner's site AND room together.
--
-- Why IS DISTINCT FROM and not just UPDATE OF: in PostgreSQL "UPDATE OF col" fires whenever the column is in the
-- SET list, changed or not. A writer that saves a whole row (KBS2 may) would otherwise re-judge an old mismatched
-- line it did not move.
--
-- No anonymiser change: no column is added, and the anonymiser rewrites only document_line's room-mate name columns
-- (with triggers disabled).

CREATE OR REPLACE FUNCTION public.trigger_document_line_room_on_its_site() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    room_site_id integer;
BEGIN
    IF NEW.resource_configuration_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE'
       AND NEW.resource_configuration_id IS NOT DISTINCT FROM OLD.resource_configuration_id
       AND NEW.site_id IS NOT DISTINCT FROM OLD.site_id THEN
        RETURN NEW; -- neither the room nor the site changed: nothing to judge
    END IF;
    SELECT r.site_id INTO room_site_id
      FROM resource_configuration rc
      JOIN resource r ON r.id = rc.resource_id
     WHERE rc.id = NEW.resource_configuration_id;
    IF room_site_id IS DISTINCT FROM NEW.site_id THEN
        RAISE EXCEPTION 'ROOM_OF_ANOTHER_SITE document_line % (site %) cannot use resource_configuration % (site %)',
            NEW.id, NEW.site_id, NEW.resource_configuration_id, room_site_id;
    END IF;
    RETURN NEW;
END $$;

-- Installed the way V0114 and V0117 install theirs on this table: every pending script runs in one transaction, so
-- an untimed lock on document_line (constant booking traffic) could roll back the whole deploy's batch. If the table
-- is busy the trigger is NOT installed and a warning says so; run this block by hand at a quieter moment.
-- WHEN (NEW.resource_configuration_id IS NOT NULL) keeps the function off every line without a room.
DO $$
BEGIN
    SET LOCAL lock_timeout = '3s';
    DROP TRIGGER IF EXISTS room_on_its_site ON public.document_line;
    CREATE TRIGGER room_on_its_site
        BEFORE INSERT OR UPDATE OF resource_configuration_id, site_id
        ON public.document_line
        FOR EACH ROW WHEN (NEW.resource_configuration_id IS NOT NULL)
        EXECUTE FUNCTION public.trigger_document_line_room_on_its_site();
    RAISE NOTICE 'V0140: room_on_its_site installed';
EXCEPTION
    WHEN lock_not_available THEN
        RAISE WARNING 'V0140: document_line was busy, so room_on_its_site was NOT installed. A line can still be put into another site''s room until this block is run by hand';
    WHEN insufficient_privilege THEN
        RAISE WARNING 'V0140: the migrating role may not create a trigger on document_line; room_on_its_site was NOT installed';
END $$;

-- V0114: capture document_line.breakfast_included on insert when the client left it unset.
--
-- The breakfast credit (compute_document_prices, V0093) forgives one breakfast per accommodation
-- night whose line has breakfast_included = true. The flag is a snapshot taken when the line is
-- booked, by WorkingBooking.computeBreakfastIncluded (Java and React). The KBS2 back office knows
-- nothing about it: a line the registration team adds there to a KBS3 event arrives with NULL, so
-- that booking pays for breakfasts its accommodation already includes (event 1898, camping +
-- breakfast charged in full).
--
-- This trigger captures the flag in the database, so it covers every door. It only fills a NULL, so
-- the KBS3 clients (which always send true or false) are untouched, and it only acts on KBS3 events,
-- so KBS2 bookings keep NULL and are never credited, as designed.
--
-- The rule:
-- 1. A sharing option (item.share_mate) has no rate, so it carries the flag on the item: sharing
--    room = true, sharing tent = false. item.breakfast_included is read for sharing options ONLY.
--    On a room owner's item it has no meaning, and it is set there anyway: Single/Double/Twin
--    Ensuite at event 1898 carry false while their rates include breakfast. Reading it would take
--    the credit away from every room booked through the KBS2 back office.
-- 2. A room owner's accommodation line is true when any rate of the line's site and item that
--    applies to the event includes breakfast. The event scope mirrors the rate loading of
--    ServerPolicyServiceProvider: a rate bound to another event or event type does not count; a
--    repeated event uses the rates of the event it repeats. A share mate line is not charged by a
--    rate, so it never takes this branch: a mate line that already carries the owner's item must
--    not pick up the owner's breakfast.
-- 3. Otherwise false.
--
-- INSERT only, on purpose. The flag is frozen after booking: the share-linking action swaps a mate's
-- sharing item for the owner's item, and must not recapture it (a tent sharer would otherwise gain
-- the booker's breakfast). Existing NULL lines are not backfilled here either; correcting a booking
-- that was already charged needs a history entry, which the back office writes and SQL does not.
--
-- Safe to run by hand before the deploy that ships it (then run as the schema owner: SET ROLE kbs),
-- and safe for the deploy to run again afterwards: the function is replaced with the same body, and
-- the trigger is created only when missing.

CREATE OR REPLACE FUNCTION public.trigger_document_line_capture_breakfast_included() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    final_event_id int;
    final_event_type_id int;
    item_share_mate boolean;
    item_flag boolean;
    is_acco boolean;
BEGIN
    IF NEW.item_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT coalesce(e.repeated_event_id, e.id), coalesce(re.type_id, e.type_id)
      INTO final_event_id, final_event_type_id
      FROM document d
      JOIN event e ON e.id = d.event_id
      LEFT JOIN event re ON re.id = e.repeated_event_id
     WHERE d.id = NEW.document_id AND e.kbs3;
    IF NOT FOUND THEN -- KBS2 event: leave NULL, never credited
        RETURN NEW;
    END IF;
    SELECT coalesce(i.share_mate, false), i.breakfast_included, f.code = 'acco'
      INTO item_share_mate, item_flag, is_acco
      FROM item i
      LEFT JOIN item_family f ON f.id = i.family_id
     WHERE i.id = NEW.item_id;
    IF item_share_mate THEN
        NEW.breakfast_included := coalesce(item_flag, false);
    ELSIF coalesce(is_acco, false) AND NOT coalesce(NEW.share_mate, false) THEN
        NEW.breakfast_included := exists(
            SELECT 1 FROM rate r
             WHERE r.site_id = NEW.site_id AND r.item_id = NEW.item_id AND r.breakfast_included
               AND (r.event_id IS NULL OR r.event_id = final_event_id)
               AND (r.event_type_id IS NULL OR r.event_type_id = final_event_type_id));
    ELSE
        NEW.breakfast_included := false;
    END IF;
    RETURN NEW;
END $$;

-- The trigger itself, the V0100 way. CREATE TRIGGER takes SHARE ROW EXCLUSIVE on document_line, a
-- hot table, and every pending script runs in one transaction: a lock timeout at top level would
-- roll back the whole deploy's batch. So it waits 3s at most, and a busy table or a missing privilege
-- becomes a WARNING. The worst case is the state before: no trigger, and this same file run by hand
-- at a quieter moment (as the table owner) puts it right.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_trigger
                WHERE tgrelid = 'public.document_line'::regclass
                  AND tgname = 'capture_breakfast_included' AND NOT tgisinternal) THEN
        RAISE NOTICE 'V0114: capture_breakfast_included is already installed';
        RETURN;
    END IF;
    SET LOCAL lock_timeout = '3s';
    CREATE TRIGGER capture_breakfast_included
        BEFORE INSERT ON public.document_line
        FOR EACH ROW WHEN (NEW.breakfast_included IS NULL)
        EXECUTE FUNCTION public.trigger_document_line_capture_breakfast_included();
    RAISE NOTICE 'V0114: capture_breakfast_included installed';
EXCEPTION
    WHEN lock_not_available THEN
        RAISE WARNING 'V0114: document_line was busy, so capture_breakfast_included was NOT installed. Lines added by the KBS2 back office keep breakfast_included NULL and are charged breakfast. Run this script again by hand at a quieter moment';
    WHEN insufficient_privilege THEN
        RAISE WARNING 'V0114: no TRIGGER privilege on document_line, so capture_breakfast_included was NOT installed. Run this script by hand as the table owner (SET ROLE kbs)';
END $$;

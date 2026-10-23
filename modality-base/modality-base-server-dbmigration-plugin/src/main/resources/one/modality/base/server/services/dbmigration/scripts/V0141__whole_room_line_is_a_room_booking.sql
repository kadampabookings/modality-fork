-- V0141: a line that books a WHOLE ROOM says so, whatever the client remembered to send.
--
-- WHY. share_owner is what makes a room shareable: mintMateInviteToken refuses a booking without it, the
-- sharing pool counts beds from share_owner_quantity, and "Book their place" is offered only beside it. The
-- flag is written by exactly one thing -- EditShareOwnerInfoDocumentLineEvent -- which the booking form
-- sends only when selection.isRoomBooker is true, and that in turn is set only by the effect inside
-- useRoommateSection, i.e. only while the roommate section is actually mounted. Any session that books a
-- room without that section having rendered produces a whole-room line flagged as nothing at all.
--
-- It happens. Across production since 1 August there are 13 live, priced, front-office bookings (every one
-- with a cart) holding a multi-bed room with neither flag, over five event types -- NEDC 7, Stays 2,
-- Weekend Course 1, Empowerment Weekend 1, Day Course 2. The one that surfaced it, event 1953 ref 41,
-- reached us as an apology from the customer: she had booked a twin for herself and her mother, could not
-- join her own room, and assumed she had done something wrong. We never reproduced it -- two attempts
-- following her steps both produced a correct booking -- so the client cause is still unknown and is being
-- chased separately. This is the backstop that makes the question moot.
--
-- WHY HERE AND NOT IN THE SUBMIT. Three reasons, in order of weight:
--   1. The form is one writer of this table among several. The back office and KBS2 write accommodation
--      lines too and know nothing of this flag, so a rule enforced in the document service would hold for
--      the client we have just watched get it wrong and for nothing else.
--   2. The facts the rule needs -- the item's capacity and whether the item is sold per person at this
--      site for this document -- are already here. The submit path has neither: document_line.rate_id is
--      null on these lines, so the applied rate is not readable from the row.
--   3. It is a fact about the domain. A line that books every bed in a room IS a room booking; that is not
--      a client's opinion.
--
-- WHY *BEFORE* INSERT, AND NOT A DEFERRED TRIGGER AT COMMIT. Because the flag feeds pricing, and pricing
-- must see it once, already correct. compute_document_prices orders a line's candidate rates with
--
--     case when (dls.dl).share_owner then coalesce(r.per_person, false) end
--
-- so WHICH rate applies depends on share_owner. Setting the flag after pricing would reprice the booking;
-- consulting the applied rate to decide the flag would be circular. Running before the row lands avoids
-- both: compute_document_prices is deferred to commit and therefore sees the final value, and the booking
-- is priced from the start exactly as it would have been had the form sent the event.
--
-- WHY THE PER-PERSON TEST IS NOT OPTIONAL. Capacity above 1 does not mean a whole room. "1 or 2 Person
-- pre-erected tent" at the Fall Festival carries capacity 2 and is sold per person; four live lines on it
-- have neither flag and are perfectly correct. Flagging the first booker as owner of the whole tent would
-- hand them a bed to give away that they never bought. So the rule asks the same question the booking form
-- asks -- AccommodationOption.perPerson, read from the first per-day rate for this site and item in the
-- policy's own order (startDate, endDate, price) -- and, like the form, treats "no rate found" as
-- per-person, i.e. does nothing. Where we cannot tell, we leave it alone.
--
-- WHAT IT DELIBERATELY DOES NOT TOUCH:
--   * A line already carrying either flag. The WHEN clause keeps the function off it entirely, so a mate,
--     an owner, and the 17 lines carrying both (V0117 note 2) are all untouched.
--   * A line whose ITEM is a sharing option. Those carry no capacity, so the capacity test excludes them;
--     a sharing place is not a room and has no beds to hold.
--   * Anything outside the accommodation family. The reasoning is entirely about beds.
--   * A room with no second bed. capacity <= 1, or unset: not a whole-room type (V0117 reaches the same
--     conclusion from the other side).
--   * UPDATEs. A line's nature is settled when it is created. On UPDATE this rule would fight the
--     registration team: a staff member clearing share_owner deliberately would have it written straight
--     back, and no amount of comment makes that behaviour guessable from the outside.
--
-- A MATE'S LINE IS SAFE EVEN THOUGH IT IS INSERTED UNFLAGGED. The form inserts the line, then sends
-- EditShareMateInfoDocumentLineEvent, whose replay sets share_mate := true AND share_owner := false. So a
-- line that this rule flags at insert and that turns out to be a mate's has the flag cleared by the very
-- event that makes it one. If a future writer persists share_mate in the INSERT itself, the WHEN clause
-- skips the row instead. Both orders end correctly.
--
-- ON PRICE: nothing moves for the lines this governs. compute_document_line_pricing_quantity forces
-- quantity := 1 for any rate that is not per-person, and this rule flags only non-per-person lines, so
-- share_owner_quantity (set here to the room's capacity, as the form sets it) multiplies nothing. The
-- quantity is set all the same because the free-bed arithmetic counts from it: a flag with quantity 1
-- would make a twin read as a full room and hide the bed this whole mechanism exists to offer.
--
-- NO ROWS ARE CORRECTED HERE, following V0117: the mechanism ships first and is watched on staging, and
-- the 13 known rows are corrected afterwards as their own step. They need thought this file should not
-- pre-empt -- flagging an existing line changes which rate applies to it, so a correction is a repricing,
-- and a booking already paid is not a row to reprice silently.

CREATE OR REPLACE FUNCTION trigger_document_line_whole_room_is_room_booking() RETURNS trigger AS $$
DECLARE
    room_capacity int;
    per_person_item bool;
    doc public.document;
BEGIN
    -- A whole-room type, and not a sharing option's own item. Mirrors V0117's `governed` test.
    select i.capacity
      into room_capacity
      from item i join item_family f on f.id = i.family_id
     where i.id = NEW.item_id
       and f.code = 'acco'
       and not coalesce(i.share_mate, false);
    IF coalesce(room_capacity, 0) <= 1 THEN
        RETURN NEW;
    END IF;

    select * into doc from public.document d where d.id = NEW.document_id;
    IF NOT FOUND THEN
        RETURN NEW; -- no document to judge the rates against; say nothing
    END IF;

    -- The booking form's own question, asked the same way: AccommodationOption.perPerson is read from the
    -- FIRST per-day rate for this site and item, in the order the policy query returns them
    -- (`order by site,item,perDay desc,startDate,endDate,price`), defaulting to per-person when there is
    -- none. rate_alias_item_id is followed because compute_document_prices fetches rates by it.
    select coalesce(r.per_person, true)
      into per_person_item
      from rate r
     where r.site_id = NEW.site_id
       and r.item_id = coalesce((select i.rate_alias_item_id from item i where i.id = NEW.item_id), NEW.item_id)
       and r.per_day
       and rate_matches_document(r, doc)
     order by r.start_date, r.end_date, r.price
     limit 1;

    IF coalesce(per_person_item, true) THEN
        RETURN NEW; -- sold per person, or no rate to tell us: not this rule's business
    END IF;

    NEW.share_owner := true;
    NEW.share_owner_quantity := room_capacity;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Installed as V0114 and V0117 install theirs, and for the reason given there: every pending script runs
-- in one transaction, so an untimed ACCESS EXCLUSIVE on document_line (1.37M rows, constant booking
-- traffic) can roll back the whole deploy's batch. The WHEN clause keeps the function off every line that
-- already says what it is, which is all but a handful.
DO $$
BEGIN
    SET LOCAL lock_timeout = '3s';
    DROP TRIGGER IF EXISTS whole_room_is_room_booking ON public.document_line;
    CREATE TRIGGER whole_room_is_room_booking
        BEFORE INSERT
        ON public.document_line
        FOR EACH ROW WHEN (NOT NEW.share_owner AND NOT NEW.share_mate)
        EXECUTE FUNCTION public.trigger_document_line_whole_room_is_room_booking();
    RAISE NOTICE 'V0141: whole_room_is_room_booking installed';
EXCEPTION
    WHEN lock_not_available THEN
        RAISE WARNING 'V0141: document_line was busy, so whole_room_is_room_booking was NOT installed. Whole-room bookings can still land unflagged, which costs their booker the ability to share the room; run it by hand at a quieter moment';
    WHEN insufficient_privilege THEN
        RAISE WARNING 'V0141: the migrating role may not create a trigger on document_line; whole_room_is_room_booking was NOT installed';
END $$;

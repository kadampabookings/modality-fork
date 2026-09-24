-- V0112: a `removed` flag on event, building, building_zone and pool — and a booking never loses its event.
--
-- WHY. The back office's delete buttons for recurring events, buildings, building zones and pools all
-- set `removed = true`, but none of these tables had the column (nor the domain model the field), so
-- the server refused every one of those deletes before running it. They had never worked.
--
-- WHAT THE BACK OFFICE NOW DOES. Three outcomes, decided when the user deletes:
--   * REFUSED while live data still depends on the row (an active room in the building or zone, an
--     open room configuration on the pool, bookings on an event that has not ended), with the reason;
--   * HARD DELETE when nothing refers to it: the row goes, with what it owns (a building's zones, a
--     recurring event's sessions);
--   * SOFT DELETE — this flag — when only history still refers to it: the hard delete is refused by a
--     foreign key, so the row is kept for that history and hidden from the lists and pickers.
--
-- The database decides between hard and soft: every foreign key into building, building_zone and
-- pool, and — after this script — every one into event that holds records, is NO ACTION, so a
-- referenced row makes the hard delete fail with 23503 and the back office falls back to setting this
-- flag. The one exception left is push_subscription_recipient_event_id_fkey (CASCADE): Postgres would
-- drop the push opt-out history rather than refuse, so the back office checks for it itself first.
-- (allocation_rule, bookable_period and cart_message also cascade: they are the event's own
-- configuration and go with it; a mate_invite_token cannot exist without a booking.)
--
-- NOT NULL: DQL `!removed` compiles to `not (removed)`, which would silently drop rows where the flag
-- is NULL. The constant default is stored as metadata, so no table is rewritten.

ALTER TABLE public.event
    ADD COLUMN IF NOT EXISTS removed boolean DEFAULT false NOT NULL;

ALTER TABLE public.building
    ADD COLUMN IF NOT EXISTS removed boolean DEFAULT false NOT NULL;

ALTER TABLE public.building_zone
    ADD COLUMN IF NOT EXISTS removed boolean DEFAULT false NOT NULL;

ALTER TABLE public.pool
    ADD COLUMN IF NOT EXISTS removed boolean DEFAULT false NOT NULL;

COMMENT ON COLUMN public.event.removed IS
    'Deleted from the back office but kept, because bookings, mails or other records still refer to '
    'it. Hidden from event lists and pickers; the event is also closed to new front-office bookings.';

COMMENT ON COLUMN public.building.removed IS
    'Deleted from the back office but kept, because rooms that have been removed still refer to it. '
    'Hidden from the building lists and pickers; its zones are removed with it.';

COMMENT ON COLUMN public.building_zone.removed IS
    'Deleted from the back office but kept, because rooms that have been removed still refer to it. '
    'Hidden from the zone lists and pickers.';

COMMENT ON COLUMN public.pool.removed IS
    'Deleted from the back office but kept, because past bookings or room configurations still refer '
    'to it. Hidden from the pool lists and pickers.';

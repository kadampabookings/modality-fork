-- V0142: resource.setup_beds and resource.setup_date — the beds a room is set up with, and since when.
--
-- An event can need a room set up differently from usual: an extra bed added before it, taken out
-- again after. Household volunteers do that work, and the household screens need to know it is done,
-- or the same room stays on their list as a change still to make. Nothing recorded it: a room's beds
-- were only ever its resource_configuration capacity, which says what may be BOOKED, not what is
-- physically standing in the room today.
--
-- setup_beds is the number of beds currently set up in the room; setup_date is when they were last
-- set up, as local wall-clock time like last_cleaning_date and last_inspection_date beside it. Both
-- NULL means the room's setup has never been recorded, so a room nobody has touched reads as "unknown",
-- not as "set up with zero beds". A negative count is a bug, refused where it is written.
--
-- No person is recorded, deliberately: only when, never who. Who carried the bed is not needed to
-- know the room is ready, and leaving it out keeps a volunteer's working day out of the table.
--
-- Nullable, no default, so ADD COLUMN is metadata only; the check scans a table of rooms whose new
-- column is NULL throughout. record_changes_for_notification_resource() lists its columns by name
-- (site_id, site_item_family_id, name, site_item_family_code, building_id), so it ignores these two,
-- as it ignores the cleaning dates; trigger_resource_duplicate() names the columns it copies, so a
-- duplicated room starts with no recorded setup, which is true of it.

ALTER TABLE resource
    ADD COLUMN IF NOT EXISTS setup_beds smallint,
    ADD COLUMN IF NOT EXISTS setup_date timestamp without time zone;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'resource_setup_beds_check' AND conrelid = 'public.resource'::regclass) THEN
        ALTER TABLE resource
            ADD CONSTRAINT resource_setup_beds_check CHECK (setup_beds IS NULL OR setup_beds >= 0);
    END IF;
END
$do$;

COMMENT ON COLUMN resource.setup_beds IS
    'Number of beds currently set up in the room, as last recorded by household (e.g. an extra bed '
    'added for an event, or removed after). NULL: never recorded. Never negative.';

COMMENT ON COLUMN resource.setup_date IS
    'When the room''s beds were last set up (setup_beds recorded), local wall-clock time like '
    'last_cleaning_date. NULL: never recorded. Who did it is deliberately not stored.';

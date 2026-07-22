-- V0041: event 1901 parking booked twice per date — repair the data, guard the producer.
--
-- Every parking booking of event 1901 (US NEDC 2026) got 2 attendances per date,
-- because parking scheduled items existed TWICE for the event window:
--   * venue-global rows (event_id NULL, site 2502) — the venue's normal schedule,
--     like every other bookable item there; referenced by ALL parking attendances;
--   * event-bound copies (event_id 1901), created by copy_event_scheduled_items(1839, 1901):
--     the source event US Festival 2026 had event-bound parking, so the copy recreated
--     it on top of the venue-global rows. Referenced by NOTHING (insert-time
--     normalization repoints attendance.scheduled_item_id to the venue-global row).
-- The policy/booking queries load BOTH sets (bound to the event OR unbound at the
-- venue in the event window), and the onsite-event form books one attendance per
-- scheduled item — so each parking date was submitted twice in the same booking.
-- A database-wide sweep found no other event/family with this collision.
--
-- No billing impact (parking is free at this event: no applicable rate, price_net 0)
-- and no availability impact (no resource_configuration for parking at site 2502).
--
-- Two parts, mirroring V0038's shape (repair the data, fix the producer):
--   1. Repair: delete the surplus attendance rows (keep the lowest id per line+date;
--      the rows of a pair are identical) and the orphan event-bound parking scheduled
--      items. Both deletes derive their targets — idempotent, safe to re-run on an
--      environment already repaired manually (staging, 2026-07-22: 202 + 14 rows).
--   2. Producer: copy_event_scheduled_items now SKIPS a source item whose destination
--      (site, item, shifted date) is already covered by a venue-global scheduled item,
--      mapping it to the existing global row so boundary resolution still works.
--
-- set_transaction_parameters(true): the attendance deletes cascade into the deferred
-- price-recompute/allocation triggers at COMMIT, which read the transaction_parameter
-- session temp table the KBS server normally provides — create it or COMMIT fails.

DO $$ BEGIN PERFORM set_transaction_parameters(true); END $$;  -- DO/PERFORM: side-effect call, no result set

-- ── 1. Data repair ──────────────────────────────────────────────────────────

DO $$
DECLARE
    v_att integer;
    v_si  integer;
BEGIN
    -- Surplus attendances on event 1901 parking lines: keep the lowest-id row
    -- per (document_line, date). Parking family: item_family.code = 'park'.
    DELETE FROM attendance
    WHERE id IN (
        SELECT id FROM (
            SELECT a.id,
                   row_number() OVER (PARTITION BY a.document_line_id, a.date ORDER BY a.id) AS rn
            FROM attendance a
            JOIN document_line dl ON dl.id = a.document_line_id
            JOIN document d ON d.id = dl.document_id
            JOIN item i ON i.id = dl.item_id
            JOIN item_family f ON f.id = i.family_id
            WHERE d.event_id = 1901
              AND f.code = 'park'
        ) t
        WHERE rn > 1);
    GET DIAGNOSTICS v_att = ROW_COUNT;

    -- Orphan event-bound parking scheduled items: only rows that duplicate an
    -- existing venue-global row and that no attendance references (any other FK
    -- reference would abort the migration loudly — none exist by verification).
    DELETE FROM scheduled_item si
    WHERE si.event_id = 1901
      AND si.item_id IN (SELECT id FROM item
                         WHERE family_id = (SELECT id FROM item_family WHERE code = 'park'))
      AND EXISTS (SELECT 1 FROM scheduled_item g
                  WHERE g.event_id IS NULL AND g.site_id = si.site_id
                    AND g.item_id = si.item_id AND g.date = si.date)
      AND NOT EXISTS (SELECT 1 FROM attendance a WHERE a.scheduled_item_id = si.id);
    GET DIAGNOSTICS v_si = ROW_COUNT;

    RAISE NOTICE 'V0041: deleted % surplus parking attendances and % duplicate event-bound parking scheduled items for event 1901', v_att, v_si;
END $$;

-- ── 2. copy_event_scheduled_items(): skip items covered by a venue-global row ──
--
-- Same body as before except the GUARD at the top of the ScheduledItem loop
-- (and the skip counter in the final NOTICE). Canonical copy also kept in
-- scripts/copy-event-scheduled-items.sql.

CREATE OR REPLACE FUNCTION copy_event_scheduled_items(src_event_id integer, dst_event_id integer)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_src_start  date;
    v_dst_start  date;
    v_days       integer;
    v_new_id     integer;
    v_existing_global integer;
    v_bound_item integer;
    v_site       integer;
    v_item       integer;
    v_date       date;
    rec          record;
    v_si_count    integer := 0;
    v_si_skipped  integer := 0;
    v_sb_count    integer := 0;
    v_phase_count integer := 0;
    v_part_count  integer := 0;
    v_cov_count   integer := 0;
    v_sel_count   integer := 0;
BEGIN
    IF src_event_id = dst_event_id THEN
        RAISE EXCEPTION 'Source and destination events must differ (both = %)', src_event_id;
    END IF;

    SELECT start_date INTO v_src_start FROM event WHERE id = src_event_id;
    SELECT start_date INTO v_dst_start FROM event WHERE id = dst_event_id;
    IF v_src_start IS NULL THEN
        RAISE EXCEPTION 'Source event % not found (or has no start_date)', src_event_id;
    END IF;
    IF v_dst_start IS NULL THEN
        RAISE EXCEPTION 'Destination event % not found (or has no start_date)', dst_event_id;
    END IF;

    -- Safety guard: refuse to run if the destination already has scheduled
    -- items, so the function can't silently duplicate them. Remove if wanted.
    IF EXISTS (SELECT 1 FROM scheduled_item WHERE event_id = dst_event_id) THEN
        RAISE EXCEPTION 'Destination event % already has scheduled items; aborting to avoid duplicates', dst_event_id;
    END IF;

    -- The day gap to shift every date by (matches shift_event_for()).
    v_days := v_dst_start - v_src_start;

    -- old_id -> new_id maps, used to repoint internal references between passes.
    DROP TABLE IF EXISTS _map_si, _map_sb, _map_phase, _map_part;
    CREATE TEMP TABLE _map_si    (old_id integer PRIMARY KEY, new_id integer NOT NULL) ON COMMIT DROP;
    CREATE TEMP TABLE _map_sb    (old_id integer PRIMARY KEY, new_id integer NOT NULL) ON COMMIT DROP;
    CREATE TEMP TABLE _map_phase (old_id integer PRIMARY KEY, new_id integer NOT NULL) ON COMMIT DROP;
    CREATE TEMP TABLE _map_part  (old_id integer PRIMARY KEY, new_id integer NOT NULL) ON COMMIT DROP;

    ------------------------------------------------------------------
    -- 1) ScheduledItem (self-bookable, bound to the source event).
    ------------------------------------------------------------------
    FOR rec IN
        SELECT * FROM scheduled_item
         WHERE event_id = src_event_id
           AND bookable_scheduled_item_id = id
         ORDER BY id
    LOOP
        -- GUARD: skip when a venue-global scheduled item already covers the
        -- destination (site, item, shifted date) — the policy/booking queries load
        -- both event-bound and venue-global items, so copying would make the option
        -- bookable twice per date (bit us on event 1901: parking copied from event
        -- 1839 landed on top of the venue's global parking rows, and every booking
        -- got 2 attendances per date). Map the source row to the existing global
        -- row so boundary resolution (Case A below) still lands on a valid target.
        SELECT id INTO v_existing_global FROM scheduled_item
         WHERE event_id IS NULL AND site_id = rec.site_id AND item_id = rec.item_id
           AND date = rec.date + v_days
         ORDER BY id LIMIT 1;
        IF v_existing_global IS NOT NULL THEN
            RAISE NOTICE 'Skipping scheduled_item % (site %, item %, date % -> %): venue-global scheduled_item % already covers it',
                rec.id, rec.site_id, rec.item_id, rec.date, rec.date + v_days, v_existing_global;
            INSERT INTO _map_si VALUES (rec.id, v_existing_global);
            v_si_skipped := v_si_skipped + 1;
            CONTINUE;
        END IF;

        INSERT INTO scheduled_item (
            timeline_id, event_id, site_id, item_id, date, start_time, end_time,
            available, online, resource, program_scheduled_item_id, name, label_id,
            teacher_id, expiration_date, vod_delayed, published, bookable_scheduled_item_id,
            comment, comment_label_id, cancelled, buddha_id, arrival_site_id)
        VALUES (
            rec.timeline_id, dst_event_id, rec.site_id, rec.item_id,
            rec.date + v_days, rec.start_time, rec.end_time,
            rec.available, rec.online, rec.resource,
            NULL,                                                   -- program_scheduled_item_id
            rec.name, rec.label_id, rec.teacher_id,
            rec.expiration_date + (v_days || ' days')::interval,
            rec.vod_delayed, rec.published,
            NULL,                                                   -- bookable_scheduled_item_id (trigger -> self)
            rec.comment, rec.comment_label_id, rec.cancelled, rec.buddha_id, rec.arrival_site_id)
        RETURNING id INTO v_new_id;

        INSERT INTO _map_si VALUES (rec.id, v_new_id);
        v_si_count := v_si_count + 1;
    END LOOP;

    ------------------------------------------------------------------
    -- 2) ScheduledBoundary rows referenced by the source event's parts/phases.
    ------------------------------------------------------------------
    FOR rec IN
        SELECT * FROM scheduled_boundary
         WHERE id IN (
             SELECT start_boundary_id FROM event_part  WHERE event_id = src_event_id
             UNION SELECT end_boundary_id   FROM event_part  WHERE event_id = src_event_id
             UNION SELECT start_boundary_id FROM event_phase WHERE event_id = src_event_id
             UNION SELECT end_boundary_id   FROM event_phase WHERE event_id = src_event_id)
         ORDER BY id
    LOOP
        -- Resolve which destination scheduled_item this boundary must point at.
        SELECT new_id INTO v_bound_item FROM _map_si WHERE old_id = rec.scheduled_item_id;
        IF FOUND THEN
            NULL;  -- Case A: -> the new copy of an event-bound scheduled item.
        ELSIF rec.scheduled_item_id IS NULL THEN
            v_bound_item := NULL;  -- date-only boundary: keep it item-less, just shift its date.
        ELSE
            -- Case B: boundary points at a non event-bound item (Lunch/Dinner/...).
            -- Bind to the existing destination item: same site & item, shifted date.
            SELECT site_id, item_id, date INTO v_site, v_item, v_date
              FROM scheduled_item WHERE id = rec.scheduled_item_id;
            SELECT id INTO v_bound_item FROM scheduled_item
             WHERE site_id = v_site AND item_id = v_item AND date = v_date + v_days
             ORDER BY id LIMIT 1;
            IF v_bound_item IS NULL THEN
                RAISE EXCEPTION 'copy_event_scheduled_items: no destination scheduled_item (site %, item %, date %) found to rebind boundary % (source item %)',
                    v_site, v_item, v_date + v_days, rec.id, rec.scheduled_item_id;
            END IF;
        END IF;

        INSERT INTO scheduled_boundary (event_id, scheduled_item_id, timeline_id, date, at_start_time)
        VALUES (dst_event_id, v_bound_item, rec.timeline_id, rec.date + v_days, rec.at_start_time)
        RETURNING id INTO v_new_id;

        INSERT INTO _map_sb VALUES (rec.id, v_new_id);
        v_sb_count := v_sb_count + 1;
    END LOOP;

    ------------------------------------------------------------------
    -- 3) EventPhase (all rows bound to the source event).
    ------------------------------------------------------------------
    FOR rec IN SELECT * FROM event_phase WHERE event_id = src_event_id ORDER BY id LOOP
        INSERT INTO event_phase (event_id, name, label_id, start_boundary_id, end_boundary_id)
        VALUES (
            dst_event_id, rec.name, rec.label_id,
            (SELECT new_id FROM _map_sb WHERE old_id = rec.start_boundary_id),
            (SELECT new_id FROM _map_sb WHERE old_id = rec.end_boundary_id))
        RETURNING id INTO v_new_id;

        INSERT INTO _map_phase VALUES (rec.id, v_new_id);
        v_phase_count := v_phase_count + 1;
    END LOOP;

    ------------------------------------------------------------------
    -- 4) EventPart (all rows bound to the source event).
    ------------------------------------------------------------------
    FOR rec IN SELECT * FROM event_part WHERE event_id = src_event_id ORDER BY id LOOP
        INSERT INTO event_part (event_id, name, label_id, start_boundary_id, end_boundary_id,
                                accommodation_change_allowed, hyt)
        VALUES (
            dst_event_id, rec.name, rec.label_id,
            (SELECT new_id FROM _map_sb WHERE old_id = rec.start_boundary_id),
            (SELECT new_id FROM _map_sb WHERE old_id = rec.end_boundary_id),
            rec.accommodation_change_allowed, rec.hyt)
        RETURNING id INTO v_new_id;

        INSERT INTO _map_part VALUES (rec.id, v_new_id);
        v_part_count := v_part_count + 1;
    END LOOP;

    ------------------------------------------------------------------
    -- 5) EventPhaseCoverage (all rows bound to the source event).
    ------------------------------------------------------------------
    FOR rec IN SELECT * FROM event_phase_coverage WHERE event_id = src_event_id ORDER BY id LOOP
        INSERT INTO event_phase_coverage (event_id, name, label_id, phase1_id, phase2_id, phase3_id, phase4_id)
        VALUES (
            dst_event_id, rec.name, rec.label_id,
            (SELECT new_id FROM _map_phase WHERE old_id = rec.phase1_id),
            (SELECT new_id FROM _map_phase WHERE old_id = rec.phase2_id),
            (SELECT new_id FROM _map_phase WHERE old_id = rec.phase3_id),
            (SELECT new_id FROM _map_phase WHERE old_id = rec.phase4_id));
        v_cov_count := v_cov_count + 1;
    END LOOP;

    ------------------------------------------------------------------
    -- 6) EventSelection (all rows bound to the source event).
    ------------------------------------------------------------------
    FOR rec IN SELECT * FROM event_selection WHERE event_id = src_event_id ORDER BY id LOOP
        INSERT INTO event_selection (event_id, name, label_id, in_person, online, part1_id, part2_id, part3_id)
        VALUES (
            dst_event_id, rec.name, rec.label_id, rec.in_person, rec.online,
            (SELECT new_id FROM _map_part WHERE old_id = rec.part1_id),
            (SELECT new_id FROM _map_part WHERE old_id = rec.part2_id),
            (SELECT new_id FROM _map_part WHERE old_id = rec.part3_id));
        v_sel_count := v_sel_count + 1;
    END LOOP;

    RAISE NOTICE 'Copied from event % to event % (shift % days): % scheduled_item (% skipped, covered by venue-global), % scheduled_boundary, % event_phase, % event_part, % event_phase_coverage, % event_selection',
                 src_event_id, dst_event_id, v_days,
                 v_si_count, v_si_skipped, v_sb_count, v_phase_count, v_part_count, v_cov_count, v_sel_count;
END;
$$;

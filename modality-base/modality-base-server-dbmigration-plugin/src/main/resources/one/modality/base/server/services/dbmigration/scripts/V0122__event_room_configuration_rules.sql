-- V0122: the database side of the event room configuration rules.
--
-- Step 3 of docs/design/room-configuration-override-plan.md (kbs3-aggregate). An event room
-- configuration (resource_configuration.event_id set) is becoming the room's configuration for the
-- event's NIGHTS, seen by every event: from the first night (pre_date, else start_date) to the
-- night before the departure day (post_date, else end_date). For that to give a room exactly one
-- configuration per night, three rules must hold, whatever application writes the row (the KBS3
-- back office checks them before saving; KBS2's back office and the legacy Java one don't):
--
--   1. An event configuration takes its dates from its event: its own start_date/end_date are
--      CLEARED rather than refused, because KBS2's back office creates one by copying every field
--      of the global configuration (ResourcesGraphicActivity.forkIfGlobal), dates included, and a
--      global configuration versioned by KBS3 has a start date. None has dates today.
--   2. An ongoing event (event_type.ongoing: Volunteers, Residents…) has none: its end date keeps
--      moving forward. V0121 deleted the existing ones.
--   3. Two event configurations of the same room don't cover the same night. Only windows still
--      running are checked (last night >= today), so the past overlaps left on staging (10 pairs,
--      each a short event inside a longer one) never block anything.
--
-- The rule about covering another event only partly while the room is online stays in the back
-- office: it needs the events' ItemPolicies and is not about the configuration rows alone.
--
-- Two triggers enforce them:
--   - check_overlap on resource_configuration (from the KBS2 era, in no migration) is extended: its
--     function keeps its two existing checks and gains the three rules, and it now also fires when
--     event_id changes;
--   - check_room_configurations on event, new: moving an event's dates, or giving it an ongoing
--     type, must not break rules 2 and 3 for its configurations.
-- A partial index on resource_configuration(event_id) serves the event trigger's look-up (91k rows,
-- ~300 event configurations on staging).
--
-- No table or column is added: scripts/gdpr-anonymise needs no classification. The anonymiser only
-- rewrites resource_configuration.comment and no event date, so neither trigger fires on it.

CREATE INDEX IF NOT EXISTS resource_configuration_event_id_idx
    ON resource_configuration (event_id) WHERE event_id IS NOT NULL;

-- ── resource_configuration ────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION trigger_resource_configuration_check_overlap() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_event   record;
    v_other   record;
BEGIN
    -- Rule 1: an event configuration's dates are its event's.
    IF NEW.event_id IS NOT NULL THEN
        NEW.start_date := NULL;
        NEW.end_date := NULL;
    END IF;

    -- The two existing checks, unchanged.
    IF NEW.start_date IS NOT NULL AND NEW.end_date IS NOT NULL AND NEW.start_date > NEW.end_date THEN
        RAISE EXCEPTION 'end_date cannot be before start_date in resource_configuration';
    END IF;
    IF EXISTS(SELECT * FROM resource_configuration
              WHERE id <> NEW.id AND resource_id = NEW.resource_id AND event_id IS NOT DISTINCT FROM NEW.event_id
                AND overlaps(start_date, end_date, NEW.start_date, NEW.end_date)) THEN
        RAISE EXCEPTION 'Resource configurations cannot overlap';
    END IF;

    IF NEW.event_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT e.name,
           coalesce(et.ongoing, false)               AS ongoing,
           coalesce(e.pre_date, e.start_date)        AS first_night,
           coalesce(e.post_date, e.end_date) - 1     AS last_night
      INTO v_event
      FROM event e LEFT JOIN event_type et ON et.id = e.type_id
     WHERE e.id = NEW.event_id;

    -- Rule 2.
    IF v_event.ongoing THEN
        RAISE EXCEPTION 'Room configuration refused: "%" is an ongoing event, which cannot have its own room configuration', v_event.name;
    END IF;

    -- Rule 3, for windows still running.
    IF v_event.last_night >= current_date THEN
        SELECT oe.name,
               coalesce(oe.pre_date, oe.start_date)      AS first_night,
               coalesce(oe.post_date, oe.end_date) - 1   AS last_night
          INTO v_other
          FROM resource_configuration o
          JOIN event oe ON oe.id = o.event_id
         WHERE o.resource_id = NEW.resource_id AND o.id <> NEW.id AND o.event_id <> NEW.event_id
           AND coalesce(oe.pre_date, oe.start_date) <= v_event.last_night
           AND coalesce(oe.post_date, oe.end_date) - 1 >= v_event.first_night
         LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'Room configuration refused: the room already has a configuration for "%" (nights % to %), which overlaps the nights of "%"',
                v_other.name, v_other.first_night, v_other.last_night, v_event.name;
        END IF;
    END IF;

    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS check_overlap ON resource_configuration;
CREATE TRIGGER check_overlap
    BEFORE INSERT OR UPDATE OF resource_id, start_date, end_date, event_id ON resource_configuration
    FOR EACH ROW EXECUTE FUNCTION trigger_resource_configuration_check_overlap();

-- ── event ─────────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION trigger_event_check_room_configurations() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_ongoing    boolean;
    v_first      date := coalesce(NEW.pre_date, NEW.start_date);
    v_last       date := coalesce(NEW.post_date, NEW.end_date) - 1;
    v_other      record;
BEGIN
    IF NOT EXISTS(SELECT 1 FROM resource_configuration WHERE event_id = NEW.id) THEN
        RETURN NEW;
    END IF;

    -- Rule 2: an event with room configurations can't become ongoing.
    SELECT coalesce(ongoing, false) INTO v_ongoing FROM event_type WHERE id = NEW.type_id;
    IF coalesce(v_ongoing, false) THEN
        RAISE EXCEPTION 'Event refused: "%" has its own room configurations, so it cannot be of an ongoing type', NEW.name;
    END IF;

    -- Rule 3: its new nights must not overlap another event's configuration of the same rooms.
    IF v_last >= current_date THEN
        SELECT r.name AS room, oe.name,
               coalesce(oe.pre_date, oe.start_date)      AS first_night,
               coalesce(oe.post_date, oe.end_date) - 1   AS last_night
          INTO v_other
          FROM resource_configuration c
          JOIN resource_configuration o ON o.resource_id = c.resource_id AND o.event_id <> NEW.id
          JOIN event oe ON oe.id = o.event_id
          JOIN resource r ON r.id = c.resource_id
         WHERE c.event_id = NEW.id
           AND coalesce(oe.pre_date, oe.start_date) <= v_last
           AND coalesce(oe.post_date, oe.end_date) - 1 >= v_first
         LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'Event refused: with these dates, the configuration of room "%" would overlap the one for "%" (nights % to %)',
                v_other.room, v_other.name, v_other.first_night, v_other.last_night;
        END IF;
    END IF;

    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS check_room_configurations ON event;
CREATE TRIGGER check_room_configurations
    BEFORE UPDATE OF start_date, end_date, pre_date, post_date, type_id ON event
    FOR EACH ROW
    WHEN (OLD.start_date IS DISTINCT FROM NEW.start_date OR OLD.end_date IS DISTINCT FROM NEW.end_date
       OR OLD.pre_date IS DISTINCT FROM NEW.pre_date OR OLD.post_date IS DISTINCT FROM NEW.post_date
       OR OLD.type_id IS DISTINCT FROM NEW.type_id)
    EXECUTE FUNCTION trigger_event_check_room_configurations();

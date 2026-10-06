-- V0121: delete the room configurations of ongoing events.
--
-- Step 1 of docs/design/room-configuration-override-plan.md (kbs3-aggregate). That plan makes an
-- event room configuration the room's configuration for the event's nights, seen by every event,
-- and forbids one on an event whose type is ongoing (event_type.ongoing: Volunteers, Residents,
-- Stays...): the end date of such an event keeps moving forward, so its configuration would keep
-- overriding the room's global configuration for everybody.
--
-- Today the only such rows are event 1858's ("Volunteering - kbs3", 2026-01-01 -> 2028-01-01):
-- 6 rows on staging, each identical to its room's global configuration and referenced by nothing,
-- so they currently change nothing. Production may hold others, so the delete is generic, and
-- guarded: a row is deleted only when
--   - no document_line and no allocation_rule references it (both foreign keys are NO ACTION, so
--     deleting a referenced row would fail anyway), and
--   - its room has a global configuration during the event, and every such global configuration
--     carries the same values (item, capacities, online, pool, allows_*). Name, comment and
--     last_cleaning_date are not compared: they don't affect booking or allocation.
-- Any ongoing-event row the guard keeps is reported with a NOTICE, to be settled by hand.
--
-- No trigger fires on a resource_configuration DELETE, so no transaction parameters are needed.
-- No table or column changes: scripts/gdpr-anonymise needs no update.

DO $$
DECLARE
    r         record;
    v_deleted integer := 0;
    v_kept    integer := 0;
BEGIN
    FOR r IN
        SELECT rc.id, rc.event_id, rc.resource_id,
               EXISTS(SELECT 1 FROM document_line dl WHERE dl.resource_configuration_id = rc.id) AS has_lines,
               EXISTS(SELECT 1 FROM allocation_rule ar
                      WHERE rc.id IN (ar.resource_configuration_id, ar.if_resource_configuration_id)) AS has_rules,
               EXISTS(SELECT 1 FROM resource_configuration g
                      WHERE g.resource_id = rc.resource_id AND g.event_id IS NULL
                        AND kbs_overlaps(coalesce(e.pre_date, e.start_date), coalesce(e.post_date, e.end_date),
                                         g.start_date, g.end_date)) AS has_global,
               EXISTS(SELECT 1 FROM resource_configuration g
                      WHERE g.resource_id = rc.resource_id AND g.event_id IS NULL
                        AND kbs_overlaps(coalesce(e.pre_date, e.start_date), coalesce(e.post_date, e.end_date),
                                         g.start_date, g.end_date)
                        AND (g.item_id, g.max, g.max_paper, g.max_reserved, g.online, g.pool_id,
                             g.allows_male, g.allows_female, g.allows_guest, g.allows_special_guest,
                             g.allows_volunteer, g.allows_resident, g.allows_resident_family,
                             g.allows_lay, g.allows_ordained)
                            IS DISTINCT FROM
                            (rc.item_id, rc.max, rc.max_paper, rc.max_reserved, rc.online, rc.pool_id,
                             rc.allows_male, rc.allows_female, rc.allows_guest, rc.allows_special_guest,
                             rc.allows_volunteer, rc.allows_resident, rc.allows_resident_family,
                             rc.allows_lay, rc.allows_ordained)) AS differs_from_global
        FROM resource_configuration rc
        JOIN event e ON e.id = rc.event_id
        JOIN event_type et ON et.id = e.type_id
        WHERE et.ongoing
        ORDER BY rc.id
    LOOP
        IF r.has_lines OR r.has_rules OR NOT r.has_global OR r.differs_from_global THEN
            v_kept := v_kept + 1;
            RAISE NOTICE 'V0121: kept resource_configuration % (event %, resource %): lines=%, allocation rules=%, global configuration=%, differs from global=%',
                r.id, r.event_id, r.resource_id, r.has_lines, r.has_rules, r.has_global, r.differs_from_global;
        ELSE
            DELETE FROM resource_configuration WHERE id = r.id;
            v_deleted := v_deleted + 1;
        END IF;
    END LOOP;
    RAISE NOTICE 'V0121: deleted % room configuration(s) of ongoing events, kept %', v_deleted, v_kept;
END $$;

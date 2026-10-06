-- V0123: the allocation trigger reads a room's configuration night by night, for every event.
--
-- Step 4 (A) of docs/design/room-configuration-override-plan.md (kbs3-aggregate), shipped with the
-- matching change to the booking form's availability (ServerPolicyServiceProvider) and the submit
-- controller. An event room configuration is the room's configuration for the event's NIGHTS, seen by
-- every event: from its first night (pre_date, else start_date) THROUGH its end date (post_date, else
-- end_date) — some events sell that last night (30 of 135 recent KBS3 events offer accommodation on it).
-- Back-to-back events then share one night, the departing event's end date being the arriving event's
-- first night: the ARRIVING event's configuration is in force on it. V0122 allows exactly that edge
-- overlap and nothing more, so there is at most one configuration per room and night.
--
-- This is V0082's deferred_allocate_document_line() with four changes, marked "V0123" in the body:
--
--   1. The configuration in force on a night is resource_configuration_applies_on() (new, below): an
--      event configuration whose event covers the night (from its first night through its end date,
--      unless another event configuration of the room starts that night), else a global configuration
--      covering it with no event configuration of the room on that night. Before, a booking saw only its OWN event's
--      configuration or the global one, so a course during ITTP was allocated to a dorm that ITTP's
--      offline configuration had closed.
--   2. Occupancy is counted per ROOM, across its configurations: a booking made before an event
--      configuration existed still points at the global row and still takes its bed.
--   3. A ROOM is chosen, not a configuration: it must offer a usable configuration on every night of
--      the line, with capacity on each (before, grouping by configuration checked only the nights that
--      configuration covered — a stay straddling an offline window would have been placed in the
--      room). The line points at the configuration in force on its first night. Preference: the room
--      the line is already in (before: the very configuration), then a room online on every night
--      (before: `first(online)` of the configuration).
--   4. The sold-out fallback ("does any room of this item exist on these nights") uses the same rule.
--
-- Back-office requests and lines with allocate unticked skip the capacity AND coverage checks, as they
-- skipped capacity before (they still prefer a room with the item on every night). Also: occupancy is
-- computed once per candidate room and night (one pass, both sums), and "moved by the system"
-- (system_allocated, the booking editor's notifications) compares ROOMS — a line can change
-- configuration while staying in its room. Everything else is V0082 byte for byte, minus two joins
-- the old configuration choice used. Then V0122's two check functions are redefined with this
-- migration's windows (see the end of the file).
--
-- No table or column is added: scripts/gdpr-anonymise needs no classification.

-- The configuration in force for a room on a night (see above). STABLE: it reads the tables.
CREATE OR REPLACE FUNCTION public.resource_configuration_applies_on(rc resource_configuration, night date)
    RETURNS boolean
    LANGUAGE sql STABLE
AS $function$
    SELECT CASE
        WHEN rc.event_id IS NOT NULL THEN EXISTS(
            SELECT 1 FROM event e
             WHERE e.id = rc.event_id
               AND coalesce(e.pre_date, e.start_date) <= night
               AND night <= coalesce(e.post_date, e.end_date)
               -- its end date, shared with an event arriving that night: the arriving one wins
               AND NOT (night = coalesce(e.post_date, e.end_date) AND night > coalesce(e.pre_date, e.start_date)
                        AND EXISTS(SELECT 1 FROM resource_configuration arc
                                     JOIN event ae ON ae.id = arc.event_id
                                    WHERE arc.resource_id = rc.resource_id AND arc.id <> rc.id
                                      AND coalesce(ae.pre_date, ae.start_date) = night)))
        ELSE kbs_overlaps(night, night, rc.start_date, rc.end_date)
         AND NOT EXISTS(
            SELECT 1 FROM resource_configuration erc
              JOIN event e ON e.id = erc.event_id
             WHERE erc.resource_id = rc.resource_id
               AND coalesce(e.pre_date, e.start_date) <= night
               AND night <= coalesce(e.post_date, e.end_date))
    END
$function$;

CREATE OR REPLACE FUNCTION public.deferred_allocate_document_line()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    doc document%ROWTYPE;
    backend_request bool;
    forced_soldout bool := false;
    kbs2_event bool;
    kbs3_event bool;
    requested_pool_id document_line.pool_id%TYPE;
    reserved_request bool := coalesce(NEW.reserved, false);
    preferred_resource_configuration_id document_line.resource_configuration_id%TYPE;
    final_pool_id document_line.pool_id%TYPE := NEW.pool_id;
    final_reserved document_line.reserved%TYPE := coalesce(NEW.reserved, false);
    final_resource_configuration_id document_line.resource_configuration_id%TYPE := NEW.resource_configuration_id;
    family_code item_family.code%TYPE;
    previous_room_id resource_configuration.resource_id%TYPE; -- V0123: the room the line was in before
    final_room_id resource_configuration.resource_id%TYPE;
BEGIN

    IF (OLD.trigger_defer_allocate = false and NEW.trigger_defer_allocate = true) THEN

        RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;

        select into doc * from document d where d.id=NEW.document_id; -- used to allocation rules criteria

        -- The first reason to raise soldout is when the associated option has been forced to be sold out
        IF (NEW.allocate) THEN -- skipping this control if allocate is unticked (allocate = 'Check if soldout' in the backend)
            select into forced_soldout force_soldout from option where (NEW.option_id=id or NEW.option_id is null and force_soldout and site_id=NEW.site_id and item_id=NEW.item_id) and (NEW.resource_configuration_id is null or (select item_id from resource_configuration where id=NEW.resource_configuration_id)<>NEW.item_id);
            if not found or not forced_soldout then
                select into forced_soldout ip.force_sold_out from item_policy ip join policy_scope ps on ps.id=ip.scope_id where (ip.force_sold_out and ps.event_id=doc.event_id and ps.site_id=NEW.site_id and ip.item_id=NEW.item_id) and (NEW.resource_configuration_id is null or (select item_id from resource_configuration where id=NEW.resource_configuration_id)<>NEW.item_id);
            end if;
            -- V0030 moved registration's manual override to sold_out_item: the row's PRESENCE forces the
            -- item sold out for the event, and a NULL site means every site it is offered at. Enforced for
            -- FRONT-OFFICE transactions only (set_transaction_parameters(false) — what both the KBS3 and
            -- the KBS2 booking forms set): the override closes public sales, while registration keeps
            -- placing guests, walk-ins and volunteers by hand from the back office, which is what the
            -- KBS3 control has meant since V0030. Same resource_configuration guard as the two legacy
            -- sources above. exists()/coalesce() keep forced_soldout a real boolean here — the legacy
            -- `select into` idiom leaves it NULL on a no-row result, which IF merely treats as false.
            if not coalesce(forced_soldout, false) and not get_transaction_parameter() then
                select into forced_soldout exists(
                    select 1 from sold_out_item soi
                     where soi.event_id=doc.event_id and soi.item_id=NEW.item_id
                       and (soi.site_id is null or soi.site_id=NEW.site_id)
                       and (NEW.resource_configuration_id is null or (select item_id from resource_configuration where id=NEW.resource_configuration_id)<>NEW.item_id));
            end if;
            if forced_soldout then
                RAISE EXCEPTION 'SOLDOUT site_id=%, item_id=% (option forced as sold out)', NEW.site_id, NEW.item_id;
            end if;
        END IF;

        if (not NEW.lock_allocation) then

-- trying to allocate through automatic allocation rules (usually for meals dining areas)
            select into final_resource_configuration_id resource_configuration_id from allocation_rule ar
                                                                                           left join language l on l.id=ar.if_language_id
                                                                                           left join organization o on o.id=doc.person_organization_id
                                                                                           join item i on i.id=NEW.item_id
                                                                                           join resource_configuration rc on rc.id=ar.resource_configuration_id
                                                                                           join event e on e.id=doc.event_id
            where ar.active and ar.event_id=doc.event_id and (NEW.resource_configuration_id is null or not doc.arrived) and ar.item_family_id=i.family_id and rc.item_id=i.id
              and (ar.if_language_id is null or l.iso_639_1 = doc.person_lang)
              and (ar.if_country_id is null or o.country_id = ar.if_country_id)
              and (ar.if_organization_id is null or doc.person_organization_id = ar.if_organization_id)
              and (not ar.if_child or doc.person_age < 18)
              and (not ar.if_carer or exists(select * from document where not cancelled and (person_carer1_document_id=doc.id or person_carer2_document_id=doc.id)))
              and (not ar.if_lay or not doc.person_ordained)
              and (not ar.if_ordained or doc.person_ordained)
              and (ar.if_ref_min is null or doc.ref >= ar.if_ref_min)
              and (ar.if_ref_max is null or doc.ref <= ar.if_ref_max)
              and (ar.if_site_id is null and ar.if_item_id is null or (ar.if_site_id is null or NEW.site_id = ar.if_site_id) and (ar.if_item_id is null or NEW.item_id = ar.if_item_id) or exists(select * from document_line dl2 where document_id=doc.id and not cancelled and (ar.if_site_id is null or dl2.site_id = ar.if_site_id) and (ar.if_item_id is null or dl2.item_id = ar.if_item_id)))
              and (ar.if_resource_configuration_id is null or exists(select * from document_line dl2 where document_id=doc.id and dl2.id <> NEW.id and not cancelled and resource_configuration_id=ar.if_resource_configuration_id))
              and (not ar.if_whole_event or not exists(select * from event e, generate_dates(e.start_date, e.end_date) d where e.id=doc.event_id and not exists(select * from attendance a join document_line dl on dl.id=a.document_line_id where dl.document_id=NEW.document_id and date=d)))
              and (not ar.if_partial_event or   exists(select * from event e, generate_dates(e.start_date, e.end_date) d where e.id=doc.event_id and not exists(select * from attendance a join document_line dl on dl.id=a.document_line_id where dl.document_id=NEW.document_id and date=d)))
            order by ar.ord limit 1;

            IF NOT FOUND THEN
                backend_request := get_transaction_parameter();
                select kbs3, coalesce(NEW.pool_id, case when TG_OP='INSERT' then default_pool_id end) into kbs3_event, requested_pool_id from event where id=doc.event_id;
                kbs2_event = not kbs3_event or doc.event_id in (1677, 1840, 1857);
                reserved_request := coalesce(NEW.reserved, false) or requested_pool_id is not null;
                preferred_resource_configuration_id := NEW.resource_configuration_id;
                RAISE NOTICE 'preferred_resource_configuration_id = %, requested_pool_id=%, reserved_request=%, backend_request=%, kbs3_event=%', preferred_resource_configuration_id, requested_pool_id, reserved_request, backend_request, kbs3_event;
                --- selecting all dates related to this resource allocation
                with dates as (select date from attendance a where a.document_line_id = NEW.id),
                     --- then all applicable configs on this period (partition-gated for the frontend)
                     date_resource_info as (select d.date,
                                                   rc.id as resource_configuration_id,
                                                   rc.resource_id,
                                                   r.site_id,
                                                   rc.item_id,
                                                   rc.max,
                                                   rc.max_reserved,
                                                   rc.pool_id as rc_pool_id,
                                                   rc.online
                                            from dates d,
                                                 resource_configuration rc
                                                     join resource r on rc.resource_id = r.id
                                                     join site s on r.site_id = s.id
                                                     join item i on i.id = NEW.item_id
                                            where s.id= NEW.site_id
                                              and rc.item_id=i.id
                                              -- V0123: the configuration in force on that night, whatever the booking's event:
                                              -- an event configuration whose event covers the night, else the global one.
                                              and resource_configuration_applies_on(rc, d.date)
                                              and (backend_request
                                                or kbs2_event and rc.online -- KBS2: historical pool-agnostic behaviour
                                                or kbs3_event -- KBS3: gender/lay + partition gate
                                                       and case when doc.person_male is null then rc.allows_male and rc.allows_female when doc.person_male then rc.allows_male else rc.allows_female end
                                                       and case when doc.person_ordained then rc.allows_ordained else rc.allows_lay end
                                                       and case when not reserved_request
                                                                then rc.online                        -- public partition
                                                                else requested_pool_id is null or rc.pool_id = requested_pool_id  -- reserved partition (pool match only when a pool was requested)
                                                           end
                                                )),
--- V0123: then each candidate room's occupancy per night, computed ONCE (excluding the line itself): per ROOM,
--- across its configurations, counted per partition (marker match) and physically (all lines)
                     occupancy as (select rco.resource_id,
                                          a.date,
                                          coalesce(sum(dl.quantity) filter (where dl.reserved = (kbs3_event and reserved_request)), 0) as partition_qty,
                                          coalesce(sum(dl.quantity), 0) as physical_qty
                                   from attendance a
                                            join document_line dl on a.document_line_id = dl.id
                                            join resource_configuration rco on rco.id = dl.resource_configuration_id
                                   where a.present
                                     and a.date in (select date from dates)
                                     and rco.resource_id in (select resource_id from date_resource_info)
                                     and dl.id <> NEW.id
                                     and dl.share_mate_owner_document_line_id is distinct from NEW.id
                                     and (backend_request and not backend_released or
                                          not backend_request and not frontend_released)
                                   group by rco.resource_id, a.date),
--- then the current number of reservations for each day and each candidate
                     date_resource_info_with_current as (select dri.date,
                                                                dri.resource_configuration_id,
                                                                dri.resource_id,
                                                                dri.site_id,
                                                                dri.item_id,
                                                                coalesce(o.partition_qty, 0) as current_partition,
                                                                coalesce(o.physical_qty, 0) as current_physical,
                                                                case when not kbs3_event then dri.max                        -- KBS2: whole room
                                                                     when not reserved_request then dri.max - coalesce(dri.max_reserved, 0) -- public partition
                                                                     else coalesce(dri.max_reserved, 0)                       -- reserved partition
                                                                end as partition_max,
                                                                dri.max as physical_max,
                                                                dri.online
                                                         from date_resource_info dri
                                                         left join occupancy o on o.resource_id = dri.resource_id and o.date = dri.date
                     )
                -- V0123: a ROOM is chosen, not a configuration — its configuration can change during the stay. It must
                -- offer a usable configuration on EVERY night (a public stay straddling an event that takes the room
                -- offline must not be allocated to it), with capacity on each; the line then points at the
                -- configuration in force on its first night. The room the line is already in is preferred, then a room
                -- with the item on every night (a back-office line skips the coverage check), then one online on every
                -- night.
                select (array_agg(resource_configuration_id order by date, resource_configuration_id))[1],
                       case when kbs3_event then requested_pool_id end, coalesce(kbs3_event and reserved_request, false)
                  into final_resource_configuration_id, final_pool_id, final_reserved
                from date_resource_info_with_current
                group by resource_id
                having (backend_request or not NEW.allocate
                        or count(distinct date) = (select count(*) from attendance a where a.document_line_id = NEW.id)
                       and min(partition_max - current_partition) >= NEW.share_owner_quantity
                       and min(physical_max - current_physical)  >= NEW.share_owner_quantity)
                order by case when resource_id = (select resource_id from resource_configuration where id = NEW.resource_configuration_id) then 0 else 1 end,
                         count(distinct date) desc,
                         bool_and(online) desc,
                         min(resource_configuration_id)
                limit 1;
                RAISE NOTICE 'final_resource_configuration_id = %, final_pool_id=%, final_reserved=%', final_resource_configuration_id, final_pool_id, final_reserved;
            END IF;

            IF NOT FOUND THEN
                IF (NEW.allocate) THEN
                    select into family_code f.code
                    from item i
                             join item_family f on f.id = i.family_id
                    where i.id = NEW.item_id;
                    with dates as (select date from attendance a where a.document_line_id = NEW.id),
                         date_resource_info as (select d.date,
                                                       rc.id as resource_configuration_id,
                                                       r.site_id,
                                                       rc.item_id,
                                                       rc.max
                                                from dates d,
                                                     resource_configuration rc
                                                         join resource r on rc.resource_id = r.id
                                                         join site s on r.site_id = s.id
                                                where resource_configuration_applies_on(rc, d.date)
                                                  and s.id= NEW.site_id
                                                  and rc.item_id= NEW.item_id)
                    select into final_resource_configuration_id resource_configuration_id
                    from date_resource_info
                    limit 1;
                    IF FOUND THEN -- not found means no resource at all => we skip soldout control in that case
                        RAISE EXCEPTION 'SOLDOUT site_id=%, item_id=% (no resource found)', NEW.site_id, NEW.item_id;
                    END IF;
                END IF;

                -- Unallocated line (typically a non-resource item — teaching, option…): reset ALL
                -- allocation fields explicitly. The no-row SELECT INTO above nulled every target
                -- variable, and reserved is NOT NULL — writing that NULL back would violate the
                -- constraint (found on the staging rehearsal: every booking with a teaching line
                -- failed on submit).
                final_resource_configuration_id := null;
                final_pool_id := null;
                final_reserved := false;
            END IF;

        end if;

        -- V0123: "moved" means another ROOM — a line can change configuration while staying in its room
        select resource_id into previous_room_id from resource_configuration where id = NEW.resource_configuration_id;
        select resource_id into final_room_id from resource_configuration where id = final_resource_configuration_id;
        if (backend_request and previous_room_id is distinct from final_room_id) then -- forcing notification to update the booking editor user interface with the new allocation name
            INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, final_resource_configuration_id, 'resource_id');
            INSERT into sys_log (table_name, update, oid, column_name) values ('resource', true, (select resource_id from resource_configuration where id=final_resource_configuration_id), 'name');
        end if;
        if (backend_request and (NEW.pool_id is distinct from final_pool_id or NEW.reserved is distinct from final_reserved)) then -- forcing notification to update the booking editor user interface with the new allocation/partition
            INSERT into sys_log (table_name, update, oid, column_name) values ('document_line', true, NEW.id, 'pool_id');
            INSERT into sys_log (table_name, update, oid, column_name) values ('document_line', true, NEW.id, 'reserved');
            INSERT into sys_log (table_name, update, oid, column_name) values ('pool', true, final_pool_id, 'name');
        end if;

        -- update the table
        update document_line set
                                 resource_configuration_id = final_resource_configuration_id,
                                 pool_id = final_pool_id,
                                 reserved = final_reserved,
                                 system_allocated   = case when (select resource_id from resource_configuration where id = OLD.resource_configuration_id) = final_room_id then system_allocated else previous_room_id is distinct from final_room_id end -- this indicates that the system changed the allocation (V0123: the room)
        where id=NEW.id;
        update document_line set
            trigger_defer_allocate = false
        where id=NEW.id;

-- Update the attendance table (for KBS3)
        RAISE NOTICE 'Updating attendances';
        update attendance au
        set scheduled_item_id = si.id
        from attendance a
                 join scheduled_item si
                      ON si.site_id=NEW.site_id -- Note: works only for KBS3 events (where document_line site directly matches with scheduled_item site)
                          and si.item_id = NEW.item_id and si.date = a.date and si.id = si.bookable_scheduled_item_id
        where a.id = au.id
          and a.document_line_id = NEW.id;

    END IF;

    RETURN NEW;
END;
$function$
;


-- ── V0122's checks, with the windows of this migration ─────────────────────────
-- V0122 counted an event's nights up to the day before its end date, so a one-night event (start date =
-- end date, no pre/post date) had none and was never checked, while the rule above puts its
-- configuration in force on that night: two configurations of a room could then apply at once. Both
-- check functions now use the same windows as resource_configuration_applies_on() — through the end
-- date, sharing only the edge night with an arriving event. Bodies otherwise as in V0122; the triggers
-- themselves are unchanged.

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
           coalesce(e.post_date, e.end_date)         AS last_night
      INTO v_event
      FROM event e LEFT JOIN event_type et ON et.id = e.type_id
     WHERE e.id = NEW.event_id;

    -- Rule 2.
    IF v_event.ongoing THEN
        RAISE EXCEPTION 'Room configuration refused: "%" is an ongoing event, which cannot have its own room configuration', v_event.name;
    END IF;

    -- Rule 3, for windows still running. V0123: windows run through the end date; two windows may share
    -- only the edge night where one event's end date is the other's first night (the arriving event's
    -- configuration is in force on it) — anything else, a one-night event included, is an overlap.
    IF v_event.last_night >= current_date THEN
        SELECT oe.name,
               coalesce(oe.pre_date, oe.start_date)      AS first_night,
               coalesce(oe.post_date, oe.end_date)       AS last_night
          INTO v_other
          FROM resource_configuration o
          JOIN event oe ON oe.id = o.event_id
         WHERE o.resource_id = NEW.resource_id AND o.id <> NEW.id AND o.event_id <> NEW.event_id
           AND coalesce(oe.pre_date, oe.start_date) <= v_event.last_night
           AND coalesce(oe.post_date, oe.end_date) >= v_event.first_night
           AND NOT (coalesce(oe.post_date, oe.end_date) = v_event.first_night AND coalesce(oe.pre_date, oe.start_date) < v_event.first_night
                 OR v_event.last_night = coalesce(oe.pre_date, oe.start_date) AND v_event.first_night < coalesce(oe.pre_date, oe.start_date))
         LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'Room configuration refused: the room already has a configuration for "%" (nights % to %), which overlaps the nights of "%"',
                v_other.name, v_other.first_night, v_other.last_night, v_event.name;
        END IF;
    END IF;

    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION trigger_event_check_room_configurations() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_ongoing    boolean;
    v_first      date := coalesce(NEW.pre_date, NEW.start_date);
    v_last       date := coalesce(NEW.post_date, NEW.end_date);
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
               coalesce(oe.post_date, oe.end_date)       AS last_night
          INTO v_other
          FROM resource_configuration c
          JOIN resource_configuration o ON o.resource_id = c.resource_id AND o.event_id <> NEW.id
          JOIN event oe ON oe.id = o.event_id
          JOIN resource r ON r.id = c.resource_id
         WHERE c.event_id = NEW.id
           AND coalesce(oe.pre_date, oe.start_date) <= v_last
           AND coalesce(oe.post_date, oe.end_date) >= v_first
           -- the shared edge night only (V0123, as in trigger_resource_configuration_check_overlap)
           AND NOT (coalesce(oe.post_date, oe.end_date) = v_first AND coalesce(oe.pre_date, oe.start_date) < v_first
                 OR v_last = coalesce(oe.pre_date, oe.start_date) AND v_first < coalesce(oe.pre_date, oe.start_date))
         LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'Event refused: with these dates, the configuration of room "%" would overlap the one for "%" (nights % to %)',
                v_other.room, v_other.name, v_other.first_night, v_other.last_night;
        END IF;
    END IF;

    RETURN NEW;
END $$;

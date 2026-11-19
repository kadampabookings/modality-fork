-- V0178: a meal sitting declared for an event serves that event's bookings only.
--
-- (V0177 is taken: erase_birthdates, renumbered from V0171 on the dob-to-age branch, ships first.)
--
-- WHY. Meal sittings (S1, S2…) are declared for big events only (Manjushri, event 1957 in November 2026, then
-- 1993): one resource per sitting at the venue, and one EVENT resource_configuration per sitting and event, with
-- the event's lunch item and the sitting's capacity (back-office "Meal sittings" page). Since V0123 an event
-- configuration is in force on its event's days for EVERY event's bookings — right for a room, which is the same
-- room whoever books it, wrong for a sitting: the volunteers, guest stays and weekly classes eating at the venue
-- on those days (staging: events 1858, 1989 and 2009 during 1957) would be put into the event's sittings, counted
-- against them, and refused as SOLDOUT by the front office once the sittings are full.
--
-- WHAT. deferred_allocate_document_line(): V0154's body with three additions, marked V0178. For a line whose item
-- is in the 'meals' family, an event configuration is a candidate only when its event is the booking's event (or
-- the event that one repeats), in BOTH places the function reads configurations: the allocation itself, and the
-- sold-out fallback ("does any configuration of this item exist on these days"). So a meal line of another event
-- finds no sitting, stays unallocated (resource_configuration_id null) and is not refused, as before any sitting
-- existed. Global configurations (event_id null) are unchanged, and so is every other family, accommodation
-- included. The allocation rules (allocation_rule) were already per event (ar.event_id = doc.event_id).
--
-- The booking form's availability (ServerPolicyServiceProvider, maleFemaleAvailabilities) applies the same rule
-- in the same change, so the form and the trigger agree on which sittings a booking can use.
--
-- DATA. Measured on staging (2026-11-19): no booking line points at a non-accommodation event configuration of
-- another event (0 rows), so no existing allocation reads differently; the 4 existing meal event configurations
-- keep serving their own event. Existing lines are not touched: the function runs only when a line is
-- (re)allocated. Transport and parking event configurations (5 on staging) keep V0123's rule: not asked for.
--
-- No table or column is added: scripts/gdpr-anonymise and scripts/gdpr-erasure need no change.
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

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
    own_event_meals bool; -- V0178: a meal line sees only its own event's event configurations
    booking_event_id event.id%TYPE; -- V0178: the booking's event, as an event configuration names it
BEGIN

    IF (OLD.trigger_defer_allocate = false and NEW.trigger_defer_allocate = true) THEN

        RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;

        select into doc * from document d where d.id=NEW.document_id; -- used to allocation rules criteria

        -- V0178: a MEAL line sees an event configuration (a sitting) only when it belongs to the booking's own
        -- event, or to the event it repeats. A sitting is declared for a big event; the other events at the
        -- venue on those days (volunteering, guest stays, a weekly class) do not eat in sittings, so their lines
        -- must stay unallocated, and must not be refused as SOLDOUT when the sittings are full. Accommodation
        -- keeps V0123's rule: a room is the same room whoever books it.
        select into own_event_meals coalesce(f.code = 'meals', false)
        from item i join item_family f on f.id = i.family_id
        where i.id = NEW.item_id;
        own_event_meals := coalesce(own_event_meals, false);
        select into booking_event_id coalesce(e.repeated_event_id, e.id) from event e where e.id = doc.event_id;

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
                                              -- V0178: a meal line, its own event's sittings only (see above).
                                              and (not own_event_meals or rc.event_id is null or rc.event_id in (doc.event_id, booking_event_id))
                                              -- V0154: a room made not bookable (resource_configuration.unavailable) is never allocated by the front
                                              -- office on those nights, whatever its partitions say: max_reserved = max leaves the RESERVED partition
                                              -- the whole room, which a reserved line with no requested pool would otherwise fit.
                                              and (backend_request or not rc.unavailable)
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
                                                  and rc.item_id= NEW.item_id
                                                  -- V0178: same rule as the allocation above. Without it, another
                                                  -- event's sittings on these days would make this line SOLDOUT.
                                                  and (not own_event_meals or rc.event_id is null or rc.event_id in (doc.event_id, booking_event_id)))
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

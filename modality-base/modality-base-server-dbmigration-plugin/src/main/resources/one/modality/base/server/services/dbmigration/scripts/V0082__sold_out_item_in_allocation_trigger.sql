-- V0082: make the allocation trigger honour sold_out_item, registration's manual "sold out" override.
--
-- V0030 moved that override out of item_policy.force_sold_out into sold_out_item (row presence =
-- forced sold out, NULL site = every site the item is offered at), and the KBS3 back office has
-- written there ever since. But deferred_allocate_document_line() — the server-side backstop that
-- raises SOLDOUT on submit — was never taught about the table: it still reads only the two legacy
-- sources, option.force_soldout and item_policy.force_sold_out. So a sold_out_item row was honoured
-- by whichever front-office sections happened to consult it (rooms, parking, transport, tours) and
-- by nothing else — a meal marked sold out for event 1901 was still bookable, and the database
-- accepted the line.
--
-- This is V0016's function body (the current definition) with ONE addition, in the forced-sold-out
-- block at the top: after the two legacy lookups, a third one against sold_out_item for the
-- document's event, the line's item, and a NULL-or-matching site. It raises the SAME exception text
-- ('SOLDOUT site_id=%, item_id=% (option forced as sold out)'), which ServerDocumentServiceProvider
-- parses into the sold-out result the wizard's recovery page handles. The legacy lookups are kept:
-- KBS2 still writes item_policy.force_sold_out (see V0030), and dropping them is that later
-- migration's job.
--
-- Scope, deliberately narrower than the legacy sources: it applies only to FRONT-OFFICE transactions
-- (get_transaction_parameter() is false — both booking forms set it; a back-office submit sets true),
-- only when the line allocates (NEW.allocate — "Check if soldout" in the back office), and a line
-- already allocated to a resource of this item is let through. So the KBS3 back office's per-room
-- sold-out buttons, which have written this table since V0030 without any database effect, still do
-- not refuse a back-office booking, a booking draft or a volunteering export — they close PUBLIC
-- sales, and the front office is the side that enforces. Everything below the block is byte-for-byte
-- V0016.
--
-- Known limits it inherits from the legacy sources: NEW.allocate is stored from the client's line (a
-- forged front-office line with allocate=false skips every sold-out source and the capacity check
-- alike), and trigger_document_line_defer_allocate never arms items coded 'pdej'/'din' (the French
-- festivals' breakfast and dinner), so for those two the backstop never runs.
--
-- Rehearsed on 2026-09-04 against the structure dump plus V0001-V0081 in a throwaway Postgres 17:
-- a front-office lunch line is refused with a site-less row and with a row naming its site, and
-- accepted with a row naming another site, another event, another item, with allocate unticked,
-- with a pre-allocated resource of the item, and from a back-office transaction. The staging
-- database has carried no triggers since the 2026-08-13 refresh, so it cannot exercise this.

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
                                                     join item rci on rci.id = rc.item_id
                                                     join event e on e.id = doc.event_id
                                            where s.id= NEW.site_id
                                              and rc.item_id=i.id
                                              -- Pick a single resource_configuration per resource & date: only this event's config or the global one
                                              -- (ignore other events' configs), preferring the event config when both an event and a global one overlap the date.
                                              and (rc.event_id = e.id
                                                or rc.event_id is null
                                                       and kbs_overlaps(d.date, d.date, rc.start_date, rc.end_date)
                                                       and not exists(select * from resource_configuration erc
                                                              where erc.resource_id = rc.resource_id
                                                                and erc.event_id = e.id)
                                                  )
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
--- then the current number of reservations (excluding itself) for each day and each resource,
--- counted per partition (marker match) and physically (all lines)
                     date_resource_info_with_current as (select dri.date,
                                                                dri.resource_configuration_id,
                                                                dri.site_id,
                                                                dri.item_id,
                                                                (select coalesce(sum(dl.quantity), 0)
                                                                 from attendance a
                                                                          join document_line dl on a.document_line_id = dl.id
                                                                          join document d on d.id = dl.document_id
                                                                 where a.present
                                                                   and a.date = dri.date
                                                                   and dl.id <> NEW.id
                                                                   and dl.share_mate_owner_document_line_id is distinct from NEW.id
                                                                   and dl.resource_configuration_id = dri.resource_configuration_id
                                                                   and dl.reserved = (kbs3_event and reserved_request)
                                                                   and (backend_request and not backend_released or
                                                                        not backend_request and not frontend_released)
                                                                ) as current_partition,
                                                                (select coalesce(sum(dl.quantity), 0)
                                                                 from attendance a
                                                                          join document_line dl on a.document_line_id = dl.id
                                                                          join document d on d.id = dl.document_id
                                                                 where a.present
                                                                   and a.date = dri.date
                                                                   and dl.id <> NEW.id
                                                                   and dl.share_mate_owner_document_line_id is distinct from NEW.id
                                                                   and dl.resource_configuration_id = dri.resource_configuration_id
                                                                   and (backend_request and not backend_released or
                                                                        not backend_request and not frontend_released)
                                                                ) as current_physical,
                                                                case when not kbs3_event then dri.max                        -- KBS2: whole room
                                                                     when not reserved_request then dri.max - coalesce(dri.max_reserved, 0) -- public partition
                                                                     else coalesce(dri.max_reserved, 0)                       -- reserved partition
                                                                end as partition_max,
                                                                dri.max as physical_max,
                                                                dri.online
                                                         from date_resource_info dri
                     )
                select resource_configuration_id, case when kbs3_event then requested_pool_id end, coalesce(kbs3_event and reserved_request, false)
                  into final_resource_configuration_id, final_pool_id, final_reserved
                from date_resource_info_with_current
                group by resource_configuration_id
                having (backend_request or not NEW.allocate
                        or min(partition_max - current_partition) >= NEW.share_owner_quantity
                       and min(physical_max - current_physical)  >= NEW.share_owner_quantity)
                order by case when resource_configuration_id = NEW.resource_configuration_id then 0 else 1 end,
                         first(online) desc,
                         resource_configuration_id
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
                                                where kbs_overlaps(d.date, d.date, rc.start_date, rc.end_date)
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

        if (backend_request and NEW.resource_configuration_id is distinct from final_resource_configuration_id) then -- forcing notification to update the booking editor user interface with the new allocation name
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
                                 system_allocated   = case when OLD.resource_configuration_id = final_resource_configuration_id then system_allocated else NEW.resource_configuration_id is distinct from final_resource_configuration_id end -- this indicates that the system changed the allocation
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

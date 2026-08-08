-- V0058: reject new FRONTOFFICE bookings on CLOSED events at the database level.
--
-- The FO wizard now shows a "Bookings Closed" gate page when Event.state = 'CLOSED'
-- (kbs3-react daba8393d), but that is UI-only: a stale tab opened before the event
-- closed, or a direct bus submit, could still insert a booking. This adds the DB
-- backstop: trigger_document_auto_ref() gains a CLOSED check mirroring the existing
-- EVENT_ON_HOLD one.
--
-- Scope — deliberately identical to the client gate:
-- * Inside the get_transaction_parameter() = false block => FRONTEND requests only.
--   Backoffice bookings by the registration team are unaffected (they bypass this
--   block entirely, exactly as they bypass the ON_HOLD and double-booking checks).
-- * The trigger fires on document INSERT only => FO modify/manage/pay flows on
--   existing bookings are unaffected.
--
-- The server maps unrecognised raises to a technical failure (no EVENT_CLOSED branch
-- in ServerDocumentServiceProvider.recover) — acceptable for a backstop the UI gate
-- normally prevents from ever firing.
--
-- Body below = live definition (staging, 2026-08-08, identical to the repo dump)
-- + the CLOSED check.

CREATE OR REPLACE FUNCTION public.trigger_document_auto_ref() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    -- Setting the booking ref
    select into new.ref count(*) + 1 from document where event_id = new.event_id;
    -- Setting the booking organization if not set (to be the same as event)
    if (new.organization_id is null) then
        select into new.organization_id e.organization_id from event e where e.id = new.event_id;
    end if;
    -- Switching the booking to online for KBS2 events that don't allow in-person bookings (ex: 2026 KMCF French Festival Online)
    if (select not kbs3 and not in_person_allowed from event where id = new.event_id) then
        new.in_person = false;
    end if;
    -- Setting the booking activity if not set (to be the same as event)
    if (new.activity_id is null) then
        select into new.activity_id e.activity_id from event e where e.id = new.event_id;
    end if;
    -- Setting the booking cart if not set (for frontend bookings only)
    if (get_transaction_parameter() = false /* ie frontend request */) then
        -- Checking if the event is on hold
        if (select state = 'ON_HOLD' from event where id = new.event_id) then
            raise exception 'EVENT_ON_HOLD';
        end if;
        -- Checking if the event is closed (new frontoffice bookings are no longer accepted;
        -- backoffice bookings skip this whole block and remain possible)
        if (select state = 'CLOSED' from event where id = new.event_id) then
            raise exception 'EVENT_CLOSED';
        end if;
        -- Now raising an exception on double bookings (unless it is a tester)
        if (exists(select *
                   from document d
                            join person p on p.id = d.person_id
                            join frontend_account fa on fa.id = p.frontend_account_id
                   where d.event_id = new.event_id
                     and d.person_id = new.person_id
                     and not d.cancelled
                     and not fa.tester)) then
            raise exception 'DOUBLEBOOKING';
        end if;
        if (new.cart_id is null) then
            -- Checking if there is already a booking made for that event with the same frontend account and reusing the same booking cart in that case
            select into new.cart_id cart_id
            from "document" d
                     join person p on p.id = d.person_id
                     join person np on np.id = new.person_id
            where event_id = new.event_id
              and p.frontend_account_id = np.frontend_account_id
              and cart_id is not null
            order by cart_id
            limit 1;
            -- Otherwise creating a new booking cart
            if (new.cart_id is null) then
                insert into cart
                (uuid)
                values (uuid_in(overlay(
                        overlay(md5(random()::text || ':' || clock_timestamp()::text) placing '4' from 13) placing
                        to_hex(floor(random() * (11 - 8 + 1) + 8)::int)::text from 17)::cstring))
                returning id into new.cart_id;
            end if;
        end if;
    end if;
    return new;
END
$$;

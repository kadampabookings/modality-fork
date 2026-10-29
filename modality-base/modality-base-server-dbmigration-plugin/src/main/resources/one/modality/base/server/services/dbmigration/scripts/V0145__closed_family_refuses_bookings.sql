-- V0145: an event that is RECONCILED, FINALISED or ARCHIVED refuses new front-office bookings,
-- as a CLOSED one already does.
--
-- WHY. V0058 taught trigger_document_auto_ref() to refuse a new front-office booking on a CLOSED
-- event, and it tested exactly that value: state = 'CLOSED'. Nothing wrote the three states that
-- come after CLOSED in the event lifecycle, so the gap stayed latent. The Event Lifecycle screen
-- starts writing them -- staff mark an event reconciled once its accounts are settled, and a
-- committed personal-data removal seals a reconciled event FINALISED -- and each of those moves
-- would have REOPENED the event to new front-office bookings, because the test no longer matched.
-- Reconciling is exactly when a late booking does the most harm: it lands in accounts that have
-- just been declared settled, on an event whose guests' data is about to be removed.
--
-- WHAT. One condition changes. The EVENT_CLOSED test is now
--     state IN ('CLOSED', 'RECONCILED', 'FINALISED', 'ARCHIVED')
-- the closed-to-booking family, the same set the React apps share (kbs3-react
-- shared/src/domain/event-state.ts). All four are labels of the event_state enum. The EVENT_ON_HOLD
-- test is unchanged, and so is every other line: the body below is V0109's, the latest definition
-- and the one production runs, with that condition and its comment changed. Re-checked on 2026-10-28:
-- no migration through V0144 redefines the function, and the 2026-10-22 production schema dump holds
-- V0109's body. A comment-stripped, whitespace-normalised diff of the two bodies shows that one
-- statement and nothing else.
--
-- SCOPE, unchanged from V0058:
--   * FRONT OFFICE ONLY. The test sits inside the get_transaction_parameter() = false block, so a
--     back-office booking (the registration team adding somebody to a reconciled event on purpose)
--     still passes, exactly as it already bypasses the CLOSED, ON_HOLD and double-booking checks.
--   * INSERT ONLY. The trigger fires on document INSERT, so existing bookings can still be modified
--     and paid (balances, and recordings until audio_closing_date); what closed means for those is
--     the front office's decision, not this backstop's.
--   * An event with no state (the legacy KBS2 lifecycle, NULL) is unaffected: the test is NULL and
--     the IF is not taken, as before.
--
-- The raise is the same EVENT_CLOSED, so whatever reads it today reads it unchanged.
--
-- NOT HERE. The legacy Java front office needs no change: EventLifeCycle.canBookNow() only books a
-- KBS3 event that is OPEN (or TESTING, for testers), and isClosed() falls back to the end date, which
-- has always passed by the time an event is reconciled. No BEGIN/COMMIT: the runner wraps every
-- pending script in one transaction.

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
    -- Booking rules the back office is trusted to override (frontend requests only)
    if (get_transaction_parameter() = false /* ie frontend request */) then
        -- Checking if the event is on hold
        if (select state = 'ON_HOLD' from event where id = new.event_id) then
            raise exception 'EVENT_ON_HOLD';
        end if;
        -- Checking if the event is closed (new frontoffice bookings are no longer accepted;
        -- backoffice bookings skip this whole block and remain possible). V0145: closed means the
        -- whole closed family, CLOSED and the three states after it (RECONCILED, FINALISED,
        -- ARCHIVED); testing CLOSED alone reopened an event to bookings once it was reconciled.
        if (select state IN ('CLOSED', 'RECONCILED', 'FINALISED', 'ARCHIVED') from event where id = new.event_id) then
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
    end if;
    -- Setting the booking cart if not set — for ALL bookings, front-office and back-office
    -- alike, because the document service dereferences document.cart when building its result.
    if (new.cart_id is null) then
        if (new.payer_id is not null) then
            -- V0109: a booking with a payer shares that payer's cart for the event. Inert today
            -- (AddDocumentEvent cannot carry a payer, which is set by a later update and handled
            -- by trigger_document_payer_cart); kept so an insert that does carry one follows the
            -- same rule.
            if (new.person_id is null) then
                raise exception 'PAYER_NEEDS_PERSON';
            end if;
            if (new.event_id is not null) then
                perform pg_advisory_xact_lock(new.event_id, new.payer_id);
            end if;
            new.cart_id := public.document_payer_cart_id(new.event_id, new.payer_id, new.id);
        else
            -- Reusing the cart of a booking made for that event with the same frontend account
            -- (V0109: never a payer's cart).
            new.cart_id := public.document_account_cart_id(new.event_id, new.person_id, new.id);
        end if;
        -- Otherwise creating a new booking cart (V0109: CSPRNG token)
        if (new.cart_id is null) then
            new.cart_id := public.new_cart_id();
        end if;
    end if;
    return new;
END
$$;

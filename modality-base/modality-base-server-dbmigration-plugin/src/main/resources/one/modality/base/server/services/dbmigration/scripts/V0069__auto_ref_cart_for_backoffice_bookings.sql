-- V0069: give EVERY new booking a cart, not just front-office ones.
--
-- THE BUG
--   Creating a booking from the BACK OFFICE commits the row and then fails the endpoint with
--       NullPointerException: Cannot invoke "Cart.getPrimaryKey()"
--       because "Document.getCart()" is null
--   in ServerDocumentServiceProvider.submitChangesAndPrepareResult(). The client therefore
--   reports a failure for a booking that EXISTS, inviting a retry that duplicates it.
--
-- THE CHAIN
--   1. modality-fork 783d4b333 ("back-office room moves — honour isBackoffice()") made the
--      server submit under Triggers.backOfficeTransaction when the session declares
--      backoffice=true, i.e. get_transaction_parameter() = true.
--   2. The cart block below sat inside the `= false /* frontend */` guard, so back-office
--      submits left document.cart_id NULL.
--   3. submitChangesAndPrepareResult() reloads "ref,cart.uuid" whenever cartUuid is null and
--      then dereferences document.getCart() unconditionally => NPE.
--   Every back-office booking CREATION is affected: the React back office's "New booking"
--   form and the volunteering KBS3 export alike. Edits and room moves are unaffected — those
--   documents already carry a cart.
--
-- THE FIX
--   The cart block moves OUT of the front-office-only guard. A cart is the booking's payment
--   container, not a front-office concept: the payment page URL and the guest magic link are
--   both keyed on it, so a staff-created booking wants one just as much.
--
--   The genuinely front-office-only rules STAY inside the guard, byte-identical to V0058:
--     * EVENT_ON_HOLD  — staff must be able to book an event that is on hold;
--     * EVENT_CLOSED   — staff must be able to book a closed event (V0058's intent, preserved);
--     * DOUBLEBOOKING  — staff must be able to add a second booking deliberately.
--
--   Cart reuse is unchanged: a linked person rebooking the same event reuses the cart of their
--   frontend account, while a guest booking (person_id NULL) matches nothing in the reuse
--   SELECT and gets a fresh cart.
--
-- BASED ON the V0058 body (the current definition) — NOT on scripts/kbs-database-structure.sql,
-- which predates V0058's EVENT_CLOSED guard.

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
    end if;
    -- Setting the booking cart if not set — for ALL bookings, front-office and back-office
    -- alike, because the document service dereferences document.cart when building its result.
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
    return new;
END
$$;

-- Repair the bookings already created without a cart while the bug was live.
--
-- Bounded to documents created on/after the regression commit (2026-07-07): every one of those
-- is a back-office creation, because a front-office booking always got its cart from the trigger
-- above. Older cart-less documents (KBS2-era imports and the like) are deliberately left alone,
-- and rows with a NULL creation_date are excluded by the comparison.
WITH missing AS (
    SELECT id, row_number() OVER (ORDER BY id) AS rn
    FROM document
    WHERE cart_id IS NULL
      AND creation_date >= timestamp '2026-07-07 00:00:00'
), created AS (
    INSERT INTO cart (uuid)
    SELECT uuid_in(overlay(
            overlay(md5(random()::text || ':' || clock_timestamp()::text) placing '4' from 13) placing
            to_hex(floor(random() * (11 - 8 + 1) + 8)::int)::text from 17)::cstring)
    FROM missing
    RETURNING id
), paired AS (
    SELECT m.id AS document_id, c.id AS cart_id
    FROM missing m
             JOIN (SELECT id, row_number() OVER (ORDER BY id) AS rn FROM created) c ON c.rn = m.rn
)
UPDATE document d
SET cart_id = p.cart_id
FROM paired p
WHERE d.id = p.document_id;

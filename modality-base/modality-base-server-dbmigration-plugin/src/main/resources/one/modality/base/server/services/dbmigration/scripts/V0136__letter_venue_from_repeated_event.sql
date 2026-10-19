-- V0136: an event with no venue of its own resolves its letters on the venue of the event it
-- repeats.
--
-- WHY. Letters can be scoped to a site (letter.site_id, V0037), matched against the booking's
-- event.venue_id. A repeat event (event.repeated_event_id) is a further series of the same
-- programme, held at the same place, but it may be created without a venue of its own. Its
-- bookings then never match a site-scoped letter, and nothing says so: on staging (2026-10-19)
-- the STTP WIC and WOC events (2022, 2023, repeating 2021 at site 2225) received 63 bookings and
-- not one "Booking cart" letter (letter 26933, site 2225 + event type 48) -- the trigger found
-- no applicable letter and generated nothing, with no error and no mail id consumed.
--
-- WHAT.
--   * event_letter_venue_id(event): the event's own venue, else the venue of the event it
--     repeats. One level only: repeat events point at the original, never at another repeat
--     (6 repeat events on staging, 0 chained), and the back office's letter pickers resolve the
--     same single level (buildInheritedLetterCondition).
--   * trigger_document_generate_mails_on_booking ranks letters with it instead of e.venue_id.
--     Body = V0125's verbatim apart from that: the venue is computed once per booking (LATERAL),
--     not once per candidate letter.
--   An event's OWN venue always wins; only a missing venue falls back. The event type does not
--   fall back: a repeat event carries its own type.
--
-- NOT CHANGED.
--   * letter_scope_rank(): stays a pure function of its arguments; callers choose the venue.
--   * trigger_frontend_account_generate_password_email (V0098): no "Send password letter" is
--     site-scoped (0 of 1484 on staging), so the fallback would change nothing there.
--   * KBS2's AutomaticLettersActor resolves scopes with its own SQL (KBS2 repo): KBS2 events are
--     not repeat events of KBS3 programmes, so it is left as is.
--
-- EFFECT ON EXISTING DATA. None retroactively: the trigger runs when a booking is created. On
-- staging, 4 repeat events have no venue (1423, 1424, 1737, 1738 -- STTP 2024 and 2025); new
-- bookings on them would now match their original's site letters.
--
-- No table or column changes: scripts/gdpr-anonymise/ is unaffected.

-- 1. The venue an event's letters are resolved on ---------------------------------------------

CREATE OR REPLACE FUNCTION public.event_letter_venue_id(p_event_id integer)
    RETURNS integer
    LANGUAGE sql
    STABLE
AS $$
    SELECT COALESCE(e.venue_id, r.venue_id)
      FROM event e
           LEFT JOIN event r ON r.id = e.repeated_event_id
     WHERE e.id = p_event_id
$$;

COMMENT ON FUNCTION public.event_letter_venue_id(integer) IS
    'The venue letters are matched against for an event: its own venue, else the venue of the event it repeats (one level). V0136.';

-- 2. Cart/order booking trigger: V0125's body, venue from event_letter_venue_id() ------------

CREATE OR REPLACE FUNCTION public.trigger_document_generate_mails_on_booking()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    lt letter%ROWTYPE;
    ml mail%ROWTYPE;
BEGIN
    RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    FOR lt IN EXECUTE
        'select distinct on (t.id) l.* from letter l join letter_type t on l.type_id=t.id, document d join event e on e.id=d.event_id'
        || ' cross join lateral (select public.event_letter_venue_id(e.id) as venue_id) ev'
        || ' where d.id=$1 and (t.cart or t."order") and l.active'
        || ' and case when d.in_person then l.applicable_to_in_person else l.applicable_to_online end'
        || ' and letter_scope_rank(l.event_id,l.site_id,l.event_type_id,l.organization_id,d.event_id,ev.venue_id,e.type_id,d.organization_id) is not null'
        || ' order by t.id, letter_scope_rank(l.event_id,l.site_id,l.event_type_id,l.organization_id,d.event_id,ev.venue_id,e.type_id,d.organization_id), l.id'
        USING NEW.id
        LOOP
            IF NOT lt.suppresses_sending THEN
                SELECT INTO ml * FROM mail m JOIN document d ON m.document_id=d.id join letter_type t on lt.type_id=t.id WHERE letter_id=lt.id AND (t.order and d.id=NEW.id OR t.cart and d.cart_id=NEW.cart_id AND EXISTS(SELECT * FROM recipient r WHERE r.mail_id=m.id AND (r.email=NEW.person_email OR r.person_id=NEW.person_id)));
                IF NOT FOUND THEN
                    update document set trigger_send_letter_id=lt.id where id=NEW.id;
                END IF;
            END IF;
        END LOOP;
    RETURN NEW;
END $function$;

-- V0125: restore letter scope resolution in the cart/order booking trigger.
--
-- V0059 rebuilt trigger_document_generate_mails_on_booking() from a schema
-- dump that predated V0040/V0043/V0052, and so silently reverted it to the
-- pre-scope body:
--     l.event_id = d.event_id OR l.event_id IS NULL AND l.organization_id = d.organization_id
-- From then on every cart/order letter with no event matched EVERY event of its
-- organization, whatever its site / event type (e.g. a site + eventType STTP
-- cart letter generated for Fall Festival bookings). The same regression also
-- dropped: the l.active filter, the narrowest-scope-wins override (one letter
-- per type — the old body sends them all), suppression letters (a "send
-- nothing" letter was sent itself), and global event-type letters (V0052).
--
-- Body below = V0052's scope-aware body + V0059's only intended change to this
-- function: the person_id leg in the cart-letter dedup (members without an
-- email). The WHEN clause V0059 put on the trigger is kept as is.

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
        || ' where d.id=$1 and (t.cart or t."order") and l.active'
        || ' and case when d.in_person then l.applicable_to_in_person else l.applicable_to_online end'
        || ' and letter_scope_rank(l.event_id,l.site_id,l.event_type_id,l.organization_id,d.event_id,e.venue_id,e.type_id,d.organization_id) is not null'
        || ' order by t.id, letter_scope_rank(l.event_id,l.site_id,l.event_type_id,l.organization_id,d.event_id,e.venue_id,e.type_id,d.organization_id), l.id'
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

-- V0052: GLOBAL event-type letter scope — letters attached to an event type
-- only, with NO organization (follows V0051's global event types).
--
-- A letter with organization_id NULL and event_type_id set applies to every
-- organization's events of that type (e.g. one NKT-wide "National Festival"
-- letter set serving whichever organization hosts the festival).
--
-- Resolution ladder (narrowest wins), decided 2026-07-23: the global type
-- scope slots BETWEEN eventType@org and site@org — a type-specific global
-- letter beats an organization's generic letters, and an organization
-- overrides it by creating its own type-scoped letter (rank 2) or a
-- suppressor. Existing letters all carry an organization, so their relative
-- order is unchanged: behavior is provably identical until the first global
-- letter is created.
--
--   0  event
--   1  site + eventType  @org
--   2  eventType         @org
--   3  eventType         GLOBAL   <- new
--   4  site              @org     (was 3)
--   5  organization               (was 4)
--
-- The new 8-parameter letter_scope_rank() folds the organization check in
-- (l_organization_id NULL = global), so consumers no longer pre-filter on
-- l.organization_id = d.organization_id. The legacy 6-parameter overload is
-- KEPT untouched: already-deployed consumers (KBS2 actor builds, older
-- servers) keep working — they simply never rank global letters, because
-- their organization pre-filter excludes organization-less letters.
--
-- Deliberately NOT converted: trigger_frontend_account_generate_password_email
-- keeps its own org-disjunction (caller's org OR NKT org 1) — password letters
-- are not event-type-specific and already have a global mechanism via org 1;
-- its org filter structurally excludes organization-less letters.

-- 1. letter.organization_id becomes nullable, in the global-eventType shape only
ALTER TABLE letter ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE letter ADD CONSTRAINT letter_global_scope_chk
    CHECK (organization_id IS NOT NULL
           OR (event_type_id IS NOT NULL AND site_id IS NULL AND event_id IS NULL));

COMMENT ON COLUMN letter.organization_id IS
    'Owning organization; NULL = global event-type letter (event_type_id set, all organizations — V0052).';

-- 2. Organization-aware rank (8 params). The 6-param overload remains for
--    already-deployed consumers during the transition.
CREATE OR REPLACE FUNCTION letter_scope_rank(
    l_event_id int, l_site_id int, l_event_type_id int, l_organization_id int,
    d_event_id int, d_venue_id int, d_event_type_id int, d_organization_id int
) RETURNS int
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        -- global letter: letter_global_scope_chk guarantees the eventType-only shape
        WHEN l_organization_id IS NULL
            THEN CASE WHEN l_event_type_id = d_event_type_id THEN 3 END
        WHEN l_organization_id <> d_organization_id OR d_organization_id IS NULL
            THEN NULL
        WHEN l_event_id IS NOT NULL
            THEN CASE WHEN l_event_id = d_event_id THEN 0 END
        WHEN l_site_id IS NOT NULL AND l_event_type_id IS NOT NULL
            THEN CASE WHEN l_site_id = d_venue_id AND l_event_type_id = d_event_type_id THEN 1 END
        WHEN l_event_type_id IS NOT NULL
            THEN CASE WHEN l_event_type_id = d_event_type_id THEN 2 END
        WHEN l_site_id IS NOT NULL
            THEN CASE WHEN l_site_id = d_venue_id THEN 4 END
        ELSE 5
    END
$$;

COMMENT ON FUNCTION letter_scope_rank(int, int, int, int, int, int, int, int) IS
    'Letter scope resolution rank incl. the organization check and the global event-type scope (V0052): 0 event, 1 site+eventType@org, 2 eventType@org, 3 eventType GLOBAL (org NULL), 4 site@org, 5 organization. NULL = letter not applicable to the document context.';

-- 3. Cart/order booking trigger: switch to the 8-param rank (drops the
--    organization pre-filter so global letters compete).
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
                SELECT INTO ml * FROM mail m JOIN document d ON m.document_id=d.id join letter_type t on lt.type_id=t.id WHERE letter_id=lt.id AND (t.order and d.id=NEW.id OR t.cart and d.cart_id=NEW.cart_id AND EXISTS(SELECT * FROM recipient r WHERE r.mail_id=m.id AND r.email=NEW.person_email));
                IF NOT FOUND THEN
                    update document set trigger_send_letter_id=lt.id where id=NEW.id;
                END IF;
            END IF;
        END LOOP;
    RETURN NEW;
END $function$;

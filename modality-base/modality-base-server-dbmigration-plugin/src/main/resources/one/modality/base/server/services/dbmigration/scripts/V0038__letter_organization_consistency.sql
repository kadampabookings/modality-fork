-- V0038: letter.organization_id must match the event's organization.
--
-- 3,441 event-scoped letters (58% of all cart/order letters, including letters on live
-- events through Jan 2027 — dominated by org 1 → org 2) carry an organization_id
-- different from their event's. Root cause: copy_letter() clones organization_id
-- VERBATIM from the source letter, so copying letters onto another organization's
-- event kept the SOURCE org. This is a data inconsistency: a letter bound to an event
-- belongs to that event's organization.
--
-- Fixing it now also unblocks the letter scope resolution arc (docs/letter-scope-plan.md):
-- with the invariant restored, the scope consumers (V0040) can gate every letter on
-- l.organization_id = d.organization_id — the ownership boundary — without an
-- event-scoped exemption for the corrupted rows.
--
-- Verified inert for sending behavior: all three V0017 "from"-account resolution chains
-- read the DOCUMENT's organization (document.organization_id), never the letter's, and
-- letter.account_id (which 19 of the mismatched letters carry) is untouched.
--
-- Three parts: fix the producer, guard the invariant, repair the data.

-- ── 1. copy_letter(): the copy belongs to the DESTINATION event's organization ──
--
-- Same body as before except:
--   a. organization_id is resolved from dst_event_id instead of copied from the source
--      letter (the fix);
--   b. applicable_to_in_person / applicable_to_online are now carried over — the old
--      column list predated them, so copying an in-person-only letter silently produced
--      a both-modes copy (defaults true). automation_enabled and on_hold stay
--      deliberately NOT copied: an automation letter must be re-armed consciously on
--      the new event, not fire because of a copy.

CREATE OR REPLACE FUNCTION public.copy_letter(src_event_id integer, src_letter_id integer, dst_event_id integer)
 RETURNS integer
 LANGUAGE plpgsql
AS $function$
DECLARE
	new_info record;
	new_id int := -1;
BEGIN
	for new_info in

with src as (
	select * from letter where (src_letter_id is not null and id = src_letter_id) or (src_letter_id is null and event_id = src_event_id)
),
row_src as (select row_number() over (order by id),* from src order by id),
new as (
	insert into letter (event_id, type_id,organization_id,account_id,active,name,subject,subject_en,subject_de,subject_fr,subject_es,subject_pt,content,en,de,fr,es,pt,document_condition,applicable_to_in_person,applicable_to_online)
			 	select dst_event_id, type_id,(select e.organization_id from event e where e.id=dst_event_id),account_id,active,name,subject,subject_en,subject_de,subject_fr,subject_es,subject_pt,content,en,de,fr,es,pt,document_condition,applicable_to_in_person,applicable_to_online from row_src
   returning *
),
row_new as (select row_number() over (order by id),* from new)
select rn.id as new_id, rs.id as src_id from row_new rn join row_src rs on rs.row_number=rn.row_number

	loop
		if (new_id = -1) then
			new_id := new_info.new_id;
		end if;
	end loop;

	return new_id;
END;
$function$;

-- ── 2. Guard: coerce organization_id to the event's org on every write ──────────
--
-- Makes the invariant structural instead of trusting every writer (KBS2 BO, KBS3 BO,
-- DbExplorer, future code). Coerces rather than rejects so no existing flow breaks —
-- a write that supplies the wrong org is silently corrected. event.organization_id is
-- NOT NULL, so the coercion can never null the letter's org. Fires only when event_id
-- or organization_id is touched.

CREATE OR REPLACE FUNCTION public.trigger_letter_organization_consistency()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.event_id IS NOT NULL THEN
        SELECT e.organization_id INTO NEW.organization_id FROM event e WHERE e.id = NEW.event_id;
    END IF;
    RETURN NEW;
END $function$;

CREATE TRIGGER letter_organization_consistency
    BEFORE INSERT OR UPDATE OF event_id, organization_id ON letter
    FOR EACH ROW EXECUTE FUNCTION trigger_letter_organization_consistency();

-- ── 3. One-time repair of the existing rows ─────────────────────────────────────

UPDATE letter l
   SET organization_id = e.organization_id
  FROM event e
 WHERE e.id = l.event_id
   AND l.organization_id <> e.organization_id;

-- V0040: letter scope resolution in the PG booking-time consumers (phase 2 of
-- docs/letter-scope-plan.md; V0037 added the columns, allowlist and rank function;
-- V0038 restored the letter.organization_id = event.organization_id invariant that
-- the org gates below rely on).
--
-- Rewrites the two trigger functions that look letters up by event so they resolve
-- through letter_scope_rank() instead of hand-rolled event/org fallbacks:
--
--   #1 trigger_document_generate_mails_on_booking  (cart/order letters on booking insert)
--   #4 trigger_frontend_account_generate_password_email  (send-password letters)
--
-- The other letter-reading functions need no change: trigger_document_send_letter /
-- _send_system_letter / _mail_auto_recipient resolve BY ID, trigger_mail_auto_account
-- only reads letter.account_id, bookings_auto_confirm uses the direct
-- event.bookings_auto_confirm_letter_id FK, and copy_event/copy_letter deliberately
-- keeps copying only event-scoped letters (wide-scoped ones no longer need copying —
-- that is the point of the arc).
--
-- Provably near-identical on current data (verified against prod recon
-- scripts/letter-scope-phase0-recon-prod.txt: NO org-scoped cart/order letters exist,
-- so the rank collapses to l.event_id = d.event_id). Three deliberate, verified-inert
-- behavior changes in #1:
--   a. l.active is now required. Prod has exactly TWO inactive cart letters, on events
--      that ended in 2013 and 2017 — no live surface, and not sending an inactive
--      letter is the correct reading of the flag anyway.
--   b. One winner per type (DISTINCT ON): if an event carries several cart letters in
--      the SAME attendance-mode slot, today ALL are sent. Six events have that (double
--      both-modes cart letters), all past (latest ended 2026-04-15) — the double-send
--      was a data accident, not a feature; the winner is the lowest-id letter.
--
-- Every letter is gated on l.organization_id = the document's organization — the
-- ownership boundary. This is safe ONLY because V0038 repaired the 3,441 letters whose
-- organization_id disagreed with their event's and installed the coercion trigger that
-- keeps the invariant true; V0038 must therefore precede this script (index order).
-- Site/eventType scopes cannot cross organizations structurally (their org is the same
-- org as the events they can match).
--
-- #4 keeps its existing three-level semantics exactly (event match → own-org →
-- NKT org-1 global fallback), re-expressed as: letters of the caller's org OR org 1,
-- ranked by scope, NKT losing ties within a rank. With the V0038 invariant, org-1
-- letters can only compete in another org's context at rank 4 (the global fallback) —
-- exactly the old NKT fallback. Identical winners on repaired data across every event
-- context + the no-event context (verified read-only on staging: 0 diffs; every
-- event-scoped send-password letter is inactive, resolution lands org-level).

-- ── #1 cart/order letters on booking insert ─────────────────────────────
--
-- Fired AFTER INSERT ON document WHEN (event_id IS NOT NULL AND person_email IS NOT
-- NULL) — the event join below is therefore always satisfied. Cart and order are
-- override types: ONE winner per letter type × the document's attendance-mode slot,
-- narrowest scope wins, l.id breaks ties. The mode CASE both filters the competing
-- slot and (unchanged from before) routes in_person=NULL documents to the online slot.
-- The already-mailed dedup logic in the loop body is byte-identical to the previous
-- version (order letters dedup per document, cart letters per cart+recipient email).

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
        || ' where d.id=$1 and (t.cart or t."order") and l.active and l.organization_id=d.organization_id'
        || ' and case when d.in_person then l.applicable_to_in_person else l.applicable_to_online end'
        || ' and letter_scope_rank(l.event_id,l.site_id,l.event_type_id,d.event_id,e.venue_id,e.type_id) is not null'
        || ' order by t.id, letter_scope_rank(l.event_id,l.site_id,l.event_type_id,d.event_id,e.venue_id,e.type_id), l.id'
        USING NEW.id
        LOOP
            SELECT INTO ml * FROM mail m JOIN document d ON m.document_id=d.id join letter_type t on lt.type_id=t.id WHERE letter_id=lt.id AND (t.order and d.id=NEW.id OR t.cart and d.cart_id=NEW.cart_id AND EXISTS(SELECT * FROM recipient r WHERE r.mail_id=m.id AND r.email=NEW.person_email));
            IF NOT FOUND THEN
                update document set trigger_send_letter_id=lt.id where id=NEW.id;
            END IF;
        END LOOP;
    RETURN NEW;
END $function$;

-- ── #4 send-password letters ────────────────────────────────────────────
--
-- Only the letter SELECT changes: the three hand-rolled ORDER BY branches (event
-- match → own-org org-scoped → NKT global) become rank 0..4 within the caller's
-- organization plus the NKT (org 1) global fallback pinned at rank 5. No
-- attendance-mode filter — there is no document in this context. The LEFT JOIN keeps
-- the no-event path alive: with eventid NULL the rank resolves org-scoped letters to
-- 4 and correctly disqualifies site/type-scoped ones (no context to match against).
-- Everything else (mail account resolution, token generation, body templating) is
-- byte-identical to the previous version.

CREATE OR REPLACE FUNCTION public.trigger_frontend_account_generate_password_email()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
	lt letter%ROWTYPE;
	ml mail%ROWTYPE;
	ma mail_account%ROWTYPE;
	subject letter.subject_en%TYPE;
	body letter.en%TYPE;
	eventid event.id%TYPE := NEW.trigger_send_password_event_id;
	organizationid organization.id%TYPE := null;
	pwdrst_token frontend_account.pwdreset_token%TYPE;
BEGIN
  	RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
  	select into pwdrst_token md5(random()::text || clock_timestamp()::text)::uuid;
   	if (eventid is not null) then
		select into organizationid organization_id from event where id=eventid limit 1;
	end if;
	-- Letters of the caller's org or NKT (org 1), scope-ranked against the event
	-- context; within a rank the caller's own letter beats the NKT fallback. With no
	-- event context the rank resolves org-scoped letters to 4 and disqualifies
	-- event/site/type-scoped ones, reproducing the old "global letters only" path.
	SELECT INTO lt l.* FROM letter l JOIN letter_type t ON l.type_id=t.id
	LEFT JOIN event e ON e.id = eventid
	WHERE t.send_password AND l.active
	  AND (l.organization_id = 1 OR organizationid IS NOT NULL AND l.organization_id = organizationid)
	  AND letter_scope_rank(l.event_id,l.site_id,l.event_type_id,eventid,e.venue_id,e.type_id) IS NOT NULL
	ORDER BY letter_scope_rank(l.event_id,l.site_id,l.event_type_id,eventid,e.venue_id,e.type_id),
	         CASE WHEN l.organization_id = 1 AND organizationid IS DISTINCT FROM 1 THEN 1 ELSE 0 END,
	         l.id
	LIMIT 1;
	IF FOUND THEN
			SELECT INTO ma * FROM mail_account a where eventid is not null and a.organization_id=organizationid or eventid is null and exists(select * from event e where e.corporation_id=NEW.corporation_id and a.organization_id=e.organization_id) order by case when event_id=eventid then 0 else 1 end,case when a.event_id is null and a.organization_id=organizationid then 0 else 1 end,case when a.event_id is null and a.organization_id=1 then 0 else 1 end,id LIMIT 1;
			IF FOUND THEN
				EXECUTE 'SELECT ($1).' || NEW.lang INTO body USING lt;
				EXECUTE 'SELECT ($1).subject_' || NEW.lang INTO subject USING lt;
				IF (body is null) THEN
					body := lt.en;
					subject := lt.subject_en;
				END IF;
				body := replace(body, '[username]', NEW.username);
				body := replace(body, '[pwdResetToken]', pwdrst_token);
				INSERT INTO mail (account_id,letter_id,background,subject,content) values (ma.id, lt.id, true, subject, body);
				INSERT INTO recipient (mail_id, email) values (currval('mail_id_seq'), NEW.username);
			END IF;
	END IF;
	UPDATE frontend_account SET trigger_send_password=false, trigger_send_password_event_id=null, pwdreset_token=pwdrst_token, pwdreset_expires = now() + interval '1 day' WHERE id=NEW.id;
	RETURN NEW;
END $function$;

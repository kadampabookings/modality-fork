-- V0043: suppression letters — "send nothing" at a narrower scope (phase 6 of
-- docs/letter-scope-plan.md).
--
-- A wide-scoped letter (e.g. the Temple-wide cart letter) sometimes needs an
-- EXCLUSION: one event type or event at that site must not get it. The scope
-- model has no "except" vocabulary, and reusing active=false as a tombstone
-- would weaponise thousands of existing retired letters (all 1,466 event-scoped
-- password letters are inactive — tombstone semantics would kill password
-- resets in event contexts, and the 93 phase-5-deactivated GP letters would
-- suppress their own canonicals).
--
-- Instead, suppression is EXPLICIT: letter.suppresses_sending. Such a letter is
-- ACTIVE, competes in scope resolution exactly like any other letter, and when
-- it WINS, the consumer sends nothing. "No cart letter for GP classes" at
-- (site 1671, eventType 47) outranks the site-wide cart letter for GP events
-- only — the same narrowest-wins philosophy, expressing "none" as an explicit
-- value rather than an absence (the ItemPolicy unset-flags precedent, V0026).
--
-- Zero behavior change until the first suppressing letter is created (the
-- column defaults false and every behavior branch below requires it true).
--
-- Consumers in this migration: the booking cart/order trigger and the password
-- trigger. The KBS2 actor gets the same winner-check in its generated SQL (KBS2
-- build DEPENDS on this column — deploy KBS3 server first, as with V0037).
-- By-id senders (trigger_send_letter/_system_letter) are deliberately
-- untouched: pickers exclude suppressing letters, automation is CHECK-blocked,
-- and the winner-checks below stop trigger ids from ever pointing at one.
-- bookings_auto_confirm sends via an explicitly chosen direct FK — suppression
-- does not apply to deliberate by-id choices.

-- ── 1. The flag ─────────────────────────────────────────────────────────

ALTER TABLE letter
    ADD COLUMN IF NOT EXISTS suppresses_sending boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN letter.suppresses_sending IS
    'true = when this letter WINS scope resolution, nothing is sent — an explicit "no letter here" '
    'marker that lets a narrower scope switch off a wider-scoped letter. The letter must be active '
    'to compete; content is ignored.';

-- A suppressing letter must never drive the automation loop (it has nothing to send).
ALTER TABLE letter ADD CONSTRAINT letter_suppression_no_automation_chk
    CHECK (NOT (suppresses_sending AND automation_enabled));

-- ── 2. Cart/order booking trigger: suppressing winner ⇒ skip its type ───
-- Identical to V0040 except the loop body honours the winner's flag. The
-- suppressing letter must stay IN the ranked competition (filtering it out
-- would let the wider letter win again) — it wins, and then nothing happens.

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
            IF NOT lt.suppresses_sending THEN
                SELECT INTO ml * FROM mail m JOIN document d ON m.document_id=d.id join letter_type t on lt.type_id=t.id WHERE letter_id=lt.id AND (t.order and d.id=NEW.id OR t.cart and d.cart_id=NEW.cart_id AND EXISTS(SELECT * FROM recipient r WHERE r.mail_id=m.id AND r.email=NEW.person_email));
                IF NOT FOUND THEN
                    update document set trigger_send_letter_id=lt.id where id=NEW.id;
                END IF;
            END IF;
        END LOOP;
    RETURN NEW;
END $function$;

-- ── 3. Password trigger: suppressing winner ⇒ no mail (account row still
--       gets its reset token, exactly as when no letter matches at all) ──

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
	-- context; within a rank the caller's own letter beats the NKT fallback. A
	-- suppressing winner sends nothing (V0043).
	SELECT INTO lt l.* FROM letter l JOIN letter_type t ON l.type_id=t.id
	LEFT JOIN event e ON e.id = eventid
	WHERE t.send_password AND l.active
	  AND (l.organization_id = 1 OR organizationid IS NOT NULL AND l.organization_id = organizationid)
	  AND letter_scope_rank(l.event_id,l.site_id,l.event_type_id,eventid,e.venue_id,e.type_id) IS NOT NULL
	ORDER BY letter_scope_rank(l.event_id,l.site_id,l.event_type_id,eventid,e.venue_id,e.type_id),
	         CASE WHEN l.organization_id = 1 AND organizationid IS DISTINCT FROM 1 THEN 1 ELSE 0 END,
	         l.id
	LIMIT 1;
	IF FOUND AND NOT lt.suppresses_sending THEN
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

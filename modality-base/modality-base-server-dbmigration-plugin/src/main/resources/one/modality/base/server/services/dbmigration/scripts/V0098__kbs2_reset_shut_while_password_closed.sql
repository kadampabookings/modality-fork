-- V0098: KBS2's password reset stays shut while an account's password is closed ("Stop my password working").
--
-- V0096's restriction closes password sign-in AND password recovery, so that the only ways back in are a passkey
-- or a super administrator. KBS3's own recovery is refused in its gateways, but KBS2 has a reset of its own, and it
-- lives in the database: setting frontend_account.trigger_send_password fires generate_password_email, whose
-- function below makes a reset token, mails it to the username, and stores it for KBS2 to check. KBS2 reads no
-- restriction table, so without this a closed account's mailbox holder could reset the password through KBS2's
-- front office.
--
-- WHAT CHANGES: for an account with a restriction in force, the function makes no token and sends no mail, and it
-- clears any reset token already stored. It still clears the request flags, as it always has, so the request is
-- spent rather than left pending. Every other account goes exactly the old way: the body below is V0053's, with
-- one early branch.
--
-- AND A PASSWORD WRITE IS REFUSED while the restriction is in force, by a second function below: the reset shut
-- above stops KBS2 minting a link, but not a password written some other way. KBS2's front office lets a signed-in
-- account set its own password with no other proof, so a KBS2 session opened before the close — the laptop taken
-- with a KBS2 tab open — could set one; so could KBS2 staff, by hand. Either leaves a password KBS2 accepts at
-- sign-in (it reads no restriction). Closing's own wipe writes a "CLOSED-" value and passes; KBS2 stores only
-- md5 hex, so no KBS2 write or sign-in can produce or match one. KBS3 already refuses a password set while
-- closed; this is for everything else. To put a password back, the owner reopens with their passkey, or a super
-- administrator rescues the account, then recovery.
--
-- THE TRIGGER THAT CALLS IT IS NOT CREATED HERE: CREATE TRIGGER takes a lock on frontend_account that a boot
-- migration must not take (V0070's lock timeout left a deploy half-applied). It is attached by the hand-run
-- scripts/refuse-password-while-closed-trigger.sql, with a short-lock retry loop. Until it is, the function sits
-- unused; the reset shut above works on its own.
--
-- Idempotent: CREATE OR REPLACE of two functions, and an owner realign; no table and no trigger is touched.
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
	lang text;
	eventid event.id%TYPE := NEW.trigger_send_password_event_id;
	organizationid organization.id%TYPE := null;
	pwdrst_token frontend_account.pwdreset_token%TYPE;
BEGIN
  	RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
	-- V0098: an account whose owner stopped their password working gets no reset link, and keeps none.
	-- The flags are cleared as below, so the request is spent rather than left pending.
	IF EXISTS (SELECT 1 FROM account_sign_in_restriction r
	            WHERE r.frontend_account_id = NEW.id AND r.method = 'password' AND r.lifted_at IS NULL) THEN
		UPDATE frontend_account SET trigger_send_password=false, trigger_send_password_event_id=null,
		       pwdreset_token=null, pwdreset_expires=null WHERE id=NEW.id;
		RETURN NEW;
	END IF;
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
				-- V0053: an unsupported/NULL account lang falls back to English instead of raising
				lang := letter_lang_or_default(NEW.lang);
				EXECUTE 'SELECT ($1).' || lang INTO body USING lt;
				EXECUTE 'SELECT ($1).subject_' || lang INTO subject USING lt;
				IF (body is null) THEN
					body := lt.en;
					subject := lt.subject_en;
				END IF;
				-- V0053: mail.subject/content are NOT NULL — skip rather than raise
				IF body IS NULL OR subject IS NULL THEN
					RAISE WARNING 'Password letter % has no usable subject/content (lang %) — nothing sent for account %', lt.id, lang, NEW.id;
				ELSE
					body := replace(body, '[username]', NEW.username);
					body := replace(body, '[pwdResetToken]', pwdrst_token);
					INSERT INTO mail (account_id,letter_id,background,subject,content) values (ma.id, lt.id, true, subject, body);
					INSERT INTO recipient (mail_id, email) values (currval('mail_id_seq'), NEW.username);
				END IF;
			END IF;
	END IF;
	UPDATE frontend_account SET trigger_send_password=false, trigger_send_password_event_id=null, pwdreset_token=pwdrst_token, pwdreset_expires = now() + interval '1 day' WHERE id=NEW.id;
	RETURN NEW;
END $function$;

-- The refusal. BEFORE UPDATE OF password (see the script named above): raises, so the write and the transaction
-- around it fail, rather than quietly keeping the old value — a KBS2 screen that thinks it set a password must
-- say it did not. NULL passes (no password signs nobody in), and so does a "CLOSED-" value (closing's own wipe,
-- which runs in the same statement as the insert of the restriction).
CREATE OR REPLACE FUNCTION public.trigger_frontend_account_refuse_password_while_closed()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
	IF NEW.password IS NOT NULL AND NEW.password NOT LIKE 'CLOSED-%'
	   AND EXISTS (SELECT 1 FROM account_sign_in_restriction r
	                WHERE r.frontend_account_id = NEW.id AND r.method = 'password' AND r.lifted_at IS NULL) THEN
		RAISE EXCEPTION 'Password sign-in is closed on account % (V0096): no password can be set until it is reopened', NEW.id
		      USING ERRCODE = 'insufficient_privilege',
		            HINT = 'The owner reopens it with a passkey sign-in from their Security page, or a super administrator from the second-factor reset screen.';
	END IF;
	RETURN NEW;
END $function$;

-- Ownership of the function this migration created (the first one is replaced in place and keeps its owner).
-- Migrations run as the server's connect role, so a new function is owned by that role rather than the schema owner
-- — see V0064 — and a later CREATE OR REPLACE by another role would fail. Realign, warning rather than failing: a
-- tidy-up must never abort the boot chain.
DO $$
DECLARE
    schema_owner name;
    fn record;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO schema_owner FROM pg_class c WHERE c.oid = 'public.frontend_account'::regclass;
    FOR fn IN
        SELECT p.oid::regprocedure AS signature
          FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public' AND p.proname = 'trigger_frontend_account_refuse_password_while_closed'
           AND pg_get_userbyid(p.proowner) <> schema_owner
    LOOP
        BEGIN
            EXECUTE format('ALTER FUNCTION %s OWNER TO %I', fn.signature, schema_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0098: could not realign the owner of % (%)', fn.signature, SQLERRM;
        END;
    END LOOP;
END $$;

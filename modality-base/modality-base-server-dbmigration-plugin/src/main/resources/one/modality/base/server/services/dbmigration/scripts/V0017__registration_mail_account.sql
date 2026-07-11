-- V0017: registration mail account for the "from" sender resolution.
--
-- Adds event_type.registration_mail_account_id and organization.registration_mail_account_id,
-- and rewrites the letter/mail "from" account resolution so that, when a letter has no account
-- of its own, the sender is resolved as:
--     letter.account  ->  event.type.registrationMailAccount  ->  event.organization.registrationMailAccount
--
-- If no registration mail account is configured anywhere, each trigger falls back to its previous
-- event-bound search, so existing sending behaviour is preserved until the new fields are populated.
--
-- Affected triggers (per the agreed scope): trigger_document_send_letter, trigger_document_send_system_letter,
-- trigger_mail_auto_account. The password-reset and online-notification resolvers are intentionally left as-is.

-- 1. New FK columns (idempotent; the inline REFERENCES also creates the FK constraint).

ALTER TABLE event_type
    ADD COLUMN IF NOT EXISTS registration_mail_account_id integer REFERENCES mail_account(id);

ALTER TABLE organization
    ADD COLUMN IF NOT EXISTS registration_mail_account_id integer REFERENCES mail_account(id);


-- 2. Letter send (manual): resolve the "from" account via letter -> event type -> organization.

CREATE OR REPLACE FUNCTION public.trigger_document_send_letter() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE
	lt letter%ROWTYPE;
	ma mail_account%ROWTYPE;
	lang document.person_lang%TYPE;
	body letter.en%TYPE;
	subject letter.subject_en%TYPE;
BEGIN
   RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
	SELECT INTO lt * FROM letter l WHERE l.id=NEW.trigger_send_letter_id;
	IF FOUND THEN
		-- "from" account = letter's own account, else event type's registration account, else organization's.
		SELECT INTO ma * FROM mail_account WHERE id = COALESCE(
			lt.account_id,
			(SELECT et.registration_mail_account_id FROM event e JOIN event_type et ON et.id=e.type_id WHERE e.id=NEW.event_id),
			(SELECT o.registration_mail_account_id FROM organization o WHERE o.id=NEW.organization_id)
		);
		IF NOT FOUND THEN -- fall back to the previous event-bound search if no registration account is configured
			SELECT INTO ma * FROM mail_account where organization_id=NEW.organization_id order by case when id=lt.account_id then 0 else 1 end, case when event_id=NEW.event_id then 0 else id end LIMIT 1;
		END IF;
		IF FOUND THEN
			lang := NEW.person_lang;
			EXECUTE 'SELECT ($1).subject_' || lang INTO subject USING lt;
			IF (subject is null) THEN
				lang := 'en';
				subject := lt.subject_en;
			END IF;
			EXECUTE 'SELECT ($1).' || lang INTO body USING lt;
			body := interpret_brackets(body, NEW.id, lang);
			subject := interpret_brackets(subject, NEW.id, lang);
			INSERT INTO mail (account_id,letter_id, document_id, background, subject, content, read) values (ma.id, lt.id, NEW.id, true, subject, body, true);
		END IF;
	END IF;
	update document set trigger_send_letter_id=null where id=NEW.id;
	RETURN NEW;
END $_$;


-- 3. System letter send: same resolution.

CREATE OR REPLACE FUNCTION public.trigger_document_send_system_letter() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE
	lt letter%ROWTYPE;
	ma mail_account%ROWTYPE;
	lang document.person_lang%TYPE;
	body letter.en%TYPE;
	subject letter.subject_en%TYPE;
	mail_id mail.id%TYPE;
BEGIN
   RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
	SELECT INTO lt * FROM letter l WHERE l.id=NEW.trigger_send_system_letter_id;
	IF FOUND THEN
		-- "from" account = letter's own account, else event type's registration account, else organization's.
		SELECT INTO ma * FROM mail_account WHERE id = COALESCE(
			lt.account_id,
			(SELECT et.registration_mail_account_id FROM event e JOIN event_type et ON et.id=e.type_id WHERE e.id=NEW.event_id),
			(SELECT o.registration_mail_account_id FROM organization o WHERE o.id=NEW.organization_id)
		);
		IF NOT FOUND THEN -- fall back to the previous event-bound search if no registration account is configured
			SELECT INTO ma * FROM mail_account where organization_id=NEW.organization_id order by case when id=lt.account_id then 0 else 1 end, case when event_id=NEW.event_id then 0 else id end LIMIT 1;
		END IF;
		IF FOUND THEN
			lang := NEW.person_lang;
			EXECUTE 'SELECT ($1).subject_' || lang INTO subject USING lt;
			IF (subject is null) THEN
				lang := 'en';
				subject := lt.subject_en;
			END IF;
			EXECUTE 'SELECT ($1).' || lang INTO body USING lt;
			body := interpret_brackets(body, NEW.id, lang);
			subject := interpret_brackets(subject, NEW.id, lang);
			INSERT INTO mail (account_id,letter_id, document_id, background, subject, content, read) values (ma.id, lt.id, NEW.id, true, subject, body, true) returning id into mail_id;
         INSERT INTO history (document_id, mail_id, username, comment) values (NEW.id, mail_id, 'system', 'Sent ''' || subject || '''');
		END IF;
	END IF;
	update document set trigger_send_system_letter_id=null where id=NEW.id;
	RETURN NEW;
END $_$;


-- 4. Auto account: fills mail.account_id when a mail is inserted without one (NEW is the mail row).

CREATE OR REPLACE FUNCTION public.trigger_mail_auto_account() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
   RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
	-- "from" account = letter's own account, else event type's registration account, else organization's.
	NEW.account_id := COALESCE(
		(SELECT l.account_id FROM letter l WHERE l.id=NEW.letter_id),
		(SELECT et.registration_mail_account_id FROM document d JOIN event e ON e.id=d.event_id JOIN event_type et ON et.id=e.type_id WHERE d.id=NEW.document_id),
		(SELECT o.registration_mail_account_id FROM document d JOIN organization o ON o.id=d.organization_id WHERE d.id=NEW.document_id)
	);
	-- Fall back to the previous event-bound search if no registration account is configured.
	IF NEW.account_id IS NULL THEN
		SELECT INTO NEW.account_id ma.id FROM mail_account ma, document d where d.id=NEW.document_id and ma.organization_id=d.organization_id order by case when NEW.letter_id is not null and ma.id=(select account_id from letter l where l.id=NEW.letter_id) then 0 else 1 end, case when ma.event_id=d.event_id then 0 else ma.id end LIMIT 1;
	END IF;
	RETURN NEW;
END $$;

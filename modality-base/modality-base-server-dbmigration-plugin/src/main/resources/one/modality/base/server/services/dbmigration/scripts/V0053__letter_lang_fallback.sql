-- V0053: never let one booking's language abort a whole batch of letters.
--
-- Several functions pick a language column by string concatenation:
--
--     label():   EXECUTE 'select coalesce(' || lang || ',en) from label ...'
--     triggers:  EXECUTE 'SELECT ($1).subject_' || person_lang INTO subject ...
--
-- Anything that is not an existing column raises. Three real ways that happens:
--
--   * document.person_lang is character(2), i.e. BLANK-PADDED — a one-letter
--     value like 'p' arrives as 'p ' and yields `coalesce(p ,en)`, raising
--     'column "p" does not exist'. Such rows exist in the data today.
--   * NULL person_lang makes the whole concatenated string NULL, raising
--     'query string argument of EXECUTE is null'.
--   * the tables disagree on which languages they have: letter has
--     de/en/es/fr/pt, while label has de/el/en/es/fr/pt/vi/zhs/zht — and the
--     front office offers vi and zh, so 'zh' raises on label and 'vi' raises
--     on letter.
--
-- Why it matters beyond one email: AutomaticLettersActor advances the
-- no-deposit/cancellation ladder with a SINGLE UPDATE across every document,
-- with these triggers firing inside it. One raising row aborts the entire
-- statement, so NO booking advances — and KBS2 catches the exception and only
-- logs it, so the ladder stops silently for everyone. Same exposure for
-- mail.subject / mail.content, which are NOT NULL: a letter with no English
-- subject or body aborts the batch just as effectively.
--
-- This migration makes all of it degrade gracefully: an unusable language
-- falls back to English (resolved from the catalog, so adding a language
-- column anywhere needs no change here), and a letter with nothing sendable is
-- skipped with a WARNING instead of raising. Behaviour for well-formed data is
-- unchanged.

-- 1. Catalog-driven language resolution ---------------------------------------

CREATE OR REPLACE FUNCTION lang_text_column_exists(tbl regclass, col text) RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT $2 IS NOT NULL AND EXISTS (
        SELECT 1 FROM pg_attribute a
        WHERE a.attrelid = $1 AND a.attname = $2
          AND a.attnum > 0 AND NOT a.attisdropped
          -- must be a text column: 'id' IS a label column, but coalesce(id, en)
          -- would fail on type mismatch
          AND a.atttypid IN ('text'::regtype, 'varchar'::regtype, 'bpchar'::regtype))
$$;

COMMENT ON FUNCTION lang_text_column_exists(regclass, text) IS
    'True when the table has a text-typed column of that name — used to validate a language code before building dynamic SQL (V0053).';

CREATE OR REPLACE FUNCTION lang_or_default(tbl regclass, lang text) RETURNS text
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    -- btrim: person_lang is character(2) and therefore blank-padded
    SELECT CASE WHEN lang_text_column_exists($1, btrim($2)) THEN btrim($2) ELSE 'en' END
$$;

COMMENT ON FUNCTION lang_or_default(regclass, text) IS
    'The language code to use for that table''s translation columns: the given code when the table has it, otherwise ''en'' (V0053).';

CREATE OR REPLACE FUNCTION letter_lang_or_default(lang text) RETURNS text
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    -- a letter needs BOTH the body column and its subject_ counterpart
    SELECT CASE WHEN lang_text_column_exists('letter'::regclass, btrim($1))
                 AND lang_text_column_exists('letter'::regclass, 'subject_' || btrim($1))
                THEN btrim($1) ELSE 'en' END
$$;

COMMENT ON FUNCTION letter_lang_or_default(text) IS
    'The language to use for a letter: the given code when the letter table has both <code> and subject_<code>, otherwise ''en'' (V0053).';

-- 2. label() — the deepest one: reached from interpret_brackets for [event],
--    [options] and friends, with the raw person_lang.
CREATE OR REPLACE FUNCTION public.label(label_id integer, name text, lang character)
 RETURNS text
 LANGUAGE plpgsql
AS $function$
DECLARE
	label text;
	safe_lang text;
BEGIN
	-- V0053: fall back to English rather than raising when the label table has
	-- no such column (blank-padded, NULL, or a language label simply lacks).
	safe_lang := lang_or_default('label'::regclass, lang::text);
	EXECUTE 'select coalesce(' || quote_ident(safe_lang) || ',en) from label where id=$1' into label using label_id;
	RETURN case when label is null then name else label end;
END;
$function$;

-- 3. Manual / confirmation letters (document.trigger_send_letter_id).
CREATE OR REPLACE FUNCTION public.trigger_document_send_letter()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
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
			-- V0053: an unsupported/NULL person_lang falls back to English instead of raising
			lang := letter_lang_or_default(NEW.person_lang);
			EXECUTE 'SELECT ($1).subject_' || lang INTO subject USING lt;
			IF (subject is null) THEN
				lang := 'en';
				subject := lt.subject_en;
			END IF;
			EXECUTE 'SELECT ($1).' || lang INTO body USING lt;
			-- V0053: mail.subject/content are NOT NULL — skip a letter with nothing
			-- sendable rather than abort the caller's whole batch.
			IF subject IS NULL OR body IS NULL THEN
				RAISE WARNING 'Letter % has no usable subject/content (lang %) — nothing sent for document %', lt.id, lang, NEW.id;
			ELSE
				body := interpret_brackets(body, NEW.id, lang);
				subject := interpret_brackets(subject, NEW.id, lang);
				INSERT INTO mail (account_id,letter_id, document_id, background, subject, content, read) values (ma.id, lt.id, NEW.id, true, subject, body, true);
			END IF;
		END IF;
	END IF;
	update document set trigger_send_letter_id=null where id=NEW.id;
	RETURN NEW;
END $function$;

-- 4. System letters — the no-deposit / cancellation / audio / shuttle ladder
--    (document.trigger_send_system_letter_id). This is the one the actor drives
--    with a single batch UPDATE, so it is the one that most needs the guard.
CREATE OR REPLACE FUNCTION public.trigger_document_send_system_letter()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
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
			-- V0053: an unsupported/NULL person_lang falls back to English instead of raising
			lang := letter_lang_or_default(NEW.person_lang);
			EXECUTE 'SELECT ($1).subject_' || lang INTO subject USING lt;
			IF (subject is null) THEN
				lang := 'en';
				subject := lt.subject_en;
			END IF;
			EXECUTE 'SELECT ($1).' || lang INTO body USING lt;
			-- V0053: mail.subject/content are NOT NULL — skip a letter with nothing
			-- sendable rather than abort the actor's batch UPDATE for every booking.
			IF subject IS NULL OR body IS NULL THEN
				RAISE WARNING 'Letter % has no usable subject/content (lang %) — nothing sent for document %', lt.id, lang, NEW.id;
			ELSE
				body := interpret_brackets(body, NEW.id, lang);
				subject := interpret_brackets(subject, NEW.id, lang);
				INSERT INTO mail (account_id,letter_id, document_id, background, subject, content, read) values (ma.id, lt.id, NEW.id, true, subject, body, true) returning id into mail_id;
				INSERT INTO history (document_id, mail_id, username, comment) values (NEW.id, mail_id, 'system', 'Sent ''' || subject || '''');
			END IF;
		END IF;
	END IF;
	update document set trigger_send_system_letter_id=null where id=NEW.id;
	RETURN NEW;
END $function$;

-- 5. Password-reset emails (frontend_account.trigger_send_password), which read
--    the language from frontend_account.lang with the same dynamic pattern.
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

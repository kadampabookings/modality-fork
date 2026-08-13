-- V0070: re-compose an already-generated letter mail in place ("regenerate"), instead
-- of deleting it and waiting for the automation to create it again.
--
-- WHY
-- ---
-- A new or reworked letter is put ON HOLD (letter.on_hold): the automation keeps
-- generating its mails so they can be inspected in the database with their merge fields
-- expanded, but neither mailer drains them (both KBS2's MailerActor and the KBS3
-- MailerJob test `!letter..onHold`). That review is exactly when problems surface — a
-- wrong bracket token, a broken link, a bad translation.
--
-- Fixing the letter today means DELETING the generated mails so the automation composes
-- them again, which is destructive and lossy:
--   * history.mail_id is ON DELETE SET NULL, so each deleted mail leaves a dangling
--     "Sent '<old subject>'" history row on the booking, and the re-send adds a second
--     one — the booking's history grows a duplicate per review round;
--   * re-generation depends on the automation's dedup ("no mail exists for this
--     document/letter/recording") firing again, so it only works for letters the ladder
--     will revisit;
--   * anything a human already did to the row (the mail was read, an extra recipient
--     added) is gone.
--
-- This migration adds the missing operation: recompose a PENDING mail's text from its
-- letter, in place. Same composition code as the send path (see "one composition" below),
-- so what you re-read after regenerating is exactly what the trigger would have written.
--
-- WHAT IT TOUCHES ON THE MAIL, AND WHAT IT DOES NOT
-- -------------------------------------------------
--   subject/content  recomposed from the letter (or push title/body for a channel='push'
--                    mail — the channel is never switched)
--   account_id       re-resolved from the letter (letter's own account, else the event
--                    type's registration account, else the organization's), because a
--                    wrong "from" is one of the things an on-hold review catches. Kept as
--                    is when nothing resolves.
--   error            cleared — a stale failure note does not belong on text that is new
--   date             refreshed by default (p_refresh_date), matching what delete + let-it-
--                    regenerate does today. It matters: BOTH mailers only drain
--                    `date > current_date - 7`, so a mail held for longer than a week
--                    would never be sent after the hold is lifted. Pass false to keep the
--                    original date — note that a bumped date also makes this mail the
--                    "last mail" of its booking for AutomaticLettersActor's ladder, again
--                    exactly as a delete + re-generate does.
--   history          the machine-written "Sent '<subject>'" entry that the system-letter
--                    trigger left for this mail is retargeted to the new subject (only
--                    when it still matches that exact string). Nothing was sent yet, so
--                    this keeps the pending record consistent rather than rewriting an
--                    account of something that happened.
--   recipients       untouched. Addressing is a booking/person concern, not a letter one;
--                    a wrong address still calls for delete + re-generate (auto_recipient
--                    only fires on INSERT).
--
-- Guard: NOT transmitted, and that is the only one. A sent mail is a record of what went
-- out and is never rewritten; everything still queued is fair game. The two remaining
-- conditions are not policy but arithmetic — recomposition needs a letter to compose from
-- and a booking to expand the brackets against, so a mail without letter_id or
-- document_id is skipped with a reason rather than guessed at.
--
-- Consequence worth knowing: mails a human composed against a letter are regenerated too.
-- The BO Communications tab writes the letter NAME as subject and whatever the user typed
-- as content (background=false); regenerating replaces both with the letter's own text.
-- preview_regenerate_mail() shows that before it happens.
--
-- ONE COMPOSITION, TWO CALLERS
-- ----------------------------
-- The point of the feature is that a regenerated mail is byte-identical to a freshly
-- generated one. That only stays true if both paths share the code, so the composition
-- (language column pick, its fallbacks, interpret_brackets) and the "from" account
-- resolution move into functions, and trigger_document_send_letter() /
-- trigger_document_send_system_letter() are re-defined on top of them. Their bodies are
-- otherwise the V0053/V0055 ones unchanged — same guards, same warnings, same audio and
-- push branches, same inserts. The historic quirks are preserved deliberately (a missing
-- subject_<lang> takes the BODY to English too; a missing push_title_<lang> falls back
-- per-field WITHOUT changing the language used for bracket expansion).
--
-- HOW TO USE IT
-- -------------
--     select preview_regenerate_mail(1322155);          -- see old vs new, writes nothing
--     select regenerate_mail(1322155);                  -- one mail
--     select regenerate_letter_mails(26304);            -- every pending mail of a letter
--     update letter set trigger_regenerate_mails = true where id = 26304;   -- same, from any UI
--
-- The last form is the flag-column pattern already used by document.trigger_send_letter_id
-- and frontend_account.trigger_send_password: it makes the operation reachable from the
-- existing letter editors (KBS2 and the KBS3 back-office letter drawer) with no endpoint,
-- once the column is exposed in their domain models. The flag resets itself.

-- 1. The regenerate flag on letter ---------------------------------------------------

ALTER TABLE letter
    ADD COLUMN IF NOT EXISTS trigger_regenerate_mails boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN letter.trigger_regenerate_mails IS
    'Write-only flag: set it to true to re-compose every pending (untransmitted) mail of this letter from the letter''s current text; the trigger resets it to false (V0070).';

-- 2. Shared "from" account resolution ------------------------------------------------
-- Verbatim the ladder both send triggers have used since V0017/V0053, including the
-- second ordering key's `else id` (event-matching accounts first, then by id).

CREATE OR REPLACE FUNCTION letter_from_mail_account_id(lt letter, p_organization_id integer, p_event_id integer)
RETURNS integer
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(
        -- letter's own account, else the event type's registration account, else the organization's
        (SELECT ma.id FROM mail_account ma WHERE ma.id = COALESCE(
            lt.account_id,
            (SELECT et.registration_mail_account_id FROM event e JOIN event_type et ON et.id = e.type_id WHERE e.id = p_event_id),
            (SELECT o.registration_mail_account_id FROM organization o WHERE o.id = p_organization_id))),
        -- fall back to the previous event-bound search if no registration account is configured
        (SELECT ma.id FROM mail_account ma WHERE ma.organization_id = p_organization_id
          ORDER BY CASE WHEN ma.id = lt.account_id THEN 0 ELSE 1 END,
                   CASE WHEN ma.event_id = p_event_id THEN 0 ELSE ma.id END
          LIMIT 1))
$$;

COMMENT ON FUNCTION letter_from_mail_account_id(letter, integer, integer) IS
    'The mail_account a letter sends from for that organization/event: the letter''s own, else the event type''s registration account, else the organization''s, else the closest account of the organization (V0070).';

-- 3. Shared composition --------------------------------------------------------------
-- Both return NULLs (rather than raising) when the letter has nothing sendable in that
-- language: mail.subject/content are NOT NULL, and a raise here would abort the caller's
-- whole batch — the V0053 rule.

CREATE OR REPLACE FUNCTION letter_compose_email(lt letter, p_lang text, p_document_id integer, p_scheduled_item_id integer,
                                                OUT subject text, OUT content text)
LANGUAGE plpgsql AS $function$
DECLARE
    v_lang text := letter_lang_or_default(p_lang); -- NULL-safe, blank-pad-safe (V0053)
BEGIN
    EXECUTE 'SELECT ($1).subject_' || v_lang INTO subject USING lt;
    IF (subject IS NULL) THEN
        -- historic quirk, kept: no subject in that language sends the BODY to English too
        v_lang := 'en';
        subject := lt.subject_en;
    END IF;
    EXECUTE 'SELECT ($1).' || v_lang INTO content USING lt;
    IF subject IS NULL OR content IS NULL THEN
        subject := NULL;
        content := NULL;
        RETURN;
    END IF;
    -- The 4-arg interpret_brackets with a NULL scheduled item builds the very same query
    -- as the 3-arg one, so this is what both send paths did.
    content := interpret_brackets(content, p_document_id, v_lang::bpchar, p_scheduled_item_id);
    subject := interpret_brackets(subject, p_document_id, v_lang::bpchar, p_scheduled_item_id);
END $function$;

COMMENT ON FUNCTION letter_compose_email(letter, text, integer, integer) IS
    'The email subject/content a letter produces for that booking and language, brackets expanded — NULLs when the letter has nothing sendable. Shared by the send triggers and regenerate_mail() (V0070).';

CREATE OR REPLACE FUNCTION letter_compose_push(lt letter, p_lang text, p_document_id integer, p_scheduled_item_id integer,
                                               OUT title text, OUT body text)
LANGUAGE plpgsql AS $function$
DECLARE
    v_lang text := letter_lang_or_default(p_lang);
BEGIN
    -- the push columns are a separate family: a language the body columns have but the
    -- push ones do not must not build a non-existent column name (V0053 rule, applied here)
    IF NOT lang_text_column_exists('letter'::regclass, 'push_title_' || v_lang)
       OR NOT lang_text_column_exists('letter'::regclass, 'push_body_' || v_lang) THEN
        v_lang := 'en';
    END IF;
    EXECUTE 'SELECT ($1).push_title_' || v_lang INTO title USING lt;
    IF (title IS NULL) THEN
        title := lt.push_title_en;
    END IF;
    EXECUTE 'SELECT ($1).push_body_' || v_lang INTO body USING lt;
    IF (body IS NULL) THEN
        body := lt.push_body_en;
    END IF;
    IF title IS NULL OR body IS NULL THEN
        title := NULL;
        body := NULL;
        RETURN;
    END IF;
    -- V0055 quirk, kept: the per-field English fallback does NOT change the language the
    -- brackets are expanded in.
    title := interpret_brackets(title, p_document_id, v_lang::bpchar, p_scheduled_item_id);
    body := interpret_brackets(body, p_document_id, v_lang::bpchar, p_scheduled_item_id);
END $function$;

COMMENT ON FUNCTION letter_compose_push(letter, text, integer, integer) IS
    'The web-push title/body a letter produces for that booking and language, brackets expanded — NULLs when the letter has no usable push text. Shared by the system-letter trigger and regenerate_mail() (V0070).';

-- 4. The two send triggers, now composing through the functions above -----------------
--    (V0053 body for the manual one, V0055 body for the system one — unchanged apart
--    from delegating the account resolution and the composition.)

CREATE OR REPLACE FUNCTION public.trigger_document_send_letter()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
	lt letter%ROWTYPE;
	v_account_id mail_account.id%TYPE;
	v_lang text;
	body letter.en%TYPE;
	subject letter.subject_en%TYPE;
BEGIN
   RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
	SELECT INTO lt * FROM letter l WHERE l.id=NEW.trigger_send_letter_id;
	IF FOUND THEN
		v_account_id := letter_from_mail_account_id(lt, NEW.organization_id, NEW.event_id);
		IF v_account_id IS NOT NULL THEN
			-- V0053: an unsupported/NULL person_lang falls back to English instead of raising
			v_lang := letter_lang_or_default(NEW.person_lang);
			SELECT * INTO subject, body FROM letter_compose_email(lt, v_lang, NEW.id, NULL);
			-- V0053: mail.subject/content are NOT NULL — skip a letter with nothing
			-- sendable rather than abort the caller's whole batch.
			IF subject IS NULL OR body IS NULL THEN
				RAISE WARNING 'Letter % has no usable subject/content (lang %) — nothing sent for document %', lt.id, v_lang, NEW.id;
			ELSE
				INSERT INTO mail (account_id,letter_id, document_id, background, subject, content, read) values (v_account_id, lt.id, NEW.id, true, subject, body, true);
			END IF;
		END IF;
	END IF;
	update document set trigger_send_letter_id=null where id=NEW.id;
	RETURN NEW;
END $function$;

CREATE OR REPLACE FUNCTION public.trigger_document_send_system_letter()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
	lt letter%ROWTYPE;
	tp letter_type%ROWTYPE;
	v_account_id mail_account.id%TYPE;
	v_lang text;
	body letter.en%TYPE;
	subject letter.subject_en%TYPE;
	mail_id mail.id%TYPE;
	si_id scheduled_item.id%TYPE;
	push_title letter.push_title_en%TYPE;
	push_body letter.push_body_en%TYPE;
	push_sent boolean := false;
BEGIN
   RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
	SELECT INTO lt * FROM letter l WHERE l.id=NEW.trigger_send_system_letter_id;
	IF FOUND THEN
		v_account_id := letter_from_mail_account_id(lt, NEW.organization_id, NEW.event_id);
		IF v_account_id IS NOT NULL THEN
			-- V0055 (restoring pre-V0017 behaviour): audio letter types resolve the most
			-- recent published recording of the booking — merge tokens expand against it
			-- (4-arg interpret_brackets) and mail.scheduled_item_id records it, which is
			-- the per-recording dedup key of AutomaticLettersActor.sendAudioLetters().
			SELECT INTO tp * FROM letter_type WHERE id = lt.type_id;
			IF (tp.audio) THEN
				select into si_id si.id from attendance a join document_line dl on dl.id=a.document_line_id, scheduled_item si join item i on i.id=si.item_id join item_family f on f.id=i.family_id where dl.document_id=NEW.id and si.bookable_scheduled_item_id = a.scheduled_item_id and si.published and f.code='record' order by si.date desc limit 1;
			END IF;
			-- V0053: an unsupported/NULL person_lang falls back to English instead of raising
			v_lang := letter_lang_or_default(NEW.person_lang);
			-- V0055: web-push branch — when the letter has a push variant AND the booker
			-- has a live subscription on its context AND no push was already created for
			-- this (document, letter, scheduled item), create a channel='push' mail
			-- instead of an email. The already-created guard is what makes the sender's
			-- email fallback work: on re-fire, this branch is skipped and the email path
			-- below runs.
			IF lt.push_context IS NOT NULL
			   AND EXISTS (SELECT 1 FROM live_push_endpoints(lt.push_context, NEW.person_email, NEW.person_id))
			   AND NOT EXISTS (SELECT 1 FROM mail m WHERE m.channel = 'push' AND m.document_id = NEW.id
			                     AND m.letter_id = lt.id AND m.scheduled_item_id IS NOT DISTINCT FROM si_id) THEN
				SELECT * INTO push_title, push_body FROM letter_compose_push(lt, v_lang, NEW.id, si_id);
				IF push_title IS NULL OR push_body IS NULL THEN
					RAISE WARNING 'Letter % has push_context but no usable push title/body (lang %) — falling back to email for document %', lt.id, v_lang, NEW.id;
				ELSE
					INSERT INTO mail (account_id, letter_id, document_id, scheduled_item_id, background, subject, content, read, channel)
						values (v_account_id, lt.id, NEW.id, si_id, true, push_title, push_body, true, 'push') returning id into mail_id;
					INSERT INTO history (document_id, mail_id, username, comment) values (NEW.id, mail_id, 'system', 'Sent push ''' || push_title || '''');
					push_sent := true;
				END IF;
			END IF;
			IF NOT push_sent THEN
				SELECT * INTO subject, body FROM letter_compose_email(lt, v_lang, NEW.id, si_id);
				-- V0053: mail.subject/content are NOT NULL — skip a letter with nothing
				-- sendable rather than abort the actor's batch UPDATE for every booking.
				IF subject IS NULL OR body IS NULL THEN
					RAISE WARNING 'Letter % has no usable subject/content (lang %) — nothing sent for document %', lt.id, v_lang, NEW.id;
				ELSE
					INSERT INTO mail (account_id, letter_id, document_id, scheduled_item_id, background, subject, content, read)
						values (v_account_id, lt.id, NEW.id, si_id, true, subject, body, true) returning id into mail_id;
					INSERT INTO history (document_id, mail_id, username, comment) values (NEW.id, mail_id, 'system', 'Sent ''' || subject || '''');
				END IF;
			END IF;
		END IF;
	END IF;
	update document set trigger_send_system_letter_id=null where id=NEW.id;
	RETURN NEW;
END $function$;

-- 5. Recomposition of an existing mail (read-only core) ------------------------------
-- All the guards and the composition in one place, writing nothing: regenerate_mail()
-- applies its result, preview_regenerate_mail() just shows it.

CREATE OR REPLACE FUNCTION letter_mail_recomposition(p_mail_id integer,
    OUT new_subject text, OUT new_content text, OUT new_account_id integer, OUT skip_reason text)
LANGUAGE plpgsql AS $function$
DECLARE
    m mail%ROWTYPE;
    d document%ROWTYPE;
    lt letter%ROWTYPE;
    v_lang text;
BEGIN
    SELECT INTO m * FROM mail WHERE id = p_mail_id;
    IF NOT FOUND THEN
        skip_reason := format('no mail %s', p_mail_id);
        RETURN;
    END IF;
    IF m.transmitted THEN
        skip_reason := 'already transmitted';
        RETURN;
    END IF;
    -- Not a policy guard: there is simply nothing to recompose from without a letter, and
    -- nothing to expand the brackets against without a booking (contact-us mails, magic
    -- links and the password letter's account mails all land here).
    IF m.letter_id IS NULL OR m.document_id IS NULL THEN
        skip_reason := 'no letter and booking to recompose from';
        RETURN;
    END IF;
    SELECT INTO lt * FROM letter WHERE id = m.letter_id;
    IF NOT FOUND THEN
        skip_reason := format('letter %s no longer exists', m.letter_id);
        RETURN;
    END IF;
    SELECT INTO d * FROM document WHERE id = m.document_id;
    IF NOT FOUND THEN
        skip_reason := format('document %s no longer exists', m.document_id);
        RETURN;
    END IF;
    -- The language is re-read from the booking, exactly as at generation time: a booker
    -- who changed language since gets the letter in the new one, as a delete + re-generate
    -- would have given them.
    v_lang := letter_lang_or_default(d.person_lang);
    IF m.channel = 'push' THEN
        -- the channel is never switched: a push mail is recomposed from the push columns,
        -- an email mail from the body columns
        SELECT * INTO new_subject, new_content FROM letter_compose_push(lt, v_lang, d.id, m.scheduled_item_id);
    ELSE
        SELECT * INTO new_subject, new_content FROM letter_compose_email(lt, v_lang, d.id, m.scheduled_item_id);
    END IF;
    IF new_subject IS NULL OR new_content IS NULL THEN
        new_subject := NULL;
        new_content := NULL;
        skip_reason := format('letter %s has no usable %s in language %s', lt.id,
                              CASE WHEN m.channel = 'push' THEN 'push title/body' ELSE 'subject/content' END, v_lang);
        RETURN;
    END IF;
    new_account_id := COALESCE(letter_from_mail_account_id(lt, d.organization_id, d.event_id), m.account_id);
END $function$;

COMMENT ON FUNCTION letter_mail_recomposition(integer) IS
    'What an existing pending mail would say if composed from its letter now (subject/content/from-account), or the reason it cannot be — writes nothing (V0070).';

CREATE OR REPLACE FUNCTION preview_regenerate_mail(p_mail_id integer)
RETURNS TABLE (mail_channel text, old_subject text, new_subject text, old_content text, new_content text, skip_reason text)
LANGUAGE plpgsql AS $function$
BEGIN
    -- left join, so a mail id that does not exist still comes back with its skip_reason
    RETURN QUERY
        SELECT m.channel::text, m.subject::text, r.new_subject, m.content, r.new_content, r.skip_reason
        FROM letter_mail_recomposition(p_mail_id) r
        LEFT JOIN mail m ON m.id = p_mail_id;
END $function$;

COMMENT ON FUNCTION preview_regenerate_mail(integer) IS
    'Old vs new text of a pending mail, to check a letter fix before applying it — writes nothing (V0070).';

-- 6. Regeneration --------------------------------------------------------------------

CREATE OR REPLACE FUNCTION regenerate_mail(p_mail_id integer, p_refresh_date boolean DEFAULT true)
RETURNS boolean
LANGUAGE plpgsql AS $function$
DECLARE
    v_old_subject text;
    v_channel text;
    v_subject text;
    v_content text;
    v_account_id integer;
    v_skip text;
    v_prefix text;
    v_updated integer;
BEGIN
    -- lock the row first: the mailers may be draining this letter (it is only on-hold
    -- letters in practice, but nothing here depends on that)
    SELECT m.subject, m.channel INTO v_old_subject, v_channel FROM mail m WHERE m.id = p_mail_id FOR UPDATE;
    SELECT * INTO v_subject, v_content, v_account_id, v_skip FROM letter_mail_recomposition(p_mail_id);
    IF v_skip IS NOT NULL THEN
        RAISE WARNING 'regenerate_mail: mail % left untouched — %', p_mail_id, v_skip;
        RETURN false;
    END IF;
    UPDATE mail SET subject = v_subject,
                    content = v_content,
                    account_id = v_account_id,
                    error = NULL, -- a stale failure note does not belong on new text
                    -- localtimestamp = the column's own `now()` default, without the
                    -- timestamptz round trip
                    date = CASE WHEN p_refresh_date THEN localtimestamp ELSE mail.date END
     WHERE id = p_mail_id AND NOT transmitted;
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    IF v_updated = 0 THEN -- transmitted between the recomposition and here
        RAISE WARNING 'regenerate_mail: mail % was transmitted concurrently — left untouched', p_mail_id;
        RETURN false;
    END IF;
    -- Retarget the system-letter trigger's own history entry, and only that exact string:
    -- nothing was sent yet, so the pending record should name the text that is queued.
    IF v_subject IS DISTINCT FROM v_old_subject THEN
        v_prefix := CASE WHEN v_channel = 'push' THEN 'Sent push ''' ELSE 'Sent ''' END;
        UPDATE history SET comment = v_prefix || v_subject || ''''
         WHERE mail_id = p_mail_id AND comment = v_prefix || v_old_subject || '''';
    END IF;
    RETURN true;
END $function$;

COMMENT ON FUNCTION regenerate_mail(integer, boolean) IS
    'Re-compose a pending letter mail in place from its letter''s current text (and re-resolve its from-account); true when applied. Transmitted and hand-written mails are left untouched (V0070).';

CREATE OR REPLACE FUNCTION regenerate_letter_mails(p_letter_id integer, p_refresh_date boolean DEFAULT true)
RETURNS integer
LANGUAGE plpgsql AS $function$
DECLARE
    v_mail_id integer;
    v_count integer := 0;
BEGIN
    FOR v_mail_id IN
        SELECT m.id FROM mail m
         WHERE m.letter_id = p_letter_id
           AND NOT m.transmitted
           AND m.document_id IS NOT NULL
         ORDER BY m.id
    LOOP
        BEGIN
            IF regenerate_mail(v_mail_id, p_refresh_date) THEN
                v_count := v_count + 1;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            -- one unusable mail (an expanded subject over 255 chars, say) must not roll
            -- back the mails that recomposed fine — the V0053 rule again
            RAISE WARNING 'regenerate_letter_mails: mail % skipped: %', v_mail_id, SQLERRM;
        END;
    END LOOP;
    RETURN v_count;
END $function$;

COMMENT ON FUNCTION regenerate_letter_mails(integer, boolean) IS
    'Re-compose every pending mail of a letter in place; returns how many were rewritten (V0070).';

-- Backs the loop above: mail has no index but its primary key, and 99.999% of its million
-- rows are transmitted, so this partial index is a few pages. Its predicate has to stay
-- exactly the loop's, or the planner cannot use it.
CREATE INDEX IF NOT EXISTS mail_pending_letter_idx ON mail (letter_id) WHERE NOT transmitted;

-- 7. The flag trigger ----------------------------------------------------------------

CREATE OR REPLACE FUNCTION trigger_letter_regenerate_mails() RETURNS trigger
LANGUAGE plpgsql AS $function$
DECLARE
    v_count integer;
BEGIN
    RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    v_count := regenerate_letter_mails(NEW.id);
    RAISE NOTICE 'Letter %: % pending mail(s) regenerated', NEW.id, v_count;
    -- resets the flag; the WHEN clause keeps that update from re-entering here
    UPDATE letter SET trigger_regenerate_mails = false WHERE id = NEW.id;
    RETURN NEW;
END $function$;

DROP TRIGGER IF EXISTS regenerate_mails ON public.letter;
CREATE TRIGGER regenerate_mails AFTER UPDATE OF trigger_regenerate_mails ON public.letter
    FOR EACH ROW WHEN (new.trigger_regenerate_mails)
    EXECUTE FUNCTION public.trigger_letter_regenerate_mails();

-- 8. Ownership of the objects this migration created ---------------------------------
-- Migrations run as the server's connect role, so anything they CREATE is owned by that
-- role instead of the schema owner every other role reaches the schema through — see
-- V0064. Realign, warning rather than failing: a tidy-up must never abort the boot chain.

DO $$
DECLARE
    schema_owner name;
    fn record;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO schema_owner FROM pg_class c WHERE c.oid = 'public.letter'::regclass;
    FOR fn IN
        SELECT p.oid::regprocedure AS signature
          FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public'
           AND p.proname IN ('letter_from_mail_account_id', 'letter_compose_email', 'letter_compose_push',
                             'letter_mail_recomposition', 'preview_regenerate_mail', 'regenerate_mail',
                             'regenerate_letter_mails', 'trigger_letter_regenerate_mails')
           AND pg_get_userbyid(p.proowner) <> schema_owner
    LOOP
        BEGIN
            EXECUTE format('ALTER FUNCTION %s OWNER TO %I', fn.signature, schema_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0070: could not realign the owner of % (%)', fn.signature, SQLERRM;
        END;
    END LOOP;
END $$;

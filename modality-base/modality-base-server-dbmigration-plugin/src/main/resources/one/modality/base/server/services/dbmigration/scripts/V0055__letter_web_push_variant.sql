-- V0055: web-push variant for letters — a letter can now be delivered as a web push
-- notification instead of an email, per recipient, decided at mail-creation time.
--
-- DESIGN (docs/letter-web-push-plan.md in the aggregate repo)
-- -----------------------------------------------------------
-- * The push CONTENT lives on the letter as extra columns (push_context + per-language
--   push_title_<lang>/push_body_<lang> + push_url), NOT as a second letter row — so the
--   scope-ranked letter selection and the automation dedup keep operating on one letter.
-- * A push DELIVERY is a mail row with channel='push' (subject = composed push title,
--   content = composed push body). Reusing the mail table keeps every sequencing
--   invariant true by construction: the actor's audio dedup (mail.scheduled_item_id),
--   the documentCondition dedup, NO_SYSTEM_MAIL_CONDITION and the delay-from-last-
--   system-mail logic all test "exists a mail row", whatever the channel.
-- * The per-recipient branch happens in trigger_document_send_system_letter(), the
--   single funnel both automation families (audio letters + documentCondition letters)
--   pass through: live subscription on the letter's push_context -> push row, else the
--   unchanged email path. A push row for the same (document, letter, scheduled_item)
--   acts as a guard: when the KBS3 push sender finds no live device at send time it
--   re-fires the trigger, which then takes the email branch — automatic email fallback
--   without recomposition logic outside the database.
-- * CHECK (push_context IS NULL OR kbs3): a push variant forces the letter onto the
--   KBS3 side of the mailer partition (V0047), so KBS2's MailerActor — which drains
--   `not letter..kbs3` and knows nothing about channel — can never pick up a push row.
--   The KBS3 MailerJob adds `and channel='email'` to its drain; the new WebPushMailerJob
--   drains `channel='push'`.
--
-- AUDIO BRANCH RESTORED (regression fix)
-- --------------------------------------
-- The pre-V0017 trigger resolved the most recent published 'record' scheduled item for
-- letters of an audio letter type, passed it to the 4-arg interpret_brackets, and
-- stamped it on mail.scheduled_item_id — which is the per-recording dedup key of
-- AutomaticLettersActor.sendAudioLetters(). The V0017 rewrite dropped all of that
-- (unnoticed: no audio letter is configured in prod), leaving a trap: an audio letter
-- would re-send EVERY HOUR because the actor's not-exists dedup could never match.
-- This body restores the audio branch on top of the V0053 language-fallback shape.

-- 1. Letter: push variant columns ---------------------------------------------------

ALTER TABLE letter
    ADD COLUMN IF NOT EXISTS push_context varchar(32),
    ADD COLUMN IF NOT EXISTS push_url varchar(255),
    ADD COLUMN IF NOT EXISTS push_title_en varchar(64),
    ADD COLUMN IF NOT EXISTS push_title_de varchar(64),
    ADD COLUMN IF NOT EXISTS push_title_es varchar(64),
    ADD COLUMN IF NOT EXISTS push_title_fr varchar(64),
    ADD COLUMN IF NOT EXISTS push_title_pt varchar(64),
    ADD COLUMN IF NOT EXISTS push_body_en varchar(512),
    ADD COLUMN IF NOT EXISTS push_body_de varchar(512),
    ADD COLUMN IF NOT EXISTS push_body_es varchar(512),
    ADD COLUMN IF NOT EXISTS push_body_fr varchar(512),
    ADD COLUMN IF NOT EXISTS push_body_pt varchar(512);

COMMENT ON COLUMN letter.push_context IS
    'Non-null = this letter has a web-push variant: the push_subscription_recipient.context a booker must be live-subscribed to for delivery as a push instead of an email (V0055).';
COMMENT ON COLUMN letter.push_url IS
    'Click-through URL of the push notification (FO deep link, e.g. /audio-library). No bracket expansion (V0055).';

ALTER TABLE letter DROP CONSTRAINT IF EXISTS letter_push_kbs3_chk;
ALTER TABLE letter ADD CONSTRAINT letter_push_kbs3_chk
    CHECK (push_context IS NULL OR kbs3);

-- 2. Mail: delivery channel ---------------------------------------------------------

ALTER TABLE mail
    ADD COLUMN IF NOT EXISTS channel varchar(8) NOT NULL DEFAULT 'email';

COMMENT ON COLUMN mail.channel IS
    '''email'' (default) = transmitted by a mailer through SMTP; ''push'' = subject/content are a composed web-push title/body, transmitted by the WebPushMailerJob to the booker''s live push subscriptions (V0055).';

ALTER TABLE mail DROP CONSTRAINT IF EXISTS mail_channel_chk;
ALTER TABLE mail ADD CONSTRAINT mail_channel_chk
    CHECK (channel IN ('email', 'push'));

-- Tiny partial index backing the WebPushMailerJob drain query.
CREATE INDEX IF NOT EXISTS mail_push_untransmitted_idx
    ON mail (date) WHERE channel = 'push' AND NOT transmitted;

-- 3. live_push_endpoints(): the one definition of "who is reachable by push" ---------
--
-- Used at INSERT time by the trigger below (EXISTS) and at SEND time by the
-- WebPushMailerJob (full list), so both sides can never disagree on the audience.
-- Identity match: the document's denormalized booker email (case-insensitive) OR the
-- booker person — subscriptions are written by the FO with the logged-in account's
-- email + person, which may differ from the booking's (family-member bookings fall
-- back to email delivery to the document's person, which is the correct addressee).

CREATE OR REPLACE FUNCTION live_push_endpoints(push_context text, doc_email text, doc_person_id integer)
RETURNS TABLE (subscription_id integer, endpoint text, p256dh_key text, auth_key text, vapid_public_key text)
LANGUAGE sql STABLE AS $$
    SELECT DISTINCT ps.id, ps.endpoint, ps.p256dh_key::text, ps.auth_key::text, ps.vapid_public_key
    FROM push_subscription_recipient psr
    JOIN push_subscription ps ON ps.id = psr.subscription_id
    WHERE psr.context = $1 AND psr.unsubscribed_at IS NULL
      AND (lower(psr.email) = lower($2) OR psr.person_id = $3)
$$;

COMMENT ON FUNCTION live_push_endpoints(text, text, integer) IS
    'Distinct live device endpoints subscribed to that push context for that booker (by email, case-insensitive, or person). Shared by the system-letter trigger (EXISTS) and the WebPushMailerJob (list) (V0055).';

-- 4. System-letter trigger: push branch + restored audio branch ---------------------

CREATE OR REPLACE FUNCTION public.trigger_document_send_system_letter()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
	lt letter%ROWTYPE;
	tp letter_type%ROWTYPE;
	ma mail_account%ROWTYPE;
	lang document.person_lang%TYPE;
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
			-- V0055 (restoring pre-V0017 behaviour): audio letter types resolve the most
			-- recent published recording of the booking — merge tokens expand against it
			-- (4-arg interpret_brackets) and mail.scheduled_item_id records it, which is
			-- the per-recording dedup key of AutomaticLettersActor.sendAudioLetters().
			SELECT INTO tp * FROM letter_type WHERE id = lt.type_id;
			IF (tp.audio) THEN
				select into si_id si.id from attendance a join document_line dl on dl.id=a.document_line_id, scheduled_item si join item i on i.id=si.item_id join item_family f on f.id=i.family_id where dl.document_id=NEW.id and si.bookable_scheduled_item_id = a.scheduled_item_id and si.published and f.code='record' order by si.date desc limit 1;
			END IF;
			-- V0053: an unsupported/NULL person_lang falls back to English instead of raising
			lang := letter_lang_or_default(NEW.person_lang);
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
				EXECUTE 'SELECT ($1).push_title_' || lang INTO push_title USING lt;
				IF (push_title is null) THEN
					push_title := lt.push_title_en;
				END IF;
				EXECUTE 'SELECT ($1).push_body_' || lang INTO push_body USING lt;
				IF (push_body is null) THEN
					push_body := lt.push_body_en;
				END IF;
				IF push_title IS NULL OR push_body IS NULL THEN
					RAISE WARNING 'Letter % has push_context but no usable push title/body (lang %) — falling back to email for document %', lt.id, lang, NEW.id;
				ELSE
					push_title := interpret_brackets(push_title, NEW.id, lang, si_id);
					push_body := interpret_brackets(push_body, NEW.id, lang, si_id);
					INSERT INTO mail (account_id, letter_id, document_id, scheduled_item_id, background, subject, content, read, channel)
						values (ma.id, lt.id, NEW.id, si_id, true, push_title, push_body, true, 'push') returning id into mail_id;
					INSERT INTO history (document_id, mail_id, username, comment) values (NEW.id, mail_id, 'system', 'Sent push ''' || push_title || '''');
					push_sent := true;
				END IF;
			END IF;
			IF NOT push_sent THEN
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
					body := interpret_brackets(body, NEW.id, lang, si_id);
					subject := interpret_brackets(subject, NEW.id, lang, si_id);
					INSERT INTO mail (account_id, letter_id, document_id, scheduled_item_id, background, subject, content, read)
						values (ma.id, lt.id, NEW.id, si_id, true, subject, body, true) returning id into mail_id;
					INSERT INTO history (document_id, mail_id, username, comment) values (NEW.id, mail_id, 'system', 'Sent ''' || subject || '''');
				END IF;
			END IF;
		END IF;
	END IF;
	update document set trigger_send_system_letter_id=null where id=NEW.id;
	RETURN NEW;
END $function$;

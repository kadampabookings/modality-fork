-- V0160: staff alerts -- automated letters addressed to staff (by default the registration team), not the booker.
--
-- Plan: docs/design/staff-alert-letter-plan.md in kbs3-aggregate.
--
-- WHAT. A new letter type flag, letter_type.staff_alert, and one "Staff alert" type. A letter of that
-- type is addressed to the mail account it is sent FROM -- the one letter_from_mail_account_id() already
-- resolves: the letter's own account, else the event type's registration account, else the
-- organisation's (V0070). No address is ever typed into a letter. The values that do decide it are closed or
-- guarded (the letter-redirect concern behind V0128/V0129): letter_type, mail_account and
-- event_type.registration_mail_account_id are denied to every client write by ClientWriteSecrets, shipped with
-- this script; letter.account_id needs EditLetterProperties, organization.registration_mail_account_id
-- EditOrganizationDetails. Without the first part, a client flipping staff_alert on the Confirmation type would
-- have sent every booker's confirmation, cart link included, to whatever address the account held.
-- First use: tell the team when a booker adds an option (Sojong) online, with an automation condition
-- such as `exists(select DocumentLine where document=d and !cancelled and !read and item=<item id>)`.
--
-- "ADDED ONLINE" IS `document_line.read = false`. A front-office line is created unread, a back-office
-- line read: KBS2's back office writes read=true itself; KBS3's AddDocumentLineEvent carries no read, so
-- its lines took the column default (false) and, through cascadeunread, made the whole booking unread.
-- Section 5 makes the database mark a line read when the transaction declared itself back office.
-- Staff can mark lines read (via the booking) but never unread, so an unread line is a front-office one.
--
-- REMINDER CHAINS. The type's delay_minutes is -1, like "Event letter". KBS2's AutomaticLettersActor
-- counts a mail as a "system mail" only when coalesce(delay_minutes, 0) >= 0, and picks system letters
-- only with delay_minutes > 0. With -1 a staff alert attached to a booking neither resets nor blocks
-- the booker's no-deposit / cancellation reminders, and is never picked as one. The check constraint
-- below keeps it that way: a staff-alert type must have a negative delay.
--
-- LANGUAGE. A staff alert is composed in its organisation's language (organization.language_id),
-- English when unset or unsupported -- not the booker's. letter_mail_lang() is the one place that
-- decides, used by the two send triggers and by the recomposition behind regenerate_mail().
--
-- LOCKS. ADD COLUMN / ADD CONSTRAINT take ACCESS EXCLUSIVE on letter_type (a 20-row table, read only
-- by the letter triggers), catalogue-only. Guarded by catalogue lookups as in V0109, so the DDL block can
-- be applied by hand in a quiet window first and the boot re-runs it as a no-op. Nothing here creates or
-- drops a trigger: the document_line change replaces the body of the existing cascadeunread function.
--
-- TRIGGER SAFETY. transaction_parameter is a temp table that only set_transaction_parameters() creates
-- (the document service and KBS2 call it per booking transaction; plain client updates and migrations do
-- not -- V0076). Section 5 checks it exists before reading it, so an unread line inserted anywhere else
-- keeps today's behaviour instead of failing 42P01.
--
-- UNDELIVERABLE ALERTS. A staff alert goes only to the letter's own account, the event type's or the
-- organisation's registration account (letter_staff_alert_account_id) - never to the "closest" account the
-- sender resolution falls back to. When none applies, or it has no address, the mail is KEPT as failed
-- (transmitted, with the reason) rather than deleted as an unaddressable booker letter is: KBS2's automation
-- sends a letter to a booking while no mail of it exists, so a deleted alert would be composed and discarded
-- again every minute, for every matching booking. The history line then says it was not sent.
--
-- DISCARDED MAILS. auto_recipient deletes a booker letter it cannot address (V0059). The system-letter
-- trigger then wrote a history row pointing at the deleted mail, which failed its foreign key and aborted the
-- whole statement -- for the hourly reminder UPDATE, every booking in it. The history insert now checks the
-- mail is still there.
--
-- BASED ON the latest bodies: trigger_mail_auto_recipient = V0109, trigger_document_send_letter /
-- trigger_document_send_system_letter / letter_mail_recomposition / regenerate_mail = V0070,
-- trigger_document_line_cascadeunread = the base schema (never redefined since). Each is verbatim
-- apart from the lines marked V0160.

-- 1. Column and constraint ---------------------------------------------------------------------------

DO $ddl$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.letter_type'::regclass
                      AND attname = 'staff_alert' AND NOT attisdropped) THEN
        ALTER TABLE public.letter_type ADD COLUMN staff_alert boolean DEFAULT false NOT NULL;
        COMMENT ON COLUMN public.letter_type.staff_alert IS
            'Letters of this type go to the mail account they are sent from (the letter''s own, else the '
            'event type''s, else the organisation''s registration account), never to the booker, in the '
            'organisation''s language (V0160).';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.letter_type'::regclass
                      AND conname = 'letter_type_staff_alert_chk') THEN
        -- A staff-alert type takes no other type's addressing or schedule, and stays out of KBS2's
        -- reminder chains (negative delay).
        ALTER TABLE public.letter_type ADD CONSTRAINT letter_type_staff_alert_chk
            CHECK (NOT staff_alert OR (
                delay_minutes IS NOT NULL AND delay_minutes < 0
                AND NOT (news OR event OR terms OR cart OR "order" OR payer OR magic_link OR send_password
                         OR audio OR confirmation OR cancellation OR no_deposit1 OR no_deposit2
                         OR no_deposit3 OR no_shuttle_time)));
    END IF;
END $ddl$;

-- 2. The letter type ----------------------------------------------------------------------------------
--    ord 210, after "Event letter" (200). scope_override false: every staff alert is its own
--    rule, so an event-level one never silences an organisation-level one.

INSERT INTO letter_type (name, ord, delay_minutes, scope_override, staff_alert)
SELECT 'Staff alert', 210, -1, false, true
 WHERE NOT EXISTS (SELECT 1 FROM letter_type t WHERE t.staff_alert);

-- 3. The language a letter is composed in --------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.letter_mail_lang(lt letter, p_person_lang text, p_organization_id integer)
RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT letter_lang_or_default(
        CASE WHEN EXISTS (SELECT 1 FROM letter_type t WHERE t.id = lt.type_id AND t.staff_alert)
             THEN (SELECT btrim(l.iso_639_1) FROM organization o JOIN language l ON l.id = o.language_id
                    WHERE o.id = p_organization_id)
             ELSE p_person_lang
        END)
$$;

COMMENT ON FUNCTION public.letter_mail_lang(letter, text, integer) IS
    'The language a letter is composed in for a booking: the organisation''s for a staff alert, '
    'the booker''s otherwise; English when unset or unsupported (V0160).';

-- 3b. Where a staff alert goes ---------------------------------------------------------------------------
--    letter_from_mail_account_id()'s first three steps, WITHOUT its fourth (the organisation's "closest" mail
--    account): that fallback is fine for choosing a SENDER, but a staff alert sent to whichever account comes
--    first would reach a mailbox nobody chose. No account here = nobody to alert (see section 4).

CREATE OR REPLACE FUNCTION public.letter_staff_alert_account_id(lt letter, p_organization_id integer, p_event_id integer)
RETURNS integer
LANGUAGE sql STABLE AS $$
    SELECT ma.id FROM mail_account ma WHERE ma.id = COALESCE(
        lt.account_id,
        (SELECT et.registration_mail_account_id FROM event e JOIN event_type et ON et.id = e.type_id WHERE e.id = p_event_id),
        (SELECT o.registration_mail_account_id FROM organization o WHERE o.id = p_organization_id))
$$;

COMMENT ON FUNCTION public.letter_staff_alert_account_id(letter, integer, integer) IS
    'The mail account a staff alert goes to for that organisation/event: the letter''s own, else the event '
    'type''s registration account, else the organisation''s; NULL otherwise - never a fallback account (V0160).';

-- 4. Addressing -- V0109 body, staff-alert branch added ------------------------------------------------

CREATE OR REPLACE FUNCTION public.trigger_mail_auto_recipient() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    ma mail_account%ROWTYPE;
    doc document%ROWTYPE;
    mlink magic_link%ROWTYPE;
    type letter_type%ROWTYPE;
    email recipient.email%type;
    account_email recipient.email%type;
    recipient_person_id recipient.person_id%type;
    recipient_name recipient.name%type; -- V0160
    alert_letter letter%ROWTYPE;        -- V0160
BEGIN
    RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    IF (NOT EXISTS(select * from recipient where mail_id=NEW.id)) THEN
        IF (NEW.out) THEN -- outgoing email => sent to the person
            -- Magic-link mails first: the link's own address is authoritative even when
            -- the mail also carries a document_id (the document branch used to shadow it).
            select into mlink * from magic_link where id=NEW.magic_link_id;
            IF FOUND AND nullif(mlink.email, '') IS NOT NULL THEN
                INSERT INTO recipient (mail_id, email) values (NEW.id, mlink.email);
            ELSE
                select into doc * from document d where d.id=NEW.document_id;
                IF FOUND THEN -- email sent for a specific booking
                    -- The account login mailbox (NULL for guest bookings — no person row / no account).
                    account_email := (select fa.username from person p join frontend_account fa on fa.id=p.frontend_account_id where p.id=doc.person_id);
                    select into type lt.* from letter l join letter_type lt on lt.id=l.type_id where l.id=NEW.letter_id;
                    recipient_person_id := doc.person_id;
                    if (type.staff_alert) then
                        -- V0160: staff alerts go to the letter's own account, else the event type's,
                        -- else the organisation's registration account - never to the booker, and
                        -- never to the "closest" account the sender resolution falls back to.
                        -- Unreachable when none applies or it has no address: recorded as failed below.
                        select into alert_letter * from letter where id=NEW.letter_id;
                        select into ma * from mail_account
                         where id=letter_staff_alert_account_id(alert_letter, doc.organization_id, doc.event_id);
                        recipient_person_id := null;
                        recipient_name := ma.name;
                        email := nullif(btrim(ma.email), '');
                    elsif (type.payer and doc.payer_id is not null) then
                        -- V0109: payment requests go to whoever pays for the booking: their own
                        -- address, else their account login. Read live, never captured. No
                        -- fallback to the attendee: a payer's cart can hold other attendees'
                        -- bookings, so an unreachable payer discards the mail (below) instead.
                        recipient_person_id := doc.payer_id;
                        email := (select coalesce(nullif(p.email, ''), nullif(fa.username, ''))
                                    from person p
                                             left join frontend_account fa on fa.id = p.frontend_account_id
                                   where p.id = doc.payer_id);
                    elsif (type.order) then
                        -- Order letters carry booking access: address the account that owns the
                        -- booking (deliberate since the stage-1 guest-access arc); guests fall
                        -- back to the email typed on the booking form.
                        email := coalesce(nullif(account_email, ''), nullif(doc.person_email, ''));
                    else
                        -- All other letters target the attendee: their own address first, the
                        -- account owner's when they have none (person_email NULL = "reach them
                        -- via their account owner").
                        email := coalesce(nullif(doc.person_email, ''), nullif(account_email, ''));
                    end if;
                    IF email IS NULL AND type.staff_alert THEN
                        -- V0160: a staff alert nobody can receive is KEPT, as a failed mail (transmitted,
                        -- with the reason), not deleted. Deleting it would make KBS2's automation, which
                        -- sends a letter to a booking once there is no mail of it, compose and discard it
                        -- again every minute, for every matching booking, forever. Kept, it shows as
                        -- failed in the booking's mails; transmitted, no mailer picks it up, so the
                        -- missing recipient row is never met.
                        RAISE WARNING 'auto_recipient: no staff mail account with an address for staff alert mail % (document %), recorded as failed', NEW.id, doc.id;
                        UPDATE mail SET transmitted = true, transmission_date = now(),
                                        error = 'Staff alert not sent: no mail account with an address (letter, event type or organisation)'
                         WHERE id = NEW.id;
                    ELSIF email IS NULL THEN
                        -- Truly unreachable (guest with no usable email): discard the mail
                        -- instead of violating recipient.email NOT NULL and rolling back the
                        -- enclosing transaction (booking confirm, system-letter batch, ...).
                        -- The mail row is deleted (not left recipient-less) so the mailers
                        -- never meet an address-less mail — KBS2's JavaMailSender indexes
                        -- getRecipients(TO)[0] unguarded. Same self-delete pattern as the
                        -- existing discard trigger on mail.
                        RAISE WARNING 'auto_recipient: no address resolvable for mail % (document %), mail discarded', NEW.id, doc.id;
                        DELETE FROM mail WHERE id = NEW.id;
                    ELSE
                        INSERT INTO recipient (mail_id, person_id, name, email) values (NEW.id, recipient_person_id, recipient_name, email); -- V0160: name
                    END IF;
                END IF;
            END IF;
        ELSE -- incoming email => sent to the mail_account
            SELECT INTO ma * FROM mail_account where id=NEW.account_id LIMIT 1;
            IF FOUND THEN
                INSERT INTO recipient (mail_id, name, email) values (NEW.id, ma.name, ma.email);
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;

-- 5. Back-office lines are created read -- base body, back-office branch added --------------------------
--    cascadeunread fires AFTER INSERT OR UPDATE OF read WHEN (NOT new.read): exactly when a line becomes
--    unread. In a transaction that declared itself back office, the line is marked read instead of the
--    booking unread. The UPDATE sets read=true, so it does not re-fire this trigger. The temp table is
--    checked before it is read, in a nested IF: plpgsql would plan the whole condition at once.

CREATE OR REPLACE FUNCTION public.trigger_document_line_cascadeunread() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
	IF to_regclass('pg_temp.transaction_parameter') IS NOT NULL THEN -- V0160
		IF EXISTS (SELECT 1 FROM transaction_parameter tp WHERE tp.backend) THEN
			UPDATE document_line SET read=true WHERE id=NEW.id AND NOT read;
			RETURN NEW;
		END IF;
	END IF;
	UPDATE document set read=false where id=NEW.document_id and read;
	RETURN NEW;
END $$;

-- 6. The two send triggers -- V0070 bodies, language through letter_mail_lang() ---------------------------

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
			-- V0160: a staff alert takes its organisation's language
			v_lang := letter_mail_lang(lt, NEW.person_lang, NEW.organization_id);
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
	mail_failed boolean; -- V0160
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
			-- V0160: a staff alert takes its organisation's language
			v_lang := letter_mail_lang(lt, NEW.person_lang, NEW.organization_id);
			-- V0055: web-push branch — when the letter has a push variant AND the booker
			-- has a live subscription on its context AND no push was already created for
			-- this (document, letter, scheduled item), create a channel='push' mail
			-- instead of an email. The already-created guard is what makes the sender's
			-- email fallback work: on re-fire, this branch is skipped and the email path
			-- below runs.
			-- V0160: never for a staff alert, which is not for the booker.
			IF lt.push_context IS NOT NULL AND NOT tp.staff_alert
			   AND EXISTS (SELECT 1 FROM live_push_endpoints(lt.push_context, NEW.person_email, NEW.person_id))
			   AND NOT EXISTS (SELECT 1 FROM mail m WHERE m.channel = 'push' AND m.document_id = NEW.id
			                     AND m.letter_id = lt.id AND m.scheduled_item_id IS NOT DISTINCT FROM si_id) THEN
				SELECT * INTO push_title, push_body FROM letter_compose_push(lt, v_lang, NEW.id, si_id);
				IF push_title IS NULL OR push_body IS NULL THEN
					RAISE WARNING 'Letter % has push_context but no usable push title/body (lang %) — falling back to email for document %', lt.id, v_lang, NEW.id;
				ELSE
					INSERT INTO mail (account_id, letter_id, document_id, scheduled_item_id, background, subject, content, read, channel)
						values (v_account_id, lt.id, NEW.id, si_id, true, push_title, push_body, true, 'push') returning id into mail_id;
					IF EXISTS (SELECT 1 FROM mail m WHERE m.id = mail_id) THEN -- V0160: see the email branch
						INSERT INTO history (document_id, mail_id, username, comment) values (NEW.id, mail_id, 'system', 'Sent push ''' || push_title || '''');
					END IF;
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
					-- V0160: auto_recipient deletes a mail it cannot address; a history row pointing
					-- at it would then fail its foreign key and abort the caller's whole statement.
					-- And say who received it, so staff reading the history do not take a
					-- staff alert for a mail the booker got.
					-- A staff alert nobody could receive is kept as a failed mail (auto_recipient), and
					-- its history line says so.
					SELECT m.transmitted INTO mail_failed FROM mail m WHERE m.id = mail_id;
					IF FOUND THEN
						INSERT INTO history (document_id, mail_id, username, comment) values (NEW.id, mail_id, 'system',
							CASE WHEN tp.staff_alert AND mail_failed THEN 'Staff alert not sent (no mail account with an address): '''
							     WHEN tp.staff_alert THEN 'Alerted staff: '''
							     ELSE 'Sent ''' END || subject || '''');
					END IF;
				END IF;
			END IF;
		END IF;
	END IF;
	update document set trigger_send_system_letter_id=null where id=NEW.id;
	RETURN NEW;
END $function$;

-- 7. Recomposition -- V0070 body, language through letter_mail_lang() -------------------------------------

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
    -- V0160: a staff alert takes its organisation's language, as at generation time.
    v_lang := letter_mail_lang(lt, d.person_lang, d.organization_id);
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

-- 7b. Regeneration -- V0070 body, the staff alert's history line retargeted too ----------------------------

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
    v_staff_alert boolean; -- V0160
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
        -- V0160: a staff alert's entry reads 'Alerted staff: ...', not 'Sent ...'
        SELECT lt.staff_alert INTO v_staff_alert
          FROM mail m JOIN letter l ON l.id = m.letter_id JOIN letter_type lt ON lt.id = l.type_id
         WHERE m.id = p_mail_id;
        v_prefix := CASE WHEN v_channel = 'push' THEN 'Sent push '''
                         WHEN v_staff_alert THEN 'Alerted staff: '''
                         ELSE 'Sent ''' END;
        UPDATE history SET comment = v_prefix || v_subject || ''''
         WHERE mail_id = p_mail_id AND comment = v_prefix || v_old_subject || '''';
    END IF;
    RETURN true;
END $function$;

-- 8. Ownership of the functions this migration created ------------------------------------------------
--    Migrations run as the server's connect role, so anything they CREATE is owned by that role instead
--    of the schema owner (see V0064, V0070). Realign, warning rather than failing.

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
           AND p.proname IN ('letter_mail_lang', 'letter_staff_alert_account_id')
           AND pg_get_userbyid(p.proowner) <> schema_owner
    LOOP
        BEGIN
            EXECUTE format('ALTER FUNCTION %s OWNER TO %I', fn.signature, schema_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0160: could not realign the owner of % (%)', fn.signature, SQLERRM;
        END;
    END LOOP;
END $$;

-- V0066: history emails honour the registration mail account (V0017 alignment).
--
-- Event.sendHistoryEmails notifies the registration team when an online booker changes
-- a booking: the online_defer_send_email trigger sets history.trigger_defer_send_email,
-- which fires deferred_send_history_email to insert an out=false mail whose recipient
-- the auto_recipient trigger takes from the mail account's own address.
--
-- V0017 gave the LETTER triggers a proper account chain (letter.account → event type's
-- registration account → organization's), but this function was never updated: it still
-- picks the event-bound account, else the organization's LOWEST-ID account, ignoring
-- registration_mail_account_id entirely. So an event type with a dedicated registration
-- mailbox never receives its history emails — they land in the centre's general inbox,
-- and the registrar watching the dedicated box sees nothing. Reported for the CMK France
-- "Célébration du Dharma" festivals, whose event type points at
-- inscriptions-festival@ while the mails were going to inscriptions@.
--
-- Precedence here deliberately differs from V0017's letter chain by putting the
-- event-bound mail account FIRST. V0017 can demote it because a letter carries its own
-- account; a history email has no letter, and an account explicitly bound to one event
-- is the most specific statement of intent there is. Copying V0017's order verbatim
-- would have hijacked the two Bordeaux events away from their local branch mailbox
-- (info@meditation-bordeaux.org) to the France centre — measured on prod before writing
-- this. With event-first, the only events that change destination are the 4 Célébration
-- festivals; the other 993 flagged events are untouched, and none fall through to the
-- legacy fallback (kept anyway, for DBs whose data differs).
--
-- Everything below the account resolution is byte-for-byte the previous function.

CREATE OR REPLACE FUNCTION public.deferred_send_history_email() RETURNS trigger
    LANGUAGE plpgsql
    AS $function$
DECLARE
	ma mail_account%ROWTYPE;
	subject mail.subject%TYPE;
	body mail.content%TYPE;
BEGIN
	RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
	-- Account resolution: event-bound account, else the event type's registration
	-- account, else the organization's (see the header for why event comes first).
	SELECT INTO ma * FROM mail_account WHERE id = COALESCE(
		(SELECT m.id FROM mail_account m JOIN document d ON d.id = NEW.document_id
		  WHERE m.event_id = d.event_id ORDER BY m.id LIMIT 1),
		(SELECT et.registration_mail_account_id FROM document d
		   JOIN event e ON e.id = d.event_id
		   JOIN event_type et ON et.id = e.type_id
		  WHERE d.id = NEW.document_id),
		(SELECT o.registration_mail_account_id FROM document d
		   JOIN organization o ON o.id = d.organization_id
		  WHERE d.id = NEW.document_id)
	);
	IF NOT FOUND THEN -- legacy fallback: organization's lowest-id account
		SELECT INTO ma * FROM mail_account m_a join document d on d.id=NEW.document_id where m_a.organization_id=d.organization_id order by case when m_a.event_id=d.event_id then 0 else m_a.id end LIMIT 1;
	END IF;
	IF FOUND THEN
		subject := substring(interpret_brackets('KBS2 [eventName] ([eventId]) - [fullName] #[ref] ' || NEW.comment, NEW.document_id, 'en') for 255);
		body := interpret_brackets('<html><body>' || NEW.comment || '<hr/>' || case when NEW.request is not null then NEW.request || '<hr/>' else '' end || '[personalDetails]<hr/>[options]<hr/>Invoiced: [invoiced]<br/>Deposit: [deposit]<br/>Balance: [balance]<hr/>[yourCart]</body></html>', NEW.document_id, 'en');
		INSERT INTO mail (account_id, letter_id, document_id, background, subject, content, read, out, auto_delete) values (ma.id, null, NEW.document_id, true, subject, body, true, false, true);
	END IF;
RETURN NEW;
END $function$;

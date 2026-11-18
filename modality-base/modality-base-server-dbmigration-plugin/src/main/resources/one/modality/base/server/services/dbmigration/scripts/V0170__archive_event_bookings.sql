-- V0170: retention stage 2 — anonymise the registrations of a past event (or a continuous event's departed stays):
-- archive_event_bookings(). PROVISIONAL RULES, pending the Data Protection Officer: aggregate repo,
-- docs/design/event-archive-anonymisation.md (the rules and the DPO's questions) and scripts/gdpr-erasure/manifest.tsv
-- (column `archive`: what this removes and keeps, column by column). A change of rule is a new migration that
-- CREATE OR REPLACEs this function (same signature: no overload), with the REVOKE repeated.
--
-- WHAT IT DOES, for the bookings it reaches ("the set"; see THE SET): the identity snapshot on each booking becomes
-- 'Anonymised' / '#B<booking id>' (address, phone, street, passport, nationality, town and region, coordinates, IP,
-- carers cleared; country, sex, age, language, typed centre KEPT for statistics); room-mates' names typed on its lines
-- cleared; its mails and their recipients DELETED; its history DELETED and one marker row written ('gdpr-retention',
-- 'Registration anonymised (GDPR retention policy)'); its conversations (and, for a whole event, the event's own) with
-- their messages, reactions and participants DELETED; its list items DELETED; push registrations made for it DELETED
-- (and a subscription left with no registration); payment-gateway replies on its payments cleared (amounts, dates,
-- methods, transaction references KEPT); executed KBS2 payloads tagged; a cart holding only bookings of the set (or
-- already archived) gets a new random key and loses its magic link, which is deleted; a one-off person record used by
-- nothing else (no account, no other live booking, no role, staff grant, teacher, volunteering application,
-- invitation, subscription...) becomes 'Anonymised' / '#P<person id>' with its contact details, town, region and
-- nationality cleared (country kept, as on the booking). Each booking is
-- stamped document.archived_date (added here). Lines, attendance, prices, items, rooms and payments are kept.
--
-- THE SET. A whole event (p_departed_before NULL): its bookings not archived yet. A continuous event: those whose
-- person LEFT before p_departed_before (event_departed_bookings, V0169), not archived yet. LEFT OUT and counted, never
-- touched: a booking with money still owed either way, or still running (anonymise_person_blocking, V0153), or
-- carrying a volunteering application (which holds the same person in full, and has no retention routine yet).
--
-- WHAT STOPS IT (raises '[Key] text'; nothing is written):
--   EventArchiveFinaliseFirstError   a booking of the set, one of its lines or one of its payments still holds free
--                                    text stage 1 removes: run stage 1 first
--   EventArchiveQueuedMailError      a mail of the set is still waiting to be sent
--   EventArchiveQueuedPayloadsError  an un-executed KBS2 request still holds a booking of the set
--   EventArchiveSchemaChangedError   a link to bookings, to people or to rows it deletes, or a text column on a table
--                                    it touches, that this
--                                    version was not written for — a new personal-data column cannot slip past it
--   EventArchiveContinuousEventError / EventArchiveNotContinuousError / EventArchiveNotEndedError  wrong scope
-- PROOFS before it returns (any failure raises; the caller rolls back): no booking of the set names anyone or holds
-- an address; nothing of the set's mails, history (but the markers), conversations, list items or push
-- registrations is left; and outside the set — bookings archived or pseudonymised or carrying an address, people
-- anonymised or carrying an address, mails, recipients, history, conversations, messages, list items, push
-- registrations, magic links, every other cart's key, other lines' mates' names, other payments' gateway replies —
-- every count and digest is unchanged.
--
-- FOUR INTERLOCKS, as V0077: SET LOCAL kbs.erase_confirm = 'ARCHIVE EVENT <id>' (or 'ARCHIVE DEPARTED <id>
-- <YYYY-MM-DD>'); a REPEATABLE READ transaction; the call a LATER statement of it; EXECUTE revoked from PUBLIC. It
-- never commits. The back office runs it through EventErasureRunner (stage 'archive'), Data Protection Officers only,
-- commits behind EVENT_ARCHIVE_COMMIT_ENABLED.
--
-- KNOWN LIMIT: for a continuous event, only the departed bookings' own conversations go. A conversation of the event
-- itself (no booking) is left whole, with whatever a departed guest wrote in it; none exists on a continuous event
-- today (staging, 2026-11-18: 6 conversations without a booking, none on a continuous event).
--
-- TRIGGERS: none disabled. The document/person updates are V0163's columns (set_abc_names recomputes from the
-- pseudonym); the person is anonymised BEFORE its bookings, because a person trigger copies the person's details
-- onto bookings of events not yet ended (see step 0); the line update touches names only, which the room-sharing triggers (UPDATE OF site, item, dates, owner
-- link) do not watch; deletes cascade where the schema says so (recipients with their mail).
--
-- SHIPPED = FROZEN.

-- KEPT ON THE BOOKING, and still a link to a person: person_id when it is an account holder's (their own history);
-- payer_id; the room-sharing links on its lines; its group and cart; its reference; the centre typed, the ordained
-- and unemployed flags; the payments' transaction references (keys into the gateway's own records). The DPO document
-- lists them. "Anonymised" here means the booking no longer names or locates anyone by itself.
--
-- ALSO IN THIS FILE: the person trigger skips archived bookings (section 2), and ARCHIVED becomes final and written by
-- an anonymisation only (section 3).
--
-- ===== 1. The archive stamp =================================================================================

ALTER TABLE public.document ADD COLUMN IF NOT EXISTS archived_date date;

COMMENT ON COLUMN public.document.archived_date IS
    'V0170: the day this registration was anonymised by retention stage 2 (archive_event_bookings). NULL = not archived.';

-- ===== 2. The person trigger leaves an archived booking alone ===================================================
-- trigger_person_on_details_change_update_upcoming_bookings (last defined in V0080) copies a person's details onto their
-- bookings of events not yet ended. A continuous event has not ended, so an account holder editing their profile
-- would write their name and address back onto a stay archived years ago. Restated whole from V0080's body, with one
-- condition added (the last line of its WHERE).

CREATE OR REPLACE FUNCTION public.trigger_person_on_details_change_update_upcoming_bookings() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN

    update document du
    set person_first_name      = pa.first_name,
        person_last_name       = pa.last_name,
        person_male            = pa.male,
        person_ordained        = pa.ordained,
        person_lay_name        = pa.lay_name,
        person_phone           = pa.phone,
        -- V0080: same resolution as the capture trigger, so re-syncing an
        -- upcoming booking cannot put the NULL back.
        person_email           = coalesce(
                                     nullif(pa.email, ''),
                                     (select nullif(fa.username, '') from frontend_account fa where fa.id = pa.frontend_account_id)),
        person_organization_id = pa.organization_id,
        person_street          = pa.street,
        person_post_code       = pa.post_code,
        person_city_name       = pa.city_name,
        person_country_id      = pa.country_id
    from document d
             join person p on p.id = d.person_id
             join person pa on pa.id = coalesce(p.account_person_id, p.id)
             join event e on e.id = d.event_id
    where du.id = d.id
      and d.person_id = NEW.id
      and e.end_date > now()
      -- V0170: an anonymised registration stays anonymised, whatever its person's details become
      and d.archived_date is null;

    return NEW;
END
$$;

-- ===== 3. ARCHIVED is written by an anonymisation only, and is final ===========================================
-- V0147 let any writer move an event between FINALISED and ARCHIVED (the archive toggle the lifecycle page had).
-- ARCHIVED now means "its registrations were anonymised", so it is written only by the anonymisation of that event,
-- and nothing moves an event out of it. Restated whole from V0147's body; the trigger itself is unchanged. The two
-- markers are V0147's, so every client that maps them keeps working.

CREATE OR REPLACE FUNCTION public.trigger_event_state_guards() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    state_changed boolean;
    closed_family boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- A new event is never born sealed: FINALISED is written by a removal, ARCHIVED only follows FINALISED.
        IF NEW.state = 'FINALISED' THEN
            RAISE EXCEPTION 'EVENT_FINALISED_BY_REMOVAL_ONLY';
        END IF;
        IF NEW.state = 'ARCHIVED' THEN
            RAISE EXCEPTION 'EVENT_ARCHIVED_FROM_FINALISED_ONLY';
        END IF;
        RETURN NEW;
    END IF;
    -- Nothing this trigger guards has changed: let the row through, whatever it holds.
    IF OLD.state IS NOT DISTINCT FROM NEW.state AND OLD.removed IS NOT DISTINCT FROM NEW.removed THEN
        RETURN NEW;
    END IF;
    state_changed := OLD.state IS DISTINCT FROM NEW.state;
    closed_family := coalesce(NEW.state IN ('CLOSED', 'RECONCILED', 'FINALISED', 'ARCHIVED'), false);
    IF state_changed THEN
        -- Sealed: the event's personal data has been removed. FINALISED moves on only to ARCHIVED; ARCHIVED is the end
        -- (V0170: the registrations are anonymised; there is no way back, so there is no "unarchive" either).
        IF (OLD.state = 'FINALISED' AND (NEW.state IS NULL OR NEW.state <> 'ARCHIVED'))
           OR OLD.state = 'ARCHIVED' THEN
            RAISE EXCEPTION 'EVENT_STATE_SEALED';
        END IF;
        -- Archived: entered only from FINALISED, and only inside an anonymisation of THIS event (V0170: the runner sets
        -- kbs.erase_confirm = 'ARCHIVE EVENT <id>' in the transaction that anonymised its registrations).
        IF NEW.state = 'ARCHIVED'
           AND (OLD.state IS DISTINCT FROM 'FINALISED'
                OR current_setting('kbs.erase_confirm', true) IS DISTINCT FROM ('ARCHIVE EVENT ' || NEW.id)) THEN
            RAISE EXCEPTION 'EVENT_ARCHIVED_FROM_FINALISED_ONLY';
        END IF;
        -- FINALISED is written by a removal of a RECONCILED event, inside its own transaction, and by nothing else.
        IF NEW.state = 'FINALISED'
           AND (OLD.state IS DISTINCT FROM 'RECONCILED'
                OR current_setting('kbs.erase_confirm', true) IS DISTINCT FROM ('ERASE EVENT ' || NEW.id)) THEN
            RAISE EXCEPTION 'EVENT_FINALISED_BY_REMOVAL_ONLY';
        END IF;
        -- Reconciled: back to CLOSED (the undo) or on to FINALISED (the removal, guarded just above), nowhere else.
        IF OLD.state = 'RECONCILED' AND (NEW.state IS NULL OR NEW.state NOT IN ('CLOSED', 'FINALISED')) THEN
            RAISE EXCEPTION 'EVENT_RECONCILED_CLOSED_ONLY';
        END IF;
    END IF;
    -- A removed event stays closed to bookings, whenever its state moves or it is being removed.
    IF NEW.removed IS TRUE AND (state_changed OR OLD.removed IS NOT TRUE) AND NOT closed_family THEN
        RAISE EXCEPTION 'EVENT_REMOVED_STAYS_CLOSED';
    END IF;
    -- An event whose data a commit removed -- or is removing right now -- stays closed too: asked only when it
    -- would open. A running commit's ledger row was committed before its transaction began, so it is visible here.
    IF state_changed AND NOT closed_family THEN
        IF EXISTS (SELECT 1 FROM public.event_erasure_run r
                    WHERE r.event_id = NEW.id AND r.mode = 'commit' AND r.status IN ('succeeded', 'running')) THEN
            RAISE EXCEPTION 'EVENT_DATA_REMOVED_STAYS_CLOSED';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION public.trigger_event_state_guards() IS
    'V0147, restated in V0170: refuses a new event born FINALISED or ARCHIVED, and state/removed changes that would '
    'unseal a FINALISED event other than towards ARCHIVED, or move an ARCHIVED one at all (EVENT_STATE_SEALED), enter '
    'ARCHIVED other than from FINALISED inside an anonymisation of that event (EVENT_ARCHIVED_FROM_FINALISED_ONLY), '
    'write FINALISED outside a removal of a RECONCILED event (EVENT_FINALISED_BY_REMOVAL_ONLY), move a RECONCILED '
    'event anywhere but CLOSED or FINALISED (EVENT_RECONCILED_CLOSED_ONLY), take a removed event out of the closed '
    'family (EVENT_REMOVED_STAYS_CLOSED) or reopen an event a removal erased or is erasing (a succeeded or running '
    'commit: EVENT_DATA_REMOVED_STAYS_CLOSED). No application override: a DBA disables the trigger by hand.';

-- ===== 4. archive_event_bookings ===============================================================================

CREATE OR REPLACE FUNCTION public.archive_event_bookings(p_event_id integer, p_departed_before date DEFAULT NULL)
RETURNS TABLE(step text, metric text, value text)
LANGUAGE plpgsql
SET timezone TO 'Europe/London'
AS $archive$
DECLARE
    v_tag_prefix constant text := '[personal data removed (GDPR)';
    v_marker     constant text := 'Registration anonymised (GDPR retention policy)';
    v_tag        text;
    v_confirm    text := nullif(current_setting('kbs.erase_confirm', true), '');
    v_phrase     text;
    v_text       text;
    v_before     text;
    v_after      text;
    v_bookings   bigint;
    ev  record;
    n   bigint;
    -- What this version was written for. A link or a text column outside these lists refuses the run.
    c_document_fks constant text[] := ARRAY[
        'document.group_document_id', 'document.person_carer1_document_id', 'document.person_carer2_document_id',
        'document_line.document_id', 'money_transfer.document_id', 'conversation.document_id',
        'enqueued_request.document_id', 'history.document_id', 'list_item.document_id', 'mail.document_id',
        'push_subscription_recipient.document_id', 'volunteering_application.document_id'];
    c_person_fks constant text[] := ARRAY[
        'document.payer_id', 'document.person_carer1_id', 'document.person_carer2_id', 'document.person_id',
        'money_transfer.verifier_person_id', 'activity_state.owner_id', 'auth_session.person_id',
        'authorization_management.manager_id', 'authorization_management.user_id', 'authorization_organization_admin.admin_id',
        'authorization_organization_user_access.user_id', 'authorization_super_admin.super_admin_id',
        'chat_message.person_id', 'chat_message_reaction.person_id', 'chat_presence.person_id', 'conversation.assignee_id',
        'conversation.viewer_person_id', 'conversation_participant.person_id', 'driver.person_id', 'error.user_person_id',
        'history.user_person_id', 'invitation.created_alias_person_id', 'invitation.invitee_id', 'invitation.inviter_id',
        'list.created_by_id', 'list_item.payer_id', 'list_item.person_id', 'pass_template.created_by',
        'person.account_person_id', 'person.carer1_id', 'person.carer2_id', 'push_subscription_recipient.person_id',
        'recipient.person_id', 'role_attribution.person_id', 'session_user.user_id', 'subscription.person_id',
        'support_report.person_id', 'teacher.person_id', 'volunteering_application.person_id'];
    -- Links INTO the tables this routine deletes from: a new one could cascade, or keep a row it should not
    c_deleted_tables constant text[] := ARRAY['mail', 'recipient', 'history', 'conversation', 'chat_message', 'list_item',
        'magic_link', 'push_subscription', 'push_subscription_recipient'];
    c_deleted_fks constant text[] := ARRAY[
        'cart.magic_link_id', 'chat_message.conversation_id', 'chat_message.reply_to_id', 'chat_message_reaction.message_id',
        'conversation_participant.conversation_id', 'history.mail_id', 'mail.magic_link_id',
        'mate_invite_token.invite_mail_id', 'push_subscription_recipient.subscription_id', 'recipient.mail_id'];
    c_tables constant text[] := ARRAY['document', 'document_line', 'person', 'mail', 'recipient', 'history', 'conversation',
        'chat_message', 'list_item', 'magic_link', 'cart', 'push_subscription_recipient', 'money_transfer', 'enqueued_request'];
    c_columns constant text[] := ARRAY[
        'cart.uuid', 'chat_message.content', 'chat_message.kind', 'conversation.status', 'conversation.title',
        'conversation.type', 'conversation.viewer_device', 'conversation.viewer_lang', 'document.accounting_date',
        'document.archived_date', 'document.comment', 'document.dates', 'document.inet_ip', 'document.person_abc_names',
        'document.person_admin1_code', 'document.person_admin1_name', 'document.person_admin2_code',
        'document.person_admin2_name', 'document.person_carer1_name', 'document.person_carer2_name',
        'document.person_city_name', 'document.person_city_timezone', 'document.person_country_code',
        'document.person_country_name', 'document.person_email', 'document.person_first_name', 'document.person_lang',
        'document.person_last_name', 'document.person_lay_name', 'document.person_name', 'document.person_nationality',
        'document.person_organization_name', 'document.person_passport', 'document.person_phone',
        'document.person_post_code', 'document.person_street', 'document.request', 'document.special_needs',
        'document_line.cancellation_date', 'document_line.comment', 'document_line.dates', 'document_line.end_date',
        'document_line.share_mate_owner_name', 'document_line.share_owner_mate1_name', 'document_line.share_owner_mate2_name',
        'document_line.share_owner_mate3_name', 'document_line.share_owner_mate4_name', 'document_line.share_owner_mate5_name',
        'document_line.share_owner_mate6_name', 'document_line.share_owner_mate7_name', 'document_line.start_date',
        'enqueued_request.client_push_address', 'enqueued_request.error', 'enqueued_request.reply_string',
        'enqueued_request.request_string', 'history.changes', 'history.comment', 'history.request', 'history.username',
        'list_item.comment', 'magic_link.email', 'magic_link.lang', 'magic_link.link', 'magic_link.link_type',
        'magic_link.login_run_id', 'magic_link.old_email', 'magic_link.requested_path', 'magic_link.token',
        'magic_link.usage_run_id', 'magic_link.verification_code', 'mail.channel', 'mail.content', 'mail.error',
        'mail.from_email', 'mail.from_name', 'mail.subject', 'money_transfer.comment', 'money_transfer.gateway_accesscode',
        'money_transfer.gateway_generate_payment_url_response', 'money_transfer.gateway_response', 'money_transfer.sessionid',
        'money_transfer.status', 'money_transfer.transaction_ref', 'money_transfer.verifier', 'person.abc_names',
        'person.account_person_revoked_date', 'person.address_deprecated_date', 'person.admin1_name', 'person.admin2_name',
        'person.birthdate', 'person.carer1_name', 'person.carer2_name', 'person.city_name', 'person.city_timezone',
        'person.country_code', 'person.country_name', 'person.details_confirmed_date', 'person.details_edited_date',
        'person.email', 'person.first_name', 'person.gender_changed_date', 'person.last_name', 'person.lay_name',
        'person.name', 'person.nationality', 'person.organization_deprecated_date', 'person.organization_name',
        'person.passport', 'person.phone', 'person.post_code', 'person.street', 'push_subscription_recipient.context',
        'push_subscription_recipient.email', 'push_subscription_recipient.user_agent', 'recipient.email', 'recipient.name'];
BEGIN
    v_tag := v_tag_prefix || ' on ' || current_date || ']';

    -- ================= INTERLOCKS =================
    IF p_event_id IS NULL THEN
        RAISE EXCEPTION 'archive_event_bookings: the event id is NULL';
    END IF;
    IF current_setting('transaction_isolation') NOT IN ('repeatable read', 'serializable') THEN
        RAISE EXCEPTION 'archive_event_bookings must run in a REPEATABLE READ transaction (the before/after proof needs one pinned snapshot)';
    END IF;
    IF transaction_timestamp() = statement_timestamp() THEN
        RAISE EXCEPTION 'archive_event_bookings refuses to run as a single autocommit statement — it would commit with no review';
    END IF;
    v_phrase := (CASE WHEN p_departed_before IS NULL THEN 'ARCHIVE EVENT ' || p_event_id
                      ELSE 'ARCHIVE DEPARTED ' || p_event_id || ' ' || to_char(p_departed_before, 'YYYY-MM-DD') END);
    IF v_confirm IS DISTINCT FROM v_phrase THEN
        RAISE EXCEPTION 'archive_event_bookings: missing statement of intent — run  SET LOCAL kbs.erase_confirm = ''%'';  in this transaction first (found: %)',
                        v_phrase, coalesce(v_confirm, '<unset>');
    END IF;

    SELECT e.id, e.name, e.start_date, e.end_date, e.state::text AS state, coalesce(t.ongoing, false) AS ongoing
      INTO ev FROM event e LEFT JOIN event_type t ON t.id = e.type_id WHERE e.id = p_event_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'event % does not exist', p_event_id;
    END IF;
    IF p_departed_before IS NULL THEN
        IF ev.ongoing THEN
            RAISE EXCEPTION '[EventArchiveContinuousEventError] event % is continuous: its registrations are anonymised per stay, by departure', p_event_id;
        END IF;
        IF ev.end_date IS NULL OR ev.end_date >= current_date THEN
            RAISE EXCEPTION '[EventArchiveNotEndedError] event % has not ended', p_event_id;
        END IF;
        -- Stage 1 first, on the event's own record: sealed by a removal, or (a KBS2 event keeps no state) a succeeded
        -- stage-1 commit in the ledger. The free-text refusal below is the second line, not the only one.
        IF NOT (coalesce(ev.state, '') IN ('FINALISED', 'ARCHIVED')
                OR (ev.state IS NULL AND EXISTS (SELECT 1 FROM event_erasure_run r
                                                  WHERE r.event_id = p_event_id AND r.stage = 'finalise' AND r.kind = 'event'
                                                    AND r.mode = 'commit' AND r.status = 'succeeded'))) THEN
            RAISE EXCEPTION '[EventArchiveFinaliseFirstError] event % has not had its sensitive details removed: run stage 1 first', p_event_id;
        END IF;
    ELSE
        IF NOT ev.ongoing THEN
            RAISE EXCEPTION '[EventArchiveNotContinuousError] event % is not continuous: its registrations are anonymised whole', p_event_id;
        END IF;
        IF p_departed_before > current_date THEN
            RAISE EXCEPTION '[EventArchiveNotEndedError] the cutoff % is after today — it would reach people still staying', p_departed_before;
        END IF;
    END IF;

    -- ================= SCHEMA GUARD =================
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_text FROM (
        SELECT c.conrelid::regclass::text || '.' || a.attname AS k
          FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.contype = 'f' AND c.confrelid = 'public.document'::regclass) x
     WHERE k <> ALL (c_document_fks);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[EventArchiveSchemaChangedError] links to bookings this version does not handle: %', v_text;
    END IF;
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_text FROM (
        SELECT replace(c.conrelid::regclass::text, '"', '') || '.' || a.attname AS k
          FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.contype = 'f' AND c.confrelid = 'public.person'::regclass) x
     WHERE k <> ALL (c_person_fks);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[EventArchiveSchemaChangedError] links to people this version does not handle: %', v_text;
    END IF;
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_text FROM (
        SELECT replace(c.conrelid::regclass::text, '"', '') || '.' || a.attname AS k
          FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.contype = 'f' AND c.confrelid = ANY (SELECT ('public.' || t)::regclass FROM unnest(c_deleted_tables) t)) x
     WHERE k <> ALL (c_deleted_fks);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[EventArchiveSchemaChangedError] links to rows this version deletes, which it does not handle: %', v_text;
    END IF;
    SELECT string_agg(c.table_name || '.' || c.column_name, ', ' ORDER BY c.table_name, c.column_name) INTO v_text
      FROM information_schema.columns c
     WHERE c.table_schema = 'public' AND c.table_name = ANY (c_tables)
       AND c.data_type IN ('character varying', 'text', 'character', 'date', 'inet', 'jsonb')
       AND (c.table_name || '.' || c.column_name) <> ALL (c_columns);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[EventArchiveSchemaChangedError] text columns this version was not written for: %', v_text;
    END IF;

    -- Deferred triggers fired at COMMIT read this session temp table; a safety net (V0077).
    PERFORM set_transaction_parameters(true);

    -- ================= THE SET =================
    DROP VIEW IF EXISTS ar_outside_now;
    DROP TABLE IF EXISTS ar_candidates, ar_left, ar_docs, ar_persons, ar_carts, ar_links, ar_convs, ar_subs, ar_parents, ar_before, ar_after;

    SELECT count(*) INTO v_bookings FROM document d WHERE d.event_id = p_event_id;

    CREATE TEMP TABLE ar_candidates (id integer PRIMARY KEY);
    IF p_departed_before IS NULL THEN
        INSERT INTO ar_candidates SELECT d.id FROM document d WHERE d.event_id = p_event_id AND d.archived_date IS NULL;
    ELSE
        INSERT INTO ar_candidates
        SELECT b.document_id FROM event_departed_bookings(p_event_id, p_departed_before) b
          JOIN document d ON d.id = b.document_id WHERE d.archived_date IS NULL;
    END IF;

    -- Left out, never touched: money owed either way, or still running (V0153's rule)
    -- ... or carrying a volunteering application: the application holds the same person in full (name, address, health,
    -- convictions) and no retention routine reaches it yet, so anonymising the booking beside it would claim an
    -- anonymisation that one join undoes. Left out and counted until the applications have their own rule.
    CREATE TEMP TABLE ar_left AS
    SELECT x.document_id AS id, min(x.kind) AS kind
      FROM (SELECT b.document_id, b.kind FROM anonymise_person_blocking(ARRAY(SELECT c.id FROM ar_candidates c)) b
            UNION ALL
            SELECT va.document_id, 'application' FROM volunteering_application va JOIN ar_candidates c ON c.id = va.document_id) x
     GROUP BY x.document_id;

    CREATE TEMP TABLE ar_docs (id integer PRIMARY KEY);
    INSERT INTO ar_docs SELECT c.id FROM ar_candidates c WHERE NOT EXISTS (SELECT 1 FROM ar_left l WHERE l.id = c.id);
    ANALYZE ar_docs;

    -- ================= REFUSALS (before any write) =================
    SELECT count(*) INTO n FROM document d JOIN ar_docs t ON t.id = d.id
     WHERE (d.special_needs IS NOT NULL AND d.special_needs NOT LIKE v_tag_prefix || '%')
        OR (d.comment IS NOT NULL AND d.comment NOT LIKE v_tag_prefix || '%')
        OR (d.request IS NOT NULL AND d.request NOT LIKE v_tag_prefix || '%')
        OR EXISTS (SELECT 1 FROM document_line dl WHERE dl.document_id = d.id
                     AND dl.comment IS NOT NULL AND dl.comment NOT LIKE v_tag_prefix || '%')
        -- staff notes on its payments: stage 1 tags them, and this stage keeps the payments as they are
        OR EXISTS (SELECT 1 FROM money_transfer mt WHERE mt.document_id = d.id
                     AND mt.comment IS NOT NULL AND mt.comment NOT LIKE v_tag_prefix || '%');
    IF n > 0 THEN
        RAISE EXCEPTION '[EventArchiveFinaliseFirstError] % bookings of event % still hold free text: remove their sensitive details first', n, p_event_id;
    END IF;
    SELECT count(*) INTO n FROM mail m JOIN ar_docs t ON t.id = m.document_id WHERE NOT m.transmitted;
    IF n > 0 THEN
        RAISE EXCEPTION '[EventArchiveQueuedMailError] % mails of event %''s bookings are still waiting to be sent', n, p_event_id;
    END IF;
    SELECT count(*) INTO n FROM enqueued_request er JOIN ar_docs t ON t.id = er.document_id WHERE er.execution_date IS NULL;
    IF n > 0 THEN
        RAISE EXCEPTION '[EventArchiveQueuedPayloadsError] % un-executed KBS2 requests still hold bookings of event %', n, p_event_id;
    END IF;

    -- ================= WHAT GOES WITH THEM =================
    -- Conversations of the set's bookings — and, for a whole event, the event's own
    CREATE TEMP TABLE ar_convs (id integer PRIMARY KEY);
    INSERT INTO ar_convs
    SELECT cv.id FROM conversation cv
     WHERE EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = cv.document_id)
        OR (p_departed_before IS NULL AND cv.document_id IS NULL AND cv.event_id = p_event_id);

    -- One-off people: used by nothing but bookings of the set (or archived ones). Accounts are never touched.
    CREATE TEMP TABLE ar_persons (id integer PRIMARY KEY);
    INSERT INTO ar_persons
    SELECT DISTINCT p.id FROM ar_docs t JOIN document d ON d.id = t.id JOIN person p ON p.id = d.person_id
     WHERE p.frontend_account_id IS NULL AND p.account_person_id IS NULL AND p.anonymised_date IS NULL;
    -- Still used by a live booking outside the set (as booker, payer or carer): kept
    DELETE FROM ar_persons ap USING (
        SELECT unnest(ARRAY[o.person_id, o.payer_id, o.person_carer1_id, o.person_carer2_id]) AS pid
          FROM document o
         WHERE o.archived_date IS NULL AND NOT EXISTS (SELECT 1 FROM ar_docs x WHERE x.id = o.id)
           AND (o.person_id IS NOT NULL OR o.payer_id IS NOT NULL OR o.person_carer1_id IS NOT NULL OR o.person_carer2_id IS NOT NULL)) used
     WHERE used.pid = ap.id;
    -- Anyone with a role, a grant, an account link, a teacher profile, an application, an invitation, a subscription...
    DELETE FROM ar_persons ap
     WHERE EXISTS (SELECT 1 FROM person q WHERE q.account_person_id = ap.id OR q.carer1_id = ap.id OR q.carer2_id = ap.id)
        OR EXISTS (SELECT 1 FROM authorization_management m WHERE m.user_id = ap.id OR m.manager_id = ap.id)
        OR EXISTS (SELECT 1 FROM authorization_super_admin s WHERE s.super_admin_id = ap.id)
        OR EXISTS (SELECT 1 FROM authorization_organization_admin a WHERE a.admin_id = ap.id)
        OR EXISTS (SELECT 1 FROM authorization_organization_user_access u WHERE u.user_id = ap.id)
        OR EXISTS (SELECT 1 FROM role_attribution r WHERE r.person_id = ap.id)
        OR EXISTS (SELECT 1 FROM teacher te WHERE te.person_id = ap.id)
        OR EXISTS (SELECT 1 FROM volunteering_application va WHERE va.person_id = ap.id)
        OR EXISTS (SELECT 1 FROM invitation i WHERE i.invitee_id = ap.id OR i.inviter_id = ap.id OR i.created_alias_person_id = ap.id)
        OR EXISTS (SELECT 1 FROM driver dr WHERE dr.person_id = ap.id)
        OR EXISTS (SELECT 1 FROM "session_user" su WHERE su.user_id = ap.id)
        OR EXISTS (SELECT 1 FROM subscription sb WHERE sb.person_id = ap.id)
        OR EXISTS (SELECT 1 FROM support_report sr WHERE sr.person_id = ap.id)
        OR EXISTS (SELECT 1 FROM auth_session au WHERE au.person_id = ap.id)
        OR EXISTS (SELECT 1 FROM list l WHERE l.created_by_id = ap.id)
        OR EXISTS (SELECT 1 FROM pass_template pt WHERE pt.created_by = ap.id)
        OR EXISTS (SELECT 1 FROM money_transfer mt WHERE mt.verifier_person_id = ap.id)
        OR EXISTS (SELECT 1 FROM list_item li WHERE (li.person_id = ap.id OR li.payer_id = ap.id)
                     AND (li.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs x WHERE x.id = li.document_id)))
        -- Rows outside the set that would still name the person beside its pseudonym: the person is then kept as it is
        OR EXISTS (SELECT 1 FROM recipient r JOIN mail m ON m.id = r.mail_id WHERE r.person_id = ap.id
                     AND (m.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs x WHERE x.id = m.document_id)))
        OR EXISTS (SELECT 1 FROM push_subscription_recipient psr WHERE psr.person_id = ap.id
                     AND (psr.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs x WHERE x.id = psr.document_id)))
        OR EXISTS (SELECT 1 FROM history h WHERE h.user_person_id = ap.id
                     AND (h.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs x WHERE x.id = h.document_id)))
        OR EXISTS (SELECT 1 FROM chat_message cm WHERE cm.person_id = ap.id
                     AND NOT EXISTS (SELECT 1 FROM ar_convs c WHERE c.id = cm.conversation_id))
        OR EXISTS (SELECT 1 FROM chat_message_reaction cr JOIN chat_message cm ON cm.id = cr.message_id WHERE cr.person_id = ap.id
                     AND NOT EXISTS (SELECT 1 FROM ar_convs c WHERE c.id = cm.conversation_id))
        OR EXISTS (SELECT 1 FROM conversation_participant cp WHERE cp.person_id = ap.id
                     AND NOT EXISTS (SELECT 1 FROM ar_convs c WHERE c.id = cp.conversation_id))
        OR EXISTS (SELECT 1 FROM conversation cv WHERE (cv.assignee_id = ap.id OR cv.viewer_person_id = ap.id)
                     AND NOT EXISTS (SELECT 1 FROM ar_convs c WHERE c.id = cv.id))
        OR EXISTS (SELECT 1 FROM chat_presence pr WHERE pr.person_id = ap.id)
        OR EXISTS (SELECT 1 FROM activity_state st WHERE st.owner_id = ap.id)
        OR EXISTS (SELECT 1 FROM error er WHERE er.user_person_id = ap.id);
    ANALYZE ar_persons;

    -- Carts holding only bookings of the set (or archived ones), not forwarded to: new key, no magic link
    CREATE TEMP TABLE ar_carts AS
    SELECT DISTINCT c.id, c.magic_link_id FROM ar_docs t JOIN document d ON d.id = t.id JOIN cart c ON c.id = d.cart_id
     WHERE NOT EXISTS (SELECT 1 FROM document o WHERE o.cart_id = c.id AND o.archived_date IS NULL
                         AND NOT EXISTS (SELECT 1 FROM ar_docs x WHERE x.id = o.id))
       AND NOT EXISTS (SELECT 1 FROM cart f WHERE f.forward_to_cart_id = c.id);
    ALTER TABLE ar_carts ADD PRIMARY KEY (id);
    CREATE TEMP TABLE ar_links AS
    SELECT DISTINCT ac.magic_link_id AS id FROM ar_carts ac
     WHERE ac.magic_link_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM cart o WHERE o.magic_link_id = ac.magic_link_id
                         AND NOT EXISTS (SELECT 1 FROM ar_carts x WHERE x.id = o.id));

    -- Payments split over several bookings: the parent row has no booking and holds the gateway's reply. It is
    -- cleared when every booking it paid for is in the set or already archived.
    CREATE TEMP TABLE ar_parents AS
    SELECT DISTINCT p.id FROM money_transfer c JOIN ar_docs t ON t.id = c.document_id JOIN money_transfer p ON p.id = c.parent_id
     WHERE p.document_id IS NULL
       AND NOT EXISTS (SELECT 1 FROM money_transfer o JOIN document od ON od.id = o.document_id
                        WHERE o.parent_id = p.id AND od.archived_date IS NULL
                          AND NOT EXISTS (SELECT 1 FROM ar_docs x WHERE x.id = od.id));

    CREATE TEMP TABLE ar_subs AS
    SELECT DISTINCT psr.subscription_id AS id FROM push_subscription_recipient psr JOIN ar_docs t ON t.id = psr.document_id;

    -- ================= THE GRID (before any write) =================
    RAISE NOTICE 'archive_event_bookings: db=%, event % (%), % bookings, % in the set', current_database(), ev.id, ev.name,
                 v_bookings, (SELECT count(*) FROM ar_docs);
    RETURN QUERY VALUES
        ('where', 'database', current_database()::text),
        ('where', 'server', coalesce(inet_server_addr()::text, 'local')),
        ('where', 'run_at', now()::text),
        ('where', 'tag', v_tag::text),
        ('event', 'id', ev.id::text),
        ('event', 'name', ev.name::text),
        ('event', 'start_date', ev.start_date::text),
        ('event', 'end_date', ev.end_date::text),
        ('event', 'state', coalesce(ev.state, '')),
        ('event', 'bookings', v_bookings::text);
    IF p_departed_before IS NOT NULL THEN
        RETURN QUERY VALUES ('scope', 'departed_before', to_char(p_departed_before, 'YYYY-MM-DD'));
    END IF;
    RETURN QUERY
    SELECT 'preflight', x.metric, x.value::text FROM (VALUES
        ('candidates',             (SELECT count(*) FROM ar_candidates)),
        ('bookings_to_anonymise',  (SELECT count(*) FROM ar_docs)),
        ('left_balance',           (SELECT count(*) FROM ar_left WHERE kind = 'balance')),
        ('left_running',           (SELECT count(*) FROM ar_left WHERE kind = 'active')),
        ('left_application',       (SELECT count(*) FROM ar_left WHERE kind = 'application')),
        ('already_archived',       (CASE WHEN p_departed_before IS NULL
                                         THEN (SELECT count(*) FROM document d WHERE d.event_id = p_event_id AND d.archived_date IS NOT NULL)
                                         ELSE 0 END)),
        ('people_to_anonymise',    (SELECT count(*) FROM ar_persons)),
        ('mails_to_delete',        (SELECT count(*) FROM mail m JOIN ar_docs t ON t.id = m.document_id)),
        ('history_to_delete',      (SELECT count(*) FROM history h JOIN ar_docs t ON t.id = h.document_id)),
        ('conversations_to_delete', (SELECT count(*) FROM ar_convs)),
        ('list_items_to_delete',   (SELECT count(*) FROM list_item li JOIN ar_docs t ON t.id = li.document_id)),
        ('push_to_delete',         (SELECT count(*) FROM push_subscription_recipient psr JOIN ar_docs t ON t.id = psr.document_id)),
        ('carts_to_rekey',         (SELECT count(*) FROM ar_carts)),
        ('magic_links_to_delete',  (SELECT count(*) FROM ar_links))) AS x(metric, value);

    -- ================= OUTSIDE THE SET, before =================
    CREATE TEMP VIEW ar_outside_now AS
    SELECT
      (SELECT count(*) FROM document d WHERE d.archived_date IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = d.id)) AS archived,
      (SELECT count(*) FROM document d WHERE d.person_first_name = 'Anonymised' AND NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = d.id)) AS pseudonymised,
      (SELECT count(*) FROM document d WHERE d.person_email IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = d.id)) AS with_address,
      (SELECT count(*) FROM person p WHERE p.anonymised_date IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ar_persons a WHERE a.id = p.id)) AS people_anonymised,
      (SELECT count(*) FROM person p WHERE p.email IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ar_persons a WHERE a.id = p.id)) AS people_with_address,
      (SELECT count(*) FROM mail m WHERE m.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = m.document_id)) AS mails,
      (SELECT count(*) FROM recipient r JOIN mail m ON m.id = r.mail_id
        WHERE m.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = m.document_id)) AS recipients,
      (SELECT count(*) FROM history h WHERE h.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = h.document_id)) AS history,
      (SELECT count(*) FROM conversation cv WHERE NOT EXISTS (SELECT 1 FROM ar_convs c WHERE c.id = cv.id)) AS conversations,
      (SELECT count(*) FROM chat_message cm WHERE NOT EXISTS (SELECT 1 FROM ar_convs c WHERE c.id = cm.conversation_id)) AS messages,
      (SELECT count(*) FROM list_item li WHERE li.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = li.document_id)) AS list_items,
      (SELECT count(*) FROM push_subscription_recipient psr
        WHERE psr.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = psr.document_id)) AS push,
      (SELECT count(*) FROM magic_link ml WHERE NOT EXISTS (SELECT 1 FROM ar_links l WHERE l.id = ml.id)) AS magic_links,
      (SELECT coalesce(md5(string_agg(c.id || ':' || c.uuid || ':' || coalesce(c.magic_link_id, 0), ',' ORDER BY c.id)), 'none')
         FROM cart c WHERE NOT EXISTS (SELECT 1 FROM ar_carts a WHERE a.id = c.id)) AS carts,
      (SELECT count(*) FROM document_line dl
        WHERE NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = dl.document_id)
          AND coalesce(dl.share_mate_owner_name, dl.share_owner_mate1_name, dl.share_owner_mate2_name, dl.share_owner_mate3_name,
                       dl.share_owner_mate4_name, dl.share_owner_mate5_name, dl.share_owner_mate6_name, dl.share_owner_mate7_name) IS NOT NULL) AS mates_named,
      (SELECT count(*) FROM money_transfer mt
        WHERE (mt.document_id IS NULL OR NOT EXISTS (SELECT 1 FROM ar_docs t WHERE t.id = mt.document_id))
          AND NOT EXISTS (SELECT 1 FROM ar_parents ap WHERE ap.id = mt.id)
          AND (mt.gateway_response IS NOT NULL OR mt.gateway_generate_payment_url_response IS NOT NULL)) AS gateway_replies;
    CREATE TEMP TABLE ar_before AS SELECT * FROM ar_outside_now;

    -- ================= THE WRITES — every one keyed by the ids of the set =================
    -- 0. One-off people — FIRST. A trigger on person (on_details_change_update_upcoming_bookings) copies a person's
    --    details onto their bookings of events not yet ended, which a continuous event's bookings are: done after the
    --    bookings, it would write '#P<person>' over '#B<booking>'. Done first, it copies the anonymised person, and the
    --    booking step below writes last (V0153 orders its steps the same way, for the same reason).
    UPDATE person p
       SET first_name = 'Anonymised', last_name = '#P' || p.id, name = NULL, lay_name = NULL, email = NULL, phone = NULL,
           street = NULL, post_code = NULL, passport = NULL, carer1_name = NULL, carer2_name = NULL, birthdate = NULL,
           -- what the booking loses, the person loses: town, region, coordinates, nationality (country kept, as on the booking)
           nationality = NULL, city_name = NULL, city_timezone = NULL, city_geonameid = NULL, city_latitude = NULL,
           city_longitude = NULL, latitude = NULL, longitude = NULL, admin1_name = NULL, admin2_name = NULL,
           carer1_id = NULL, carer2_id = NULL, removed = true, emailing_list = false,
           anonymised_date = now()
      FROM ar_persons a WHERE a.id = p.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person', n::text);

    -- 1. The bookings' identity snapshot (V0163's columns), and the stamp
    UPDATE document d
       SET person_first_name = 'Anonymised', person_last_name = '#B' || d.id, person_name = NULL, person_lay_name = NULL,
           person_email = NULL, person_phone = NULL, person_street = NULL, person_post_code = NULL, person_passport = NULL,
           person_nationality = NULL, person_city_name = NULL, person_city_timezone = NULL, person_city_geonameid = NULL,
           person_city_latitude = NULL, person_city_longitude = NULL, person_latitude = NULL, person_longitude = NULL,
           person_admin1_code = NULL, person_admin1_name = NULL, person_admin1_geonameid = NULL,
           person_admin2_code = NULL, person_admin2_name = NULL, person_admin2_geonameid = NULL, inet_ip = NULL,
           inet_geo_latitude = NULL, inet_geo_longitude = NULL,
           person_carer1_name = NULL, person_carer2_name = NULL, person_carer1_id = NULL, person_carer2_id = NULL,
           person_carer1_document_id = NULL, person_carer2_document_id = NULL,
           archived_date = current_date
      FROM ar_docs t WHERE t.id = d.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document', n::text);

    -- 2. Room-mates' names typed on the set's lines (names only: the sharing triggers watch other columns)
    UPDATE document_line dl
       SET share_mate_owner_name = NULL, share_owner_mate1_name = NULL, share_owner_mate2_name = NULL, share_owner_mate3_name = NULL,
           share_owner_mate4_name = NULL, share_owner_mate5_name = NULL, share_owner_mate6_name = NULL, share_owner_mate7_name = NULL
      FROM ar_docs t
     WHERE t.id = dl.document_id
       AND coalesce(dl.share_mate_owner_name, dl.share_owner_mate1_name, dl.share_owner_mate2_name, dl.share_owner_mate3_name,
                    dl.share_owner_mate4_name, dl.share_owner_mate5_name, dl.share_owner_mate6_name, dl.share_owner_mate7_name) IS NOT NULL;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line', n::text);

    -- 3. Payments: gateway replies (they may hold the payer's name); amounts and references kept
    UPDATE money_transfer mt SET gateway_response = NULL, gateway_generate_payment_url_response = NULL
      FROM ar_docs t
     WHERE t.id = mt.document_id AND (mt.gateway_response IS NOT NULL OR mt.gateway_generate_payment_url_response IS NOT NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'money_transfer', n::text);
    UPDATE money_transfer mt SET gateway_response = NULL, gateway_generate_payment_url_response = NULL
      FROM ar_parents ap
     WHERE ap.id = mt.id AND (mt.gateway_response IS NOT NULL OR mt.gateway_generate_payment_url_response IS NOT NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'money_transfer_parents', n::text);

    -- 4. Executed KBS2 payloads
    UPDATE enqueued_request er
       SET request_string = v_tag, reply_string = CASE WHEN er.reply_string IS NULL THEN NULL ELSE v_tag END
      FROM ar_docs t
     WHERE t.id = er.document_id AND er.execution_date IS NOT NULL AND er.request_string NOT LIKE v_tag_prefix || '%';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'enqueued_request', n::text);

    -- 5. Mails (letters included); their recipients go with them (ON DELETE CASCADE)
    DELETE FROM mail m USING ar_docs t WHERE t.id = m.document_id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'mail', n::text);

    -- 6. History: deleted, then one marker per booking
    DELETE FROM history h USING ar_docs t WHERE t.id = h.document_id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'history', n::text);
    INSERT INTO history (document_id, comment, username) SELECT t.id, v_marker, 'gdpr-retention' FROM ar_docs t;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('inserted', 'history_markers', n::text);

    -- 7. Conversations, with their reactions, messages and participants
    DELETE FROM chat_message_reaction r USING chat_message cm, ar_convs c WHERE r.message_id = cm.id AND cm.conversation_id = c.id;
    DELETE FROM chat_message cm USING ar_convs c WHERE cm.conversation_id = c.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'chat_message', n::text);
    DELETE FROM conversation_participant cp USING ar_convs c WHERE cp.conversation_id = c.id;
    DELETE FROM conversation cv USING ar_convs c WHERE cv.id = c.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'conversation', n::text);

    -- 8. List items of the set's bookings
    DELETE FROM list_item li USING ar_docs t WHERE t.id = li.document_id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'list_item', n::text);

    -- 9. Push registrations made for the set's bookings, and subscriptions left with none
    DELETE FROM push_subscription_recipient psr USING ar_docs t WHERE t.id = psr.document_id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'push_subscription_recipient', n::text);
    DELETE FROM push_subscription ps USING ar_subs s
     WHERE ps.id = s.id AND NOT EXISTS (SELECT 1 FROM push_subscription_recipient o WHERE o.subscription_id = ps.id);

    -- 10. Carts holding only the set's bookings: a new key, no magic link; the links themselves deleted
    UPDATE cart c SET uuid = gen_random_uuid()::text, magic_link_id = NULL FROM ar_carts a WHERE a.id = c.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'cart', n::text);
    DELETE FROM magic_link ml USING ar_links l WHERE l.id = ml.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'magic_link', n::text);

    -- ================= PROOFS =================
    SELECT count(*) INTO n FROM document d JOIN ar_docs t ON t.id = d.id
     WHERE d.person_first_name IS DISTINCT FROM 'Anonymised' OR d.person_last_name IS DISTINCT FROM '#B' || d.id
        OR d.person_email IS NOT NULL OR d.person_phone IS NOT NULL OR d.person_street IS NOT NULL OR d.person_post_code IS NOT NULL
        OR d.person_passport IS NOT NULL OR d.person_lay_name IS NOT NULL OR d.person_carer1_name IS NOT NULL
        OR d.person_carer2_name IS NOT NULL OR d.inet_ip IS NOT NULL OR d.inet_geo_latitude IS NOT NULL
        OR d.inet_geo_longitude IS NOT NULL OR d.person_city_name IS NOT NULL OR d.archived_date IS DISTINCT FROM current_date;
    IF n > 0 THEN
        RAISE EXCEPTION 'archive_event_bookings: % bookings of the set still name someone [db=%, event=%] — ROLLBACK', n, current_database(), p_event_id;
    END IF;
    SELECT (SELECT count(*) FROM mail m JOIN ar_docs t ON t.id = m.document_id)
         + (SELECT count(*) FROM history h JOIN ar_docs t ON t.id = h.document_id WHERE h.username IS DISTINCT FROM 'gdpr-retention' OR h.comment IS DISTINCT FROM v_marker)
         + (SELECT count(*) FROM conversation cv JOIN ar_convs c ON c.id = cv.id)
         + (SELECT count(*) FROM list_item li JOIN ar_docs t ON t.id = li.document_id)
         + (SELECT count(*) FROM push_subscription_recipient psr JOIN ar_docs t ON t.id = psr.document_id)
      INTO n;
    IF n > 0 THEN
        RAISE EXCEPTION 'archive_event_bookings: % rows of the set survived their deletion [db=%, event=%] — ROLLBACK', n, current_database(), p_event_id;
    END IF;
    SELECT count(*) INTO n FROM person p JOIN ar_persons a ON a.id = p.id
     WHERE p.email IS NOT NULL OR p.phone IS NOT NULL OR p.street IS NOT NULL OR p.last_name IS DISTINCT FROM '#P' || p.id
        OR p.passport IS NOT NULL OR p.birthdate IS NOT NULL OR p.nationality IS NOT NULL OR p.city_name IS NOT NULL
        OR p.admin1_name IS NOT NULL OR p.admin2_name IS NOT NULL OR p.post_code IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'archive_event_bookings: % people of the set still identifiable [db=%, event=%] — ROLLBACK', n, current_database(), p_event_id;
    END IF;

    CREATE TEMP TABLE ar_after AS SELECT * FROM ar_outside_now;
    SELECT b::text INTO v_before FROM ar_before b;
    SELECT a::text INTO v_after FROM ar_after a;
    IF v_before IS DISTINCT FROM v_after THEN
        RAISE EXCEPTION 'archive_event_bookings: something outside the set changed — the set predicate is broken (before=%, after=%): ROLLBACK', v_before, v_after;
    END IF;

    RETURN QUERY
    SELECT 'verify', x.metric, x.value::text FROM (VALUES
        ('bookings_archived', (SELECT count(*) FROM document d JOIN ar_docs t ON t.id = d.id WHERE d.archived_date = current_date)),
        ('identity_left',     0::bigint),
        ('outside_unchanged', 1::bigint)) AS x(metric, value);
    RAISE NOTICE 'archive OK — event % (%): registrations anonymised, everything outside the set unchanged. NOTHING IS COMMITTED YET.',
                 p_event_id, coalesce('departed before ' || to_char(p_departed_before, 'YYYY-MM-DD'), 'every booking');
    RETURN QUERY VALUES ('status', 'result', 'archive OK — uncommitted; COMMIT to apply, ROLLBACK for a free dry run');
END
$archive$;

REVOKE ALL ON FUNCTION public.archive_event_bookings(integer, date) FROM PUBLIC;

COMMENT ON FUNCTION public.archive_event_bookings(integer, date) IS
'V0170, PROVISIONAL rules pending the DPO: retention stage 2 — anonymises the registrations of a past event (or a continuous event''s stays departed before the date): identity snapshot to Anonymised #B<id>, mails/history/conversations/list items/booking push registrations deleted, gateway replies cleared, exclusive carts re-keyed, one-off people anonymised; money, lines and statistics kept; bookings with a balance or still running left out. Requires an explicit REPEATABLE READ transaction and SET LOCAL kbs.erase_confirm = ''ARCHIVE EVENT <id>'' (or ''ARCHIVE DEPARTED <id> <YYYY-MM-DD>''). Never commits. Aggregate repo: docs/design/event-archive-anonymisation.md.';

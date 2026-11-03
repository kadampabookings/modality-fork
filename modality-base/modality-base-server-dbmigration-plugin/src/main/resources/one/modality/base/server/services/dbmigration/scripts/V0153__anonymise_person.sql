-- V0153: anonymise_person() — the GDPR right to erasure (Art. 17) for ONE customer, and its run ledger.
--
-- WHY. A customer can ask for their account and data to be deleted. Deleting the rows is not possible and not
-- required: the bookings and payments are accounting records the law makes us keep (Art. 17(3)(b); 6 years in the
-- UK, 10 in France), and deleting person, document, history or mail rows breaks the booking replay and the foreign
-- keys (see the aggregate repo's docs/operations/gdpr-past-event-erasure-reference.md, "why replace-not-delete").
-- Irreversible anonymisation is erasure in law (Recital 26): data that no longer identifies anybody is no longer
-- personal data. So this REMOVES EVERYTHING THAT IDENTIFIES THE PERSON AND KEEPS THE FINANCIAL SKELETON — amounts,
-- dates, items, the payment gateway's transaction_ref. Full rationale and the per-request checklist outside the
-- database: the aggregate repo's docs/operations/gdpr-person-erasure-reference.md.
--
-- WHO IS "THE PERSON". The human, not one row. The person row, its LINKED COPIES (rows on other accounts whose
-- account_person_id names it: the same human as a member of somebody else's account), and the bookings that carry
-- them: their own (document.person_id), bookings made WITHOUT an account that match them (below), bookings they
-- paid for (payer_id), cared for (person_carerN_id, or their name typed as carer) or shared a room in
-- (document_line share names). Also their REMOVED rows: a soft-deleted duplicate with one of their addresses (their
-- own, or one only their bookings show) and the name of one of their rows is the same human, and is anonymised with them; a removed row with one of their
-- addresses under another name only loses the address.
-- "Their names" are those of their person rows, and the names on their own bookings that nobody else bears (an own
-- booking often carries a family member's name: that member is not the requester). A booking-only name finds
-- candidates and namesakes; carers, room lines and history are matched on the names of their rows alone.
--
-- THE OTHER MEMBERS OF THEIR ACCOUNT (when they own it) are other people, and the account they hang off is deleted:
--   * a member linked to a person with an account of their own (account_person_id set) is that person's copy: never
--     anonymised here — the row is marked removed and leaves the deleted account;
--   * a member WITHOUT an account of their own (a child, a partner who never signed up) is anonymised with them only
--     when staff tick it (p_member_ids) — the requester speaks for themselves and their minor children, not for
--     another adult. Unticked, the member stays as a customer record of their own, without the account (with an
--     email of their own they can sign up later and claim their bookings). A member often has the owner's email
--     typed as their own (159 rows on kbs_local, November 2026): that address is the requester's, so it is cleared
--     from the member's row, and from their bookings like any other booking carrying it — not a duplicate to merge.
-- Members are returned in the grid (step 'member': ids, flags and counts only); a ticked id that is not an unlinked member
-- aborts the run.
--
-- BOOKINGS MADE WITHOUT AN ACCOUNT (person_id IS NULL) are matched on the booking's snapshot, because nothing else
-- links them — and a name or an address alone does not prove it is the same human:
--   * same email AND the name of one of their person rows (public.abc_names, so case, accents and word order do
--     not matter): automatic, in full; same email and a name known only from their bookings: a CANDIDATE, in full;
--   * same email, other name (a parent booking for a child): automatic, CONTACT details only — the address is
--     theirs, the name and the rest belong to the other person;
--   * same phone only: a CANDIDATE whose contact details only are removed if staff tick it (a phone can be shared);
--     a phone on more than 5 such bookings is a shared number (a centre's, a family's) and is not matched on at all;
--   * same name, other or no email: a CANDIDATE removed in full if staff tick it.
-- Their address also goes from every other booking that carries it (a family member's made under their address).
-- THEIR NAME TYPED AS CARER on somebody else's booking is cleared without a tick when something else ties the booking
-- to them (theirs, a member's, one they paid for, one with their address) or nobody else bears that name; otherwise
-- it is a CANDIDATE (action 'carer': ticked, that carer slot alone is cleared) — a namesake may be the carer.
-- OTHER CUSTOMERS WITH ONE OF THEIR NAMES (step 'namesake') are listed, never touched: a name alone does not make
-- them the same human. Staff merge one first, or anonymise it on its own.
-- Each candidate and member row also says what would stop the run if it were ticked ('blockedBy': booking refs not
-- over, or owing money — anonymise_person_blocking(), the very rules of the refusals), so the screen can say so
-- before the operator ticks it.
-- Candidates are returned in the grid (step 'candidate'); p_candidate_document_ids says which were ticked. An id
-- that is not one of this person's candidates aborts the run: the caller never chooses an arbitrary booking — unless
-- p_lenient (a PREVIEW, re-run as staff tick): it is then dropped. The grid's 'ticks' rows say which ticks the run
-- ran with; the server makes a commit send exactly those of the preview it names.
--
-- WHAT STOPS IT (each raises '[Key] text', the key mapped by the back office; nothing is written):
--   PersonAnonymiseNotFoundError        no such person
--   PersonAnonymiseAlreadyDoneError     anonymised before (person.anonymised_date)
--   PersonAnonymiseLinkedCopyError      the row is itself a linked copy: anonymise the person it is linked to
--   PersonAnonymiseStaffError           holds a grant, a role, is a teacher or owns a back-office account
--   PersonAnonymiseMemberError          a ticked member is not a member of their account without an account of their own
--   PersonAnonymiseDuplicatesError      another non-removed person row (not a member of their own account), an
--                                       account, or another person's volunteering application uses the same email:
--                                       merge or correct first, or the old address would still find that duplicate
--   PersonAnonymiseActiveBookingsError  a booking (own, auto-matched, ticked in full, or paid for) is not cancelled
--                                       and not over
--   PersonAnonymiseBalanceError         one of those has a balance (price_net - price_deposit <> 0), owed either way
--   PersonAnonymiseQueuedMailError      a mail to or about them is still waiting to be sent (last 30 days)
--   PersonAnonymiseQueuedPayloadsError  an un-executed KBS2 request still holds one of their bookings
--   PersonAnonymiseCandidateError       a ticked booking is not one of their candidates
--   PersonAnonymiseUnknownReferenceError a foreign key to person / frontend_account this version does not know about
--
-- WHAT IT DOES. The tag is '[personal data removed (GDPR) on YYYY-MM-DD]'; a NOT NULL name becomes 'Anonymised' /
-- '#<person id>' and a NOT NULL address 'anonymised-<person id>@invalid'. In this order:
--   person (+ linked copies,  names, email, phone, address, geocode, passport, birthdate, carers cleared; removed,
--    ticked members)          emailing_list off, anonymised_date set
--   auth_session              revoked (the person row is never deleted, so its ON DELETE CASCADE never fires)
--   frontend_account          their own account (they own it) DELETED: passkeys, TOTP, resets and sign-in
--                             restrictions go with it by cascade; its other members leave it (ticked ones were
--                             anonymised above, linked copies are marked removed, the others stay as records of
--                             their own); rows of the scripts' backups holding their login or ids
--                             (frontend_account_dissolved_backup, member_email_nullify_backup, spare_owner_row_backup)
--                             deleted; person_account_move usernames and notes scrubbed
--   magic_link                links of their addresses deleted (cart.magic_link_id first: that FK has no action)
--   document                  'full': person_* snapshot, inet_ip and the IP/address geocodes cleared, comment /
--                             request / special_needs tagged; 'contact': email, phone, street, post code cleared;
--                             'carer': the carer slot naming them cleared
--   cart                      uuid rotated on 'full' bookings, so links in old emails stop working — unless the cart
--                             also holds someone else's booking (a family's), whose links must keep working
--   document_line             comments of 'full' bookings tagged; their name as room owner / mate on others' lines
--                             cleared
--   history                   request tagged; inside changes (JSON, read by the replay) their AddDocumentEvent
--                             name and email, AddRequestEvent request, and their name as carer / room owner / mate
--                             redacted IN PLACE (the array stays valid); a comment quoting a tagged mail tagged;
--                             their name taken out of a comment's 'Room owner / Room mate(s) / Carer(s)' summary;
--                             the user a guest acted as ('guest:<their address>', or their address) replaced — on
--                             their bookings and the other bookings of the same carts;
--                             one marker per 'full'/'contact' booking: 'Personal data removed (GDPR erasure request)'
--   mail / recipient          every mail of a 'full'/'contact' booking or addressed to them: subject and content
--                             tagged (LETTERS INCLUDED — a rendered letter freezes the name, address and magic link,
--                             unlike V0077 which keeps letters); their recipient and sender name/address replaced
--   money_transfer            comment tagged, gateway_response / gateway_generate_payment_url_response cleared;
--                             amount, date, method, transaction_ref kept
--   chat                      their messages, and every text message and the title of their conversations, tagged
--   others                    invitation aliases naming them, list_item comments, error rows they caused, executed
--                             KBS2 payloads of their bookings; push subscriptions, subscriptions and volunteering
--                             applications (theirs, or anonymous ones under their address) DELETED (the application's photo is a file outside the database: the
--                             grid counts them, staff delete the file — see the reference doc)
--   person_erasure_run        their runs keep counts, keys and dates only: ticks, refusal texts and any listed
--                             candidates / members / namesakes cleared; other runs' refusal texts naming them, and
--                             their ticks of the bookings or members anonymised here, cleared
-- Ids are kept everywhere: an id points at a row that no longer identifies anybody.
--
-- CHECKS before it returns (any failure raises: the caller rolls back): every foreign key into person and
-- frontend_account is one this version handles; afterwards none of their addresses is left in the rows it touched,
-- no 'full' booking or AddDocumentEvent keeps a name, the mail table gained or lost no row and the unsent queue did
-- not change (no trigger sent anything).
--
-- TRIGGERS. None is disabled (DISABLE TRIGGER takes ACCESS EXCLUSIVE). Each one this fires is wanted or harmless:
-- set_abc_names / set_person_abc_names recompute from the pseudonym; record_changes_for_notification writes id-only
-- sys_log rows; person_details_edited stamps details_edited_date; on_details_change_update_upcoming_bookings copies
-- the (already anonymised) person onto upcoming bookings, which the document step anonymises again after it;
-- on_email_change_update_frontend_username does nothing (the email is set NULL); audit_account_move records the
-- account link going (its username is scrubbed by the next step). No history row is inserted with username 'online',
-- so no mail is generated; no mail row is inserted, transmitted or read.
--
-- INTERLOCKS, as V0077: SET LOCAL kbs.erase_confirm = 'ANONYMISE PERSON <id>' with the same id; a REPEATABLE READ
-- transaction; the call a LATER statement of it (a single autocommit statement is refused); EXECUTE revoked from
-- PUBLIC. It never commits: the caller reads the grid, then COMMIT or ROLLBACK — a rollback is a free dry run. The
-- back office runs it through modality-event-server-lifecycle-plugin (PersonAnonymisationRunner), super
-- administrators only, recording each run in person_erasure_run below. Set kbs.audit_person_id to the staff member's
-- person id first: the audit triggers and the history markers name them.
--
-- SHIPPED = FROZEN. Never edit once applied; a change ships as a new version, which must DROP FUNCTION first if
-- the signature changes and repeat the REVOKEs. The known-references list below must follow every migration that
-- adds a foreign key to person or frontend_account — or this function refuses to run, which is the point.
--
-- ANONYMISER. person.anonymised_date and person_erasure_run are classified in the aggregate repo's
-- scripts/gdpr-anonymise/10-anon-helpers.sql.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

-- ===== person.anonymised_date ===================================================================================

ALTER TABLE public.person ADD COLUMN IF NOT EXISTS anonymised_date timestamp with time zone;

COMMENT ON COLUMN public.person.anonymised_date IS
    'When anonymise_person() (V0153) removed this person''s identifying data at their request (GDPR Art. 17). NULL = never. The row is kept, with removed = true, because bookings and payments still point at it.';

-- ===== person_erasure_run — the ledger, as V0146 event_erasure_run ============================================

CREATE TABLE IF NOT EXISTS public.person_erasure_run (
    id                     serial      PRIMARY KEY,
    -- The person the run acted on. A plain integer, no foreign key, like event_erasure_run.event_id.
    person_id              integer     NOT NULL,
    -- preview = the function run and rolled back; commit = the real anonymisation.
    mode                   text        NOT NULL
        CONSTRAINT person_erasure_run_mode_chk CHECK (mode IN ('preview', 'commit')),
    -- refused = the function's rules stopped it (error_key says which); failed = any other error; aborted = its
    -- server went away mid-run.
    status                 text        NOT NULL
        CONSTRAINT person_erasure_run_status_chk CHECK (status IN ('running', 'succeeded', 'failed', 'aborted', 'refused')),
    -- For a commit: the succeeded preview of the same person it was checked against.
    preview_run_id         integer
        CONSTRAINT person_erasure_run_preview_run_id_fkey REFERENCES public.person_erasure_run (id),
    -- The super administrator who ran it: a person id, never a name; no foreign key.
    requested_by_person_id integer,
    -- The candidate bookings ticked for a commit (document ids).
    candidate_document_ids integer[],
    -- The account members ticked for a commit (person ids).
    member_ids             integer[],
    started_at             timestamptz NOT NULL DEFAULT now(),
    finished_at            timestamptz,
    grid                   jsonb,
    error_key              text,
    error                  text,
    app_name               text,
    CONSTRAINT person_erasure_run_commit_needs_preview_chk CHECK (mode <> 'commit' OR preview_run_id IS NOT NULL),
    CONSTRAINT person_erasure_run_finished_chk CHECK ((status = 'running') = (finished_at IS NULL))
);

-- One run per person at a time, across servers.
CREATE UNIQUE INDEX IF NOT EXISTS person_erasure_run_one_running_idx
    ON public.person_erasure_run (person_id) WHERE status = 'running';

CREATE INDEX IF NOT EXISTS person_erasure_run_person_started_idx
    ON public.person_erasure_run (person_id, started_at DESC);

REVOKE ALL ON TABLE public.person_erasure_run FROM PUBLIC;
REVOKE ALL ON SEQUENCE public.person_erasure_run_id_seq FROM PUBLIC;

COMMENT ON TABLE public.person_erasure_run IS
    'Customer anonymisation runs started from the back office (V0153): one row per anonymise_person() run, preview or commit. Counts, booking ids and event facts, never personal data. Not in the domain model: the anonymisation endpoints of modality-event-server-lifecycle-plugin (super administrators only) are the only way in.';
COMMENT ON COLUMN public.person_erasure_run.person_id IS
    'The person the run acted on. No foreign key: the record outlives whatever happens to the row.';
COMMENT ON COLUMN public.person_erasure_run.grid IS
    'The step/metric/value rows anonymise_person() returned, as the runner keeps them: counts and facts — never who a preview listed (candidate bookings, members, namesakes), a name, an address or free text.';
COMMENT ON COLUMN public.person_erasure_run.candidate_document_ids IS
    'A succeeded preview: the candidate bookings it ran with ticked (a commit of it must send exactly these). Emptied on a refusal or failure, after the preview''s validity, and when anybody they concern is anonymised.';
COMMENT ON COLUMN public.person_erasure_run.member_ids IS
    'A succeeded preview: the account members it ran with ticked. Emptied as candidate_document_ids.';
COMMENT ON COLUMN public.person_erasure_run.error IS
    'SQLSTATE and the database''s primary message. anonymise_person()''s own messages carry ids, refs and counts only.';
COMMENT ON COLUMN public.person_erasure_run.app_name IS
    'The application_name of the run''s connection (kbs-anonymise-<id>). A running row whose connection is gone from pg_stat_activity is marked aborted by the next run.';

-- Ownership, as V0146: hand the table (and its sequence) to whoever owns public.person.
DO $$
DECLARE
    app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        BEGIN
            EXECUTE format('ALTER TABLE public.person_erasure_run OWNER TO %I', app_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0153: could not realign the owner of public.person_erasure_run to % (%)', app_owner, SQLERRM;
        END;
    END IF;
END $$;

-- ===== anonymise_person_changes() — one history.changes value, redacted in place ==============================
--
-- changes is the JSON array of document events the booking replay reads (AST.parseArray), so it is edited, never
-- replaced by prose. p_scope is the booking's scope in the run: 'full', 'contact', or anything else (a booking of
-- somebody else that names them). Whatever the scope, an AddDocumentEvent email that is one of p_addresses goes
-- (somebody else's booking made with their address). A key holding their data is REMOVED (the decoders read a
-- missing key as null);
-- a name inside a names array becomes "" so the array keeps its length. A value that is not a JSON array (KBS2-era)
-- cannot be edited: on a 'full' booking it is set NULL, as V0077 does, elsewhere left alone.

DROP FUNCTION IF EXISTS public.anonymise_person_changes(text, text, text[], text[], text, text, text);
CREATE OR REPLACE FUNCTION public.anonymise_person_changes(p_changes text, p_scope text, p_names text[],
                                                           p_addresses text[], p_first text, p_last text, p_tag text,
                                                           p_cared boolean DEFAULT true)
RETURNS text
LANGUAGE plpgsql
AS $redact$
DECLARE
    v_out jsonb := '[]'::jsonb;
    e     jsonb;
    codec text;
BEGIN
    IF p_changes IS NULL THEN
        RETURN NULL;
    END IF;
    IF NOT pg_input_is_valid(p_changes, 'jsonb') OR jsonb_typeof(p_changes::jsonb) <> 'array' THEN
        RETURN CASE WHEN p_scope = 'full' THEN NULL ELSE p_changes END;
    END IF;
    FOR e IN SELECT a.x FROM jsonb_array_elements(p_changes::jsonb) WITH ORDINALITY AS a(x, o) ORDER BY a.o LOOP
        codec := CASE WHEN jsonb_typeof(e) = 'object' THEN e ->> '$codec' END;
        IF codec = 'AddDocumentEvent' THEN
            IF p_scope = 'full' THEN
                IF e ? 'firstName' THEN e := jsonb_set(e, '{firstName}', to_jsonb(p_first)); END IF;
                IF e ? 'lastName'  THEN e := jsonb_set(e, '{lastName}',  to_jsonb(p_last));  END IF;
            END IF;
            IF p_scope IN ('full', 'contact') OR lower(trim(e ->> 'email')) = ANY (p_addresses) THEN
                e := e - 'email';
            END IF;
        ELSIF codec = 'AddRequestEvent' THEN
            IF p_scope = 'full' AND jsonb_typeof(e -> 'request') = 'string'
               AND e ->> 'request' NOT LIKE '[personal data removed (GDPR)%' THEN
                e := jsonb_set(e, '{request}', to_jsonb(p_tag));
            END IF;
        ELSIF codec = 'EditCarersInfoEvent' THEN
            -- their name as carer only where the booking's carer slot goes too (p_cared): document and history agree
            IF p_scope = 'full' OR (p_cared AND public.abc_names(e ->> 'carer1Name') = ANY (p_names)) THEN e := e - 'carer1Name'; END IF;
            IF p_scope = 'full' OR (p_cared AND public.abc_names(e ->> 'carer2Name') = ANY (p_names)) THEN e := e - 'carer2Name'; END IF;
        ELSIF codec = 'EditShareMateInfoDocumentLineEvent' THEN
            IF p_scope <> 'full' AND public.abc_names(e ->> 'ownerName') = ANY (p_names) THEN
                e := e - 'ownerName';
            END IF;
        ELSIF codec = 'EditShareOwnerInfoDocumentLineEvent' THEN
            IF p_scope <> 'full' AND jsonb_typeof(e -> 'matesNames') = 'array' THEN
                e := jsonb_set(e, '{matesNames}', coalesce((
                    SELECT jsonb_agg(CASE WHEN jsonb_typeof(b.m) = 'string' AND public.abc_names(b.m #>> '{}') = ANY (p_names)
                                          THEN to_jsonb(''::text) ELSE b.m END ORDER BY b.o)
                      FROM jsonb_array_elements(e -> 'matesNames') WITH ORDINALITY AS b(m, o)), '[]'::jsonb));
            END IF;
        END IF;
        v_out := v_out || jsonb_build_array(e);
    END LOOP;
    RETURN v_out::text;
END
$redact$;

REVOKE ALL ON FUNCTION public.anonymise_person_changes(text, text, text[], text[], text, text, text, boolean) FROM PUBLIC;

-- ===== anonymise_person_comment() ===============================================================================

-- A history comment with their name taken out of the booking summary the history helpers write (Java
-- WorkingBookingHistoryHelper, React working-booking-history-helper.ts): sections joined by '  ~  ', among them
-- 'Room owner: <name>', 'Room mate(s): <a>, <b>' and 'Carer(s): <a>, <b>' (a linked carer prefixed with 🔗). A name
-- whose abc_names() is one of p_names is dropped from its list; everything else is kept as it is. abc_names() is
-- plpgsql: it runs only on a comment holding one of those labels.
DROP FUNCTION IF EXISTS public.anonymise_person_comment(text, text[]);
CREATE OR REPLACE FUNCTION public.anonymise_person_comment(p_comment text, p_names text[], p_cared boolean DEFAULT true)
RETURNS text
LANGUAGE plpgsql
AS $comment$
DECLARE
    sec      text;
    lbl      text;
    out_secs text[] := '{}';
    kept     text[];
BEGIN
    IF p_comment IS NULL OR p_comment !~ '(Room owner|Room mate\(s\)|Carer\(s\)): ' OR cardinality(p_names) = 0 THEN
        RETURN p_comment;
    END IF;
    FOREACH sec IN ARRAY string_to_array(p_comment, '  ~  ') LOOP
        lbl := substring(sec FROM '^(Room owner: |Room mate\(s\): |Carer\(s\): )');
        -- the carer section only where the booking's carer slot goes too (p_cared), as in anonymise_person_changes()
        IF lbl IS NOT NULL AND (p_cared OR lbl <> 'Carer(s): ') THEN
            SELECT array_agg(t.n ORDER BY t.o) INTO kept
              FROM unnest(string_to_array(substr(sec, length(lbl) + 1), ', ')) WITH ORDINALITY AS t(n, o)
             WHERE coalesce(public.abc_names(ltrim(t.n, '🔗')) = ANY (p_names), false) IS FALSE;
            sec := lbl || coalesce(array_to_string(kept, ', '), '');
        END IF;
        out_secs := out_secs || sec;
    END LOOP;
    RETURN array_to_string(out_secs, '  ~  ');
END
$comment$;

REVOKE ALL ON FUNCTION public.anonymise_person_comment(text, text[], boolean) FROM PUBLIC;

-- ===== anonymise_person_blocking() ==============================================================================

-- Which of these bookings stop an anonymisation, and why: 'active' = not cancelled and not over (for a continuous
-- event, a line not over); 'balance' = money owed either way (price_net - price_deposit <> 0 — a payment still
-- marked pending does not count: thousands are online attempts abandoned for good, and on a booking with nothing
-- owed either way one that did land would only mean a refund to make). The function's refusals AND the preview's
-- warning on each candidate and member both read it, so a booking the preview lets staff tick is never the one the
-- commit refuses on.
CREATE OR REPLACE FUNCTION public.anonymise_person_blocking(p_document_ids integer[])
RETURNS TABLE (document_id integer, ref integer, kind text)
LANGUAGE sql STABLE
AS $blocking$
    SELECT d.id, d.ref, 'active'
      FROM document d
      LEFT JOIN event e ON e.id = d.event_id
      LEFT JOIN event_type et ON et.id = e.type_id
     WHERE d.id = ANY (p_document_ids) AND NOT d.cancelled
       AND (   (NOT coalesce(et.ongoing, false) AND e.end_date >= current_date)
            OR EXISTS (SELECT 1 FROM document_line dl
                        WHERE dl.document_id = d.id AND NOT dl.cancelled AND dl.end_date >= current_date))
    UNION ALL
    SELECT d.id, d.ref, 'balance'
      FROM document d
     WHERE d.id = ANY (p_document_ids) AND d.price_net - d.price_deposit <> 0
$blocking$;

REVOKE ALL ON FUNCTION public.anonymise_person_blocking(integer[]) FROM PUBLIC;

-- The same, as the preview shows it on a candidate or a member: {"active": [refs], "balance": [refs]}, or NULL
-- when nothing stops it.
CREATE OR REPLACE FUNCTION public.anonymise_person_blockers(p_document_ids integer[])
RETURNS jsonb
LANGUAGE sql STABLE
AS $blockers$
    SELECT jsonb_object_agg(b.kind, b.refs)
      FROM (SELECT x.kind, coalesce(jsonb_agg(DISTINCT x.ref) FILTER (WHERE x.ref IS NOT NULL), '[]'::jsonb) AS refs
              FROM public.anonymise_person_blocking(p_document_ids) x
             GROUP BY x.kind) b
$blockers$;

REVOKE ALL ON FUNCTION public.anonymise_person_blockers(integer[]) FROM PUBLIC;

-- ===== anonymise_person() =======================================================================================

-- The two-argument version of an earlier draft of this migration, where a database has it (local rehearsals only:
-- V0153 had not shipped): a different signature would otherwise stay beside this one as an overload.
DROP FUNCTION IF EXISTS public.anonymise_person(integer, integer[]);
DROP FUNCTION IF EXISTS public.anonymise_person(integer, integer[], integer[]);

CREATE OR REPLACE FUNCTION public.anonymise_person(p_person_id integer,
                                                   p_candidate_document_ids integer[] DEFAULT '{}'::integer[],
                                                   p_member_ids integer[] DEFAULT '{}'::integer[],
                                                   p_lenient boolean DEFAULT false)
RETURNS TABLE(step text, metric text, value text)
LANGUAGE plpgsql
SET timezone TO 'Europe/London'
AS $anon$
DECLARE
    v_tag_prefix constant text := '[personal data removed (GDPR)';
    v_tag         varchar(255);
    v_confirm     text := nullif(current_setting('kbs.erase_confirm', true), '');
    v_first       constant varchar(45) := 'Anonymised';
    v_last        varchar(45);
    v_email       varchar(127);
    v_ticked      integer[] := coalesce(p_candidate_document_ids, '{}'::integer[]);
    v_members     integer[] := coalesce(p_member_ids, '{}'::integer[]);
    v_names       text[];
    v_row_names   text[];
    v_unique_names text[];
    v_family_names text[];
    v_addresses   text[];
    v_removed_same bigint;
    v_shared_phones bigint;
    p             record;
    v_account_id  integer;
    v_text        text;
    v_mail_rows_before   bigint;
    v_mail_queued_before bigint;
    n             bigint;
    bad           text := '';
BEGIN
    v_tag := v_tag_prefix || ' on ' || current_date || ']';

    -- ================= INTERLOCKS =================
    IF p_person_id IS NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseNotFoundError] the person id is NULL';
    END IF;
    IF current_setting('transaction_isolation') NOT IN ('repeatable read', 'serializable') THEN
        RAISE EXCEPTION 'anonymise_person must run in a REPEATABLE READ transaction — start with: BEGIN ISOLATION LEVEL REPEATABLE READ;';
    END IF;
    IF transaction_timestamp() = statement_timestamp() THEN
        RAISE EXCEPTION 'anonymise_person refuses to run as a single autocommit statement — it would commit with no review. BEGIN ISOLATION LEVEL REPEATABLE READ; SET LOCAL kbs.erase_confirm = ...; SELECT * FROM anonymise_person(...); then COMMIT or ROLLBACK, each statement sent separately';
    END IF;
    IF v_confirm IS DISTINCT FROM ('ANONYMISE PERSON ' || p_person_id) THEN
        RAISE EXCEPTION 'anonymise_person: missing statement of intent — run  SET LOCAL kbs.erase_confirm = ''ANONYMISE PERSON %'';  in this transaction first (found: %)',
                        p_person_id, coalesce(v_confirm, '<unset>');
    END IF;

    -- ================= THE PERSON =================
    SELECT * INTO p FROM person WHERE id = p_person_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION '[PersonAnonymiseNotFoundError] person % does not exist', p_person_id;
    END IF;
    IF p.anonymised_date IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseAlreadyDoneError] person % was anonymised on %', p_person_id, p.anonymised_date::date;
    END IF;
    IF p.account_person_id IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseLinkedCopyError] person % is a linked copy of person %: anonymise person % instead',
                        p_person_id, p.account_person_id, p.account_person_id;
    END IF;

    -- Deferred triggers fired at COMMIT read this session temp table; a safety net, as in V0077.
    PERFORM set_transaction_parameters(true);

    v_last  := '#' || p_person_id;
    v_email := 'anonymised-' || p_person_id || '@invalid';
    -- Their own account, only when they own it: a member row sits on somebody else's account.
    v_account_id := CASE WHEN p.owner THEN p.frontend_account_id END;

    -- Every foreign key into person and frontend_account must be one this version handles (or knows to be an id
    -- that may stay). A key added later makes this refuse rather than leave data behind.
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_text
      FROM (SELECT format('%s:%s.%s', rc.relname, c.relname, a.attname) AS k
              FROM pg_constraint con
              JOIN pg_class c  ON c.oid = con.conrelid
              JOIN pg_class rc ON rc.oid = con.confrelid
              JOIN pg_namespace ns ON ns.oid = rc.relnamespace AND ns.nspname = 'public'
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
             WHERE con.contype = 'f' AND rc.relname IN ('person', 'frontend_account')) fk
     WHERE k <> ALL (ARRAY[
        -- handled: rewritten, revoked or deleted below
        'person:document.person_id', 'person:document.payer_id', 'person:document.person_carer1_id',
        'person:document.person_carer2_id', 'person:person.account_person_id', 'person:person.carer1_id',
        'person:person.carer2_id', 'person:auth_session.person_id', 'person:recipient.person_id',
        'person:chat_message.person_id', 'person:conversation.viewer_person_id', 'person:invitation.invitee_id',
        'person:invitation.created_alias_person_id', 'person:list_item.person_id', 'person:error.user_person_id',
        'person:push_subscription_recipient.person_id', 'person:subscription.person_id',
        'person:volunteering_application.person_id',
        -- refusals: a person holding any of these is staff
        'person:authorization_super_admin.super_admin_id', 'person:authorization_organization_admin.admin_id',
        'person:authorization_organization_user_access.user_id', 'person:authorization_management.manager_id',
        'person:authorization_management.user_id', 'person:role_attribution.person_id', 'person:teacher.person_id',
        -- ids only, no text about the person: kept as links to a row that no longer identifies anybody
        'person:history.user_person_id', 'person:money_transfer.verifier_person_id', 'person:invitation.inviter_id',
        'person:list_item.payer_id', 'person:list.created_by_id', 'person:pass_template.created_by',
        'person:conversation.assignee_id', 'person:conversation_participant.person_id',
        'person:chat_message_reaction.person_id', 'person:chat_presence.person_id', 'person:activity_state.owner_id',
        'person:driver.person_id', 'person:support_report.person_id', 'person:session_user.user_id',
        -- the account's own: deleted by cascade, or set null
        'frontend_account:person.frontend_account_id', 'frontend_account:mate_invite_token.creator_account_id',
        'frontend_account:webauthn_credential.frontend_account_id', 'frontend_account:totp_credential.frontend_account_id',
        'frontend_account:totp_backup_code.frontend_account_id', 'frontend_account:second_factor_reset.frontend_account_id',
        'frontend_account:account_sign_in_restriction.frontend_account_id']);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseUnknownReferenceError] foreign keys this version does not handle: % — ship a new version of anonymise_person() first', v_text;
    END IF;

    -- ================= SCOPE =================
    DROP TABLE IF EXISTS ap_members, ap_persons, ap_core_addresses, ap_addresses, ap_names, ap_phones, ap_candidates, ap_docs,
                         ap_email_docs, ap_events, ap_share_lines, ap_hist_docs, ap_hist, ap_recipients, ap_sender_mails, ap_mails, ap_namesakes, ap_carer_docs,
                         ap_member_docs;

    -- The human: the row, and its linked copies on other accounts.
    CREATE TEMP TABLE ap_persons (id integer PRIMARY KEY);
    INSERT INTO ap_persons VALUES (p_person_id);
    INSERT INTO ap_persons SELECT q.id FROM person q WHERE q.account_person_id = p_person_id ON CONFLICT DO NOTHING;

    -- The other members of their own account (see the header): linked copies of people with an account of their
    -- own, and members without one — anonymised with them when ticked, with their own linked copies.
    CREATE TEMP TABLE ap_members (id integer PRIMARY KEY, linked boolean, ticked boolean, minor boolean, bookings bigint,
                                 shares_address boolean NOT NULL DEFAULT false);
    INSERT INTO ap_members
    SELECT q.id, q.account_person_id IS NOT NULL, q.id = ANY (v_members),
           coalesce(q.birthdate > current_date - interval '18 years', false),
           (SELECT count(*) FROM document d WHERE d.person_id = q.id)
      FROM person q
     WHERE q.frontend_account_id = v_account_id AND q.id <> p_person_id AND NOT q.removed;
    SELECT string_agg(x::text, ', ') INTO v_text
      FROM unnest(v_members) AS x
     WHERE x IS NULL OR NOT EXISTS (SELECT 1 FROM ap_members m WHERE m.id = x AND NOT m.linked);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseMemberError] persons % are not members of the account of person % without an account of their own', v_text, p_person_id;
    END IF;
    INSERT INTO ap_persons SELECT m.id FROM ap_members m WHERE m.ticked ON CONFLICT DO NOTHING;
    INSERT INTO ap_persons SELECT q.id FROM person q JOIN ap_members m ON m.ticked AND q.account_person_id = m.id
    ON CONFLICT DO NOTHING;
    ANALYZE ap_persons; -- temp tables are never auto-analysed: without it the planner guesses, and can scan

    -- Addresses that are certainly theirs: the persons' emails and their own account's login.
    CREATE TEMP TABLE ap_core_addresses (address text PRIMARY KEY);
    INSERT INTO ap_core_addresses
    SELECT DISTINCT a FROM (
        SELECT lower(trim(q.email)) AS a FROM person q JOIN ap_persons s ON s.id = q.id
        UNION SELECT lower(trim(fa.username)) FROM frontend_account fa WHERE fa.id = v_account_id
    ) t WHERE position('@' IN coalesce(a, '')) > 1;

    -- Members typed with one of those addresses (the owner's, as their own): it goes from their row (below).
    UPDATE ap_members m SET shares_address = true
      FROM person q WHERE q.id = m.id AND NOT m.linked AND NOT m.ticked
       AND lower(trim(q.email)) IN (SELECT address FROM ap_core_addresses);

    -- Their names, as public.abc_names() spells them (lower case, no accents, words sorted). Two words at least:
    -- a single word matches too many people to be a match.
    -- from_row: the name of one of their person rows. Only those make a booking with their address theirs without a
    -- tick (below).
    CREATE TEMP TABLE ap_names (abc text PRIMARY KEY, from_row boolean NOT NULL);
    INSERT INTO ap_names
    SELECT DISTINCT nm, true FROM (
        SELECT public.abc_names(q.first_name || ' ' || q.last_name) AS nm FROM person q JOIN ap_persons s ON s.id = q.id
    ) t WHERE position(' ' IN trim(coalesce(nm, ''))) > 0;

    -- Removed rows of the same human: a soft-deleted duplicate (merging customers on the customers screen removes the
    -- row and keeps every field) with one of their addresses AND one of their names. On kbs_local in November 2026,
    -- 983 removed rows held a live customer's address, 292 of them under the same name. Anonymised with them — left
    -- alone, a 'removed' row would keep their name, phone and street, and its bookings with them.
    INSERT INTO ap_persons
    SELECT q.id FROM person q
     WHERE q.removed AND q.anonymised_date IS NULL
       AND lower(trim(q.email)) IN (SELECT address FROM ap_core_addresses)
       AND q.abc_names IN (SELECT abc FROM ap_names WHERE from_row)
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_removed_same = ROW_COUNT;
    ANALYZE ap_persons;

    -- A name on one of their own bookings (an old name, a typo) counts too — unless somebody else bears it: an own
    -- booking often carries a family member's name (booked under the owner's person row), and that member, unticked,
    -- must not lose their bookings as if they were the requester's.
    INSERT INTO ap_names
    SELECT DISTINCT d.person_abc_names, false FROM document d JOIN ap_persons s ON s.id = d.person_id
     WHERE position(' ' IN trim(coalesce(d.person_abc_names, ''))) > 0
       AND NOT EXISTS (SELECT 1 FROM person x WHERE x.abc_names = d.person_abc_names AND NOT x.removed
                         AND NOT EXISTS (SELECT 1 FROM ap_persons s2 WHERE s2.id = x.id))
    ON CONFLICT DO NOTHING;
    SELECT array_agg(abc), array_agg(abc) FILTER (WHERE from_row) INTO v_names, v_row_names FROM ap_names;
    v_names := coalesce(v_names, '{}'::text[]);
    v_row_names := coalesce(v_row_names, '{}'::text[]);
    -- The names of their rows nobody else bears (no live person row outside theirs — an unticked member counts as
    -- somebody else): only those are cleared on bookings and rows that nothing else ties to them. A name known only
    -- from a booking is never certain enough for that (a child booked under the parent's row has no row of their
    -- own to show), so carers, room lines and history match on the row names (v_row_names) alone.
    SELECT array_agg(a.abc) INTO v_unique_names FROM ap_names a
     WHERE a.from_row AND NOT EXISTS (SELECT 1 FROM person x WHERE x.abc_names = a.abc AND NOT x.removed
                         AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = x.id));
    v_unique_names := coalesce(v_unique_names, '{}'::text[]);

    -- Addresses that are probably theirs (their bookings' snapshot, accounts they owned before a move), KEPT ONLY
    -- WHEN NOTHING SAYS OTHERWISE: an inferred address is dropped as soon as it also belongs to somebody else — a
    -- person row (removed or not) or another account holds it, a volunteering application of another person uses
    -- it, or a booking carries it under a name that is not one of theirs (a member with no email books under the
    -- account owner's address; staff may have typed a relative's or the reception's). Only these and the core
    -- addresses drive the steps that need no tick.
    CREATE TEMP TABLE ap_addresses (address text PRIMARY KEY);
    INSERT INTO ap_addresses SELECT address FROM ap_core_addresses;
    INSERT INTO ap_addresses
    SELECT DISTINCT a FROM (
        SELECT lower(trim(d.person_email)) AS a FROM document d JOIN ap_persons s ON s.id = d.person_id
        UNION SELECT lower(trim(m.from_account_username)) FROM person_account_move m JOIN ap_persons s ON s.id = m.person_id
         WHERE m.was_owner
    ) t
     WHERE position('@' IN coalesce(a, '')) > 1
       AND NOT EXISTS (SELECT 1 FROM person x WHERE lower(trim(x.email)) = t.a
                          AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = x.id)
                          -- a removed duplicate under one of their names is them (joined below), not somebody else
                          AND NOT (x.removed AND x.anonymised_date IS NULL AND x.abc_names = ANY (v_row_names)))
       AND NOT EXISTS (SELECT 1 FROM frontend_account fa WHERE lower(trim(fa.username)) = t.a
                          AND fa.id IS DISTINCT FROM v_account_id)
       AND NOT EXISTS (SELECT 1 FROM volunteering_application va WHERE lower(trim(va.email)) = t.a
                          AND va.person_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = va.person_id))
       AND NOT EXISTS (SELECT 1 FROM document d WHERE lower(trim(d.person_email)) = t.a
                          AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = d.person_id)
                          -- a booking without a name is not known to be theirs: it contradicts too
                          AND NOT coalesce(d.person_abc_names = ANY (v_names), false))
    ON CONFLICT DO NOTHING;
    ANALYZE ap_addresses;

    -- A removed duplicate under one of those older addresses and one of their names: the same human too.
    INSERT INTO ap_persons
    SELECT q.id FROM person q
     WHERE q.removed AND q.anonymised_date IS NULL
       AND lower(trim(q.email)) IN (SELECT address FROM ap_addresses)
       AND q.abc_names = ANY (v_row_names)
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS n = ROW_COUNT;
    v_removed_same := v_removed_same + n;
    ANALYZE ap_persons;

    SELECT array_agg(address) INTO v_addresses FROM ap_addresses;
    v_addresses := coalesce(v_addresses, '{}'::text[]);

    -- Their phone numbers, as the last 9 digits (so +44 7700… and 07700… meet).
    CREATE TEMP TABLE ap_phones (phone text PRIMARY KEY);
    INSERT INTO ap_phones
    SELECT DISTINCT right(dg, 9) FROM (
        SELECT regexp_replace(q.phone, '\D', '', 'g') AS dg FROM person q JOIN ap_persons s ON s.id = q.id
        UNION SELECT regexp_replace(d.person_phone, '\D', '', 'g') FROM document d JOIN ap_persons s ON s.id = d.person_id
    ) t WHERE length(coalesce(dg, '')) >= 9;

    -- A phone on more than 5 bookings made without an account is a shared number — a centre's, a family's, an
    -- organiser's — not evidence that a booking is theirs. On production (November 2026) 2,019 phones were, between
    -- them carrying 22,755 of 88,587 such bookings: matched on, they would bury the real candidates. Set aside.
    ALTER TABLE ap_phones ADD COLUMN guest_bookings bigint;
    UPDATE ap_phones ph SET guest_bookings = c.n
      FROM (SELECT right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) AS phone, count(*) AS n
              FROM document d
             WHERE d.person_id IS NULL AND length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
               AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ap_phones)
             GROUP BY 1) c
     WHERE c.phone = ph.phone;
    DELETE FROM ap_phones WHERE guest_bookings > 5;
    GET DIAGNOSTICS v_shared_phones = ROW_COUNT;

    -- ================= REFUSALS (before any write) =================
    SELECT string_agg(DISTINCT what, ', ') INTO v_text FROM (
        SELECT 'super administrator' AS what FROM authorization_super_admin x JOIN ap_persons s ON s.id = x.super_admin_id
        UNION ALL SELECT 'organization administrator' FROM authorization_organization_admin x JOIN ap_persons s ON s.id = x.admin_id
        UNION ALL SELECT 'organization access' FROM authorization_organization_user_access x JOIN ap_persons s ON s.id = x.user_id
        UNION ALL SELECT 'management' FROM authorization_management x JOIN ap_persons s ON s.id IN (x.manager_id, x.user_id)
        UNION ALL SELECT 'role' FROM role_attribution x JOIN ap_persons s ON s.id = x.person_id
        UNION ALL SELECT 'teacher' FROM teacher x JOIN ap_persons s ON s.id = x.person_id
        UNION ALL SELECT 'back-office account' FROM frontend_account fa WHERE fa.id = v_account_id AND fa.backoffice
    ) t;
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseStaffError] person % holds staff rights or roles (%): remove them first', p_person_id, v_text;
    END IF;

    SELECT string_agg(DISTINCT who, ', ') INTO v_text FROM (
        SELECT 'person ' || x.id AS who FROM person x JOIN ap_core_addresses a ON a.address = lower(trim(x.email))
         WHERE NOT x.removed AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = x.id)
           -- A member of their own account typed with their address is not a duplicate: it loses the address below
           AND NOT EXISTS (SELECT 1 FROM ap_members m WHERE m.id = x.id AND NOT m.linked)
        UNION ALL
        SELECT 'account ' || fa.id FROM frontend_account fa JOIN ap_core_addresses a ON a.address = lower(trim(fa.username))
         WHERE fa.id IS DISTINCT FROM v_account_id
        UNION ALL
        SELECT 'volunteering application ' || va.id FROM volunteering_application va
          JOIN ap_core_addresses a ON a.address = lower(trim(va.email))
         WHERE va.person_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = va.person_id)
    ) t;
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseDuplicatesError] the email address of person % is also used by % : merge or correct them first', p_person_id, v_text;
    END IF;

    -- Bookings made without an account that match them.
    CREATE TEMP TABLE ap_candidates AS
    SELECT d.id, d.ref, d.event_id,
           (lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses))             AS by_email,
           coalesce(d.person_abc_names = ANY (v_names), false)                            AS by_name,
           coalesce(d.person_abc_names = ANY (v_row_names), false)                        AS by_row_name,
           (length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
            AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ap_phones)) AS by_phone
      FROM document d
     WHERE d.person_id IS NULL
       AND (   lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses)
            OR d.person_abc_names = ANY (v_names)
            OR (length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
                AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ap_phones)));
    ALTER TABLE ap_candidates ADD COLUMN action text, ADD COLUMN carer_match boolean NOT NULL DEFAULT false;
    -- email + the name of one of their rows = the same human; email + a name only from their bookings' snapshots =
    -- perhaps them (ticked, in full; unticked, the address still goes — see ap_email_docs); their email alone = their
    -- contact details on somebody else's booking (the address is certainly theirs, so that needs no tick); name
    -- (with or without phone) = perhaps them, under another address; phone alone = perhaps a shared phone.
    UPDATE ap_candidates SET action = CASE WHEN by_email AND by_row_name THEN 'auto'
                                           WHEN by_email AND by_name THEN 'full'
                                           WHEN by_email THEN 'auto-contact'
                                           WHEN by_name THEN 'full'
                                           ELSE 'contact' END;
    ALTER TABLE ap_candidates ADD PRIMARY KEY (id);

    -- Carer names are typed: on the data this was rehearsed on, not one of 5,374 links the carer's person row. So a
    -- booking naming them as carer is found by the name — narrowed first to bookings that carry one, since
    -- public.abc_names() is plpgsql (see the room-sharing step). A name match is THEIRS without a tick only when
    -- something else ties the booking to them (it is theirs, a member's, one they paid for, or carries their address)
    -- or nobody else bears that name; otherwise somebody else's carer slot would be wiped on a namesake's account —
    -- a carer is who answers for a child. Those are candidates (action 'carer': ticked, that slot alone is cleared).
    CREATE TEMP TABLE ap_carer_docs AS
    SELECT c.id, c.n1 = ANY (v_row_names) OR c.n2 = ANY (v_row_names) AS by_name,
           c.n1 = ANY (v_unique_names) OR c.n2 = ANY (v_unique_names) AS unique_name
      FROM (SELECT x.id, public.abc_names(x.n1) AS n1, public.abc_names(x.n2) AS n2
              FROM (SELECT d.id, d.person_carer1_name AS n1, d.person_carer2_name AS n2 FROM document d
                     WHERE d.person_carer1_name IS NOT NULL OR d.person_carer2_name IS NOT NULL
                    OFFSET 0) x
            OFFSET 0) c
     WHERE c.n1 = ANY (v_row_names) OR c.n2 = ANY (v_row_names);
    ALTER TABLE ap_carer_docs ADD COLUMN tied boolean;
    UPDATE ap_carer_docs cd SET tied = cd.unique_name
        OR EXISTS (SELECT 1 FROM document d WHERE d.id = cd.id
                     AND (   d.person_id IN (SELECT s.id FROM ap_persons s) OR d.person_id IN (SELECT m.id FROM ap_members m)
                          OR d.payer_id IN (SELECT s.id FROM ap_persons s)
                          OR d.person_carer1_id IN (SELECT s.id FROM ap_persons s)
                          OR d.person_carer2_id IN (SELECT s.id FROM ap_persons s)
                          OR lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses)))
        OR EXISTS (SELECT 1 FROM ap_candidates c WHERE c.id = cd.id AND c.action IN ('auto', 'auto-contact'));
    -- A booking that is already a candidate on its own (name, phone) carries the carer match along; the others
    -- become 'carer' candidates.
    UPDATE ap_candidates c SET carer_match = true FROM ap_carer_docs cd WHERE cd.id = c.id AND NOT cd.tied;
    INSERT INTO ap_candidates (id, ref, event_id, by_email, by_name, by_row_name, by_phone, action, carer_match)
    SELECT d.id, d.ref, d.event_id, false, true, false, false, 'carer', true
      FROM ap_carer_docs cd JOIN document d ON d.id = cd.id
     WHERE NOT cd.tied
    ON CONFLICT (id) DO NOTHING;

    -- A preview (lenient) drops a tick that is no longer a candidate — the operator's ticks follow what the last
    -- preview offered, and unticking a member can take candidates away; a commit refuses it.
    IF p_lenient THEN
        v_ticked := ARRAY(SELECT DISTINCT x FROM unnest(v_ticked) AS x
                           WHERE EXISTS (SELECT 1 FROM ap_candidates c WHERE c.id = x AND c.action IN ('full', 'contact', 'carer'))
                           ORDER BY x);
    END IF;
    SELECT string_agg(x::text, ', ') INTO v_text
      FROM unnest(v_ticked) AS x
     WHERE x IS NULL OR NOT EXISTS (SELECT 1 FROM ap_candidates c WHERE c.id = x);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseCandidateError] bookings % are not candidates of person %', v_text, p_person_id;
    END IF;

    -- The bookings, each in one scope (the first that applies: what happens to the booking's person_* snapshot),
    -- and two flags that hold whatever the scope: paid = they paid for it (it counts for the refusals and its
    -- payments' comments are tagged), cared = it names them as carer (that slot is cleared). A child's booking made
    -- under the parent's address, paid by the parent who is also its carer, is 'contact', paid and cared at once.
    CREATE TEMP TABLE ap_docs (id integer PRIMARY KEY, scope text NOT NULL,
                               paid boolean NOT NULL DEFAULT false, cared boolean NOT NULL DEFAULT false);
    INSERT INTO ap_docs SELECT d.id, 'full' FROM document d JOIN ap_persons s ON s.id = d.person_id;
    INSERT INTO ap_docs SELECT c.id, 'full' FROM ap_candidates c
     WHERE c.action = 'auto' OR (c.action = 'full' AND c.id = ANY (v_ticked)) ON CONFLICT DO NOTHING;
    INSERT INTO ap_docs SELECT c.id, 'contact' FROM ap_candidates c
     WHERE c.action = 'auto-contact' OR (c.action = 'contact' AND c.id = ANY (v_ticked)) ON CONFLICT DO NOTHING;
    INSERT INTO ap_docs (id, scope, paid) SELECT DISTINCT d.id, 'payer', true FROM document d JOIN ap_persons s ON s.id = d.payer_id
    ON CONFLICT (id) DO UPDATE SET paid = true;
    INSERT INTO ap_docs (id, scope, cared)
    SELECT DISTINCT d.id, 'carer', true FROM document d JOIN ap_persons s ON s.id IN (d.person_carer1_id, d.person_carer2_id)
    ON CONFLICT (id) DO UPDATE SET cared = true;
    -- ... and by the typed name (ap_carer_docs): tied to them, or ticked.
    INSERT INTO ap_docs (id, scope, cared)
    SELECT cd.id, 'carer', true FROM ap_carer_docs cd
     WHERE cd.tied OR cd.id = ANY (v_ticked)
    ON CONFLICT (id) DO UPDATE SET cared = true;
    ANALYZE ap_docs;

    -- Not over, then money owed (anonymise_person_blocking(): the preview's warnings read the same rules).
    SELECT string_agg(DISTINCT coalesce(b.ref::text, 'id ' || b.document_id), ', ') INTO v_text
      FROM public.anonymise_person_blocking(ARRAY(SELECT t.id FROM ap_docs t WHERE t.scope = 'full' OR t.paid)) b
     WHERE b.kind = 'active';
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseActiveBookingsError] person % has bookings that are not over (refs %): cancel them, or wait until they end', p_person_id, v_text;
    END IF;

    SELECT string_agg(DISTINCT coalesce(b.ref::text, 'id ' || b.document_id), ', ') INTO v_text
      FROM public.anonymise_person_blocking(ARRAY(SELECT t.id FROM ap_docs t WHERE t.scope = 'full' OR t.paid)) b
     WHERE b.kind = 'balance';
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseBalanceError] person % has bookings with money still owed either way (refs %): settle them first', p_person_id, v_text;
    END IF;

    SELECT count(*) INTO n FROM enqueued_request er JOIN ap_docs t ON t.id = er.document_id AND t.scope IN ('full', 'contact')
     WHERE er.execution_date IS NULL;
    IF n > 0 THEN
        RAISE EXCEPTION '[PersonAnonymiseQueuedPayloadsError] % un-executed KBS2 requests still hold bookings of person %', n, p_person_id;
    END IF;

    -- The mails to or about them: of a 'full'/'contact' booking, to one of their addresses or rows, or from one.
    -- Their recipient rows and the mails they sent are found ONCE (recipient and mail are the biggest tables after
    -- history: 1.1M recipients on production) and reused by the rewrite and the checks; REPEATABLE READ means a
    -- second scan could only find these same rows. The person_id arm alone can use its index.
    CREATE TEMP TABLE ap_recipients (id integer PRIMARY KEY, mail_id integer);
    INSERT INTO ap_recipients
    SELECT r.id, r.mail_id FROM recipient r WHERE r.person_id IN (SELECT id FROM ap_persons)
    UNION SELECT r.id, r.mail_id FROM recipient r WHERE lower(trim(r.email)) IN (SELECT address FROM ap_addresses);
    ANALYZE ap_recipients;
    CREATE TEMP TABLE ap_sender_mails (id integer PRIMARY KEY);
    INSERT INTO ap_sender_mails SELECT m.id FROM mail m WHERE lower(trim(m.from_email)) IN (SELECT address FROM ap_addresses);
    CREATE TEMP TABLE ap_mails (id integer PRIMARY KEY);
    INSERT INTO ap_mails
    SELECT m.id FROM mail m JOIN ap_docs t ON t.id = m.document_id AND t.scope IN ('full', 'contact')
    UNION SELECT r.mail_id FROM ap_recipients r WHERE r.mail_id IS NOT NULL
    UNION SELECT sm.id FROM ap_sender_mails sm;
    ANALYZE ap_mails;

    -- Still waiting to be sent: rewriting it would send the tag. Older unsent rows are past every mailer's drain
    -- window (7 days) and are rewritten with the rest.
    SELECT count(*) INTO n FROM mail m JOIN ap_mails a ON a.id = m.id
     WHERE NOT m.transmitted AND m.date > now() - interval '30 days';
    IF n > 0 THEN
        RAISE EXCEPTION '[PersonAnonymiseQueuedMailError] % mails to or about person % are still waiting to be sent: try again once they have gone', n, p_person_id;
    END IF;

    -- Events of their bookings, and other people's lines naming them as room owner or mate in those events.
    CREATE TEMP TABLE ap_events AS
    SELECT DISTINCT d.event_id FROM document d JOIN ap_docs t ON t.id = d.id AND t.scope = 'full' WHERE d.event_id IS NOT NULL;
    ANALYZE ap_events;

    -- Two passes, each narrowed BEFORE any name is normalised: public.abc_names() is plpgsql, and called on the
    -- eight name columns of every booking line it took minutes (1.4M lines). First the lines naming them as room
    -- owner by id; then, among the lines of their events' bookings that carry a room name at all, those whose name
    -- is theirs — each present name normalised once.
    CREATE TEMP TABLE ap_share_lines (id integer PRIMARY KEY, document_id integer NOT NULL);
    INSERT INTO ap_share_lines
    SELECT dl.id, dl.document_id FROM document_line dl
     WHERE dl.share_mate_owner_person_id IN (SELECT id FROM ap_persons)
       AND NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = dl.document_id AND t.scope = 'full');
    INSERT INTO ap_share_lines
    SELECT DISTINCT c.id, c.document_id
      FROM (SELECT dl.id, dl.document_id,
                   ARRAY[dl.share_mate_owner_name, dl.share_owner_mate1_name, dl.share_owner_mate2_name,
                         dl.share_owner_mate3_name, dl.share_owner_mate4_name, dl.share_owner_mate5_name,
                         dl.share_owner_mate6_name, dl.share_owner_mate7_name] AS names
              FROM ap_events e
              JOIN document d ON d.event_id = e.event_id
              JOIN document_line dl ON dl.document_id = d.id
             WHERE NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = d.id AND t.scope = 'full')
               AND (dl.share_mate_owner_name IS NOT NULL OR dl.share_owner_mate1_name IS NOT NULL
                    OR dl.share_owner_mate2_name IS NOT NULL OR dl.share_owner_mate3_name IS NOT NULL
                    OR dl.share_owner_mate4_name IS NOT NULL OR dl.share_owner_mate5_name IS NOT NULL
                    OR dl.share_owner_mate6_name IS NOT NULL OR dl.share_owner_mate7_name IS NOT NULL)
            OFFSET 0) c -- OFFSET 0: a fence, so the planner cannot push the name test below into this scan
      CROSS JOIN LATERAL unnest(c.names) AS n(name)
     WHERE n.name IS NOT NULL AND public.abc_names(n.name) = ANY (v_row_names)
    ON CONFLICT DO NOTHING;

    -- Somebody else's bookings (a family member's, one they paid for or cared on) carrying one of their addresses:
    -- that address goes, nothing else.
    CREATE TEMP TABLE ap_email_docs AS
    SELECT d.id FROM document d
     WHERE lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses)
       AND NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = d.id AND t.scope IN ('full', 'contact'));
    ALTER TABLE ap_email_docs ADD PRIMARY KEY (id);
    ANALYZE ap_email_docs;
    ANALYZE ap_share_lines;

    -- The history rows to look at — every booking in scope, those carrying their address, and the other bookings
    -- whose lines name them — with each one's changes redacted ONCE, here.
    -- One table of booking ids first, then history by its document_id index: an OR of three INs on history
    -- (2.8M rows on production) reads all of it.
    -- cared: the booking's carer slot naming them goes, so its history's carer names go too (and only then).
    CREATE TEMP TABLE ap_hist_docs (id integer PRIMARY KEY, scope text NOT NULL, cared boolean NOT NULL);
    INSERT INTO ap_hist_docs SELECT t.id, t.scope, t.cared FROM ap_docs t;
    INSERT INTO ap_hist_docs SELECT ed.id, 'other', false FROM ap_email_docs ed ON CONFLICT DO NOTHING;
    INSERT INTO ap_hist_docs SELECT DISTINCT sl.document_id, 'other', false FROM ap_share_lines sl ON CONFLICT DO NOTHING;
    -- The other bookings of their bookings' carts: a guest signed in by a cart or magic link acts on the whole cart,
    -- and history.username records them as 'guest:<their address>' (HistoryRecorder, since 2026-10-19).
    INSERT INTO ap_hist_docs
    SELECT DISTINCT d2.id, 'other', false
      FROM ap_docs t JOIN document d ON d.id = t.id AND d.cart_id IS NOT NULL
      JOIN document d2 ON d2.cart_id = d.cart_id
     WHERE t.scope IN ('full', 'contact')
    ON CONFLICT DO NOTHING;
    ANALYZE ap_hist_docs;
    CREATE TEMP TABLE ap_hist AS
    SELECT h.id, t.scope,
           public.anonymise_person_changes(h.changes, t.scope, v_row_names, v_addresses, v_first,
                                           '#' || coalesce(hd.person_id, p_person_id), v_tag, t.cared) AS new_changes,
           public.anonymise_person_comment(h.comment, v_row_names, t.cared) AS new_comment,
           -- who acted: a guest is recorded as 'guest:' + their address (cut to 64), an SSO login as the address
           CASE WHEN h.username LIKE 'guest:%'
                     AND lower(substr(h.username, 7)) IN (SELECT left(a.address, 58) FROM ap_addresses a)
                THEN 'guest:' || v_email
                WHEN lower(trim(h.username)) IN (SELECT a.address FROM ap_addresses a)
                THEN v_email::text
                ELSE h.username END AS new_username
      FROM ap_hist_docs t
      JOIN history h ON h.document_id = t.id
      LEFT JOIN document hd ON hd.id = t.id;
    ALTER TABLE ap_hist ADD PRIMARY KEY (id);
    ANALYZE ap_hist;

    -- Other customers bearing one of their names: person rows not removed, not theirs, not a member of their
    -- account, not a linked copy (the person it copies is listed instead). NOT anonymised: nothing but the name
    -- links them, and names collide — on kbs_local in November 2026 a third of 58,000 customers shared theirs with
    -- somebody, up to 20 at once. Listed so staff can spot the same human under another email (a customer anonymised
    -- that day had a second family account, all four names alike, 110 bookings) and merge it first or anonymise it
    -- on its own. family_names = the other people on that customer's account who bear a name from this account
    -- (theirs or a member's): a family registered twice scores high, and is listed first.
    SELECT array_agg(DISTINCT q.abc_names) INTO v_family_names FROM person q
     WHERE (q.id IN (SELECT s.id FROM ap_persons s) OR q.id IN (SELECT m.id FROM ap_members m))
       AND position(' ' IN trim(coalesce(q.abc_names, ''))) > 0;
    v_family_names := coalesce(v_family_names, '{}'::text[]);
    CREATE TEMP TABLE ap_namesakes AS
    SELECT x.id, x.frontend_account_id AS account_id, x.owner,
           (SELECT count(*) FROM document d WHERE d.person_id = x.id) AS bookings,
           (SELECT count(*) FROM person y
             WHERE y.frontend_account_id = x.frontend_account_id AND y.id <> x.id AND NOT y.removed
               AND y.abc_names = ANY (v_family_names)) AS family_names
      FROM person x
     WHERE x.abc_names = ANY (v_names) AND NOT x.removed AND x.account_person_id IS NULL
       AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = x.id)
       AND NOT EXISTS (SELECT 1 FROM ap_members m WHERE m.id = x.id);

    -- What ticking each member (without an account of their own) would bring into the refusals: their bookings and
    -- their copies', those they paid for, and the bookings made without an account under their name with their own
    -- address or one of the requester's (ticked, those become theirs in full), and their removed duplicates'. A hint
    -- shown before the tick, not the rule: the preview re-run with the tick has the last word.
    CREATE TEMP TABLE ap_member_docs AS
    WITH mp AS (SELECT m.id AS member_id, q.id AS person_id, q.abc_names, lower(trim(q.email)) AS email
                  FROM ap_members m JOIN person mm ON mm.id = m.id
                  JOIN person q ON q.id = m.id OR q.account_person_id = m.id
                                -- their removed duplicates, which ticking them brings in too
                                OR (q.removed AND q.anonymised_date IS NULL AND q.abc_names = mm.abc_names
                                    AND lower(trim(q.email)) = lower(trim(mm.email)))
                 WHERE NOT m.linked)
    SELECT mp.member_id, d.id AS doc_id FROM mp JOIN document d ON d.person_id = mp.person_id
    UNION
    SELECT mp.member_id, d.id FROM mp JOIN document d ON d.payer_id = mp.person_id
    UNION
    SELECT mp.member_id, d.id FROM mp JOIN document d
        ON d.person_id IS NULL AND d.person_abc_names = mp.abc_names
       AND (lower(trim(d.person_email)) = mp.email OR lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses));

    -- ================= THE GRID: where, who, what is in scope =================
    SELECT count(*) INTO v_mail_rows_before FROM mail;
    SELECT count(*) INTO v_mail_queued_before FROM mail WHERE NOT transmitted;

    RETURN QUERY VALUES
        ('where',  'database',  current_database()::text),
        ('where',  'run_at',    now()::text),
        ('where',  'tag',       v_tag::text),
        ('person', 'id',        p_person_id::text),
        ('person', 'linked_copies', (SELECT count(*) FROM person q WHERE q.account_person_id = p_person_id)::text),
        ('person', 'own_account',   (v_account_id IS NOT NULL)::text),
        ('person', 'addresses',     (SELECT count(*) FROM ap_addresses)::text),
        ('scope',  'own_bookings',  (SELECT count(*) FROM document d JOIN ap_persons s ON s.id = d.person_id)::text),
        ('scope',  'guest_bookings_matched', (SELECT count(*) FROM ap_candidates WHERE action = 'auto')::text),
        ('scope',  'bookings_with_their_address',
                   ((SELECT count(*) FROM ap_candidates WHERE action = 'auto-contact') + (SELECT count(*) FROM ap_email_docs))::text),
        ('scope',  'candidates',             (SELECT count(*) FROM ap_candidates WHERE action IN ('full', 'contact', 'carer'))::text),
        ('scope',  'removed_duplicates',     v_removed_same::text),
        ('scope',  'candidates_ticked',      cardinality(v_ticked)::text),
        ('scope',  'shared_phones_ignored',  v_shared_phones::text),
        ('scope',  'account_members',        (SELECT count(*) FROM ap_members)::text),
        ('scope',  'account_members_ticked', (SELECT count(*) FROM ap_members WHERE ticked)::text),
        ('scope',  'member_bookings_with_their_address',
                   (SELECT count(*) FROM ap_email_docs ed JOIN document d ON d.id = ed.id
                      JOIN ap_members m ON m.id = d.person_id AND NOT m.ticked)::text),
        ('scope',  'namesakes',              (SELECT count(*) FROM ap_namesakes)::text),
        ('scope',  'paid_for_others',        (SELECT count(*) FROM ap_docs WHERE paid AND scope <> 'full')::text),
        ('scope',  'as_carer',               (SELECT count(*) FROM ap_docs WHERE cared AND scope <> 'full')::text),
        ('scope',  'named_on_room_lines',    (SELECT count(*) FROM ap_share_lines)::text),
        ('scope',  'mails',                  (SELECT count(*) FROM ap_mails)::text),
        ('scope',  'volunteering_photos_outside_db',
                   (SELECT count(*) FROM volunteering_application va
                     WHERE (va.person_id IN (SELECT id FROM ap_persons)
                            OR (va.person_id IS NULL AND lower(trim(va.email)) IN (SELECT address FROM ap_addresses)))
                       AND nullif(va.photo, '') IS NOT NULL)::text);

    -- One row per candidate, for the screen: booking id, ref, event, and why it matched. Never the booking's
    -- names or addresses.
    RETURN QUERY
    SELECT 'candidate'::text, c.id::text,
           jsonb_build_object('documentId', c.id, 'ref', c.ref, 'eventId', c.event_id, 'eventName', e.name,
                              'eventStart', to_char(e.start_date, 'YYYY-MM-DD'),
                              'match', concat_ws('+', CASE WHEN c.by_email THEN 'email' END,
                                                      CASE WHEN c.by_name THEN 'name' END,
                                                      CASE WHEN c.by_phone THEN 'phone' END),
                              'action', c.action, 'ticked', c.id = ANY (v_ticked), 'carerMatch', c.carer_match,
                              -- what would stop the run if it were ticked ('contact' leaves the booking's own
                              -- refusals alone): staff see it here, not after typing the phrase
                              'blockedBy', CASE WHEN c.action = 'full' THEN public.anonymise_person_blockers(ARRAY[c.id]) END)::text
      FROM ap_candidates c LEFT JOIN event e ON e.id = c.event_id
     WHERE c.action IN ('full', 'contact', 'carer')
     ORDER BY e.start_date DESC NULLS LAST, c.id;

    -- One row per member of their account, for the screen: ids, flags and counts — the names travel in the
    -- endpoint's reply, never in this grid (which the run ledger keeps).
    RETURN QUERY
    SELECT 'member'::text, m.id::text,
           jsonb_build_object('personId', m.id, 'linked', m.linked, 'ticked', m.ticked, 'minor', m.minor,
                              'bookings', m.bookings, 'sharesAddress', m.shares_address,
                              -- ticked, their bookings join the run's refusals (ap_member_docs): what would stop
                              -- it, as on a candidate
                              'blockedBy', CASE WHEN NOT m.linked THEN public.anonymise_person_blockers(ARRAY(
                                  SELECT md.doc_id FROM ap_member_docs md WHERE md.member_id = m.id)) END)::text
      FROM ap_members m ORDER BY m.id;

    -- The ticks this run ran with (a preview drops stale ones): the very sets a commit of this preview must send.
    RETURN QUERY VALUES ('ticks', 'candidates', array_to_string(v_ticked, ',')),
                        ('ticks', 'members',    array_to_string(v_members, ','));

    -- The other customers bearing one of their names, most likely the same human first: ids and counts only (the
    -- names travel in the endpoint's reply), the first 50 — scope/namesakes says how many there are in all.
    RETURN QUERY
    SELECT 'namesake'::text, n.id::text,
           jsonb_build_object('personId', n.id, 'accountId', n.account_id, 'owner', n.owner, 'bookings', n.bookings,
                              'familyNames', n.family_names)::text
      FROM ap_namesakes n ORDER BY n.family_names DESC, n.bookings DESC, n.id LIMIT 50;

    -- ================= THE ANONYMISATION =================

    -- person — first, so the upcoming-bookings trigger copies the anonymised details (the document step below
    -- anonymises those bookings again anyway).
    UPDATE person q
       SET first_name = v_first, last_name = '#' || q.id, name = NULL, lay_name = NULL,
           email = NULL, phone = NULL, street = NULL, post_code = NULL, latitude = NULL, longitude = NULL,
           passport = NULL, birthdate = NULL, carer1_name = NULL, carer1_id = NULL, carer2_name = NULL, carer2_id = NULL,
           removed = true, emailing_list = false, anonymised_date = now()
      FROM ap_persons s WHERE s.id = q.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person', n::text);

    -- Linked members are other people's copies on the deleted account: off the account (below), and removed.
    UPDATE person q SET removed = true FROM ap_members m WHERE m.id = q.id AND m.linked;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'member_copies_removed', n::text);

    -- Unticked members typed with their address: the address is theirs, not the member's. Its triggers re-sync the
    -- member's upcoming bookings; the account unlink below re-syncs them again without the account's login, and
    -- the document step clears the address from every booking that still carries it.
    UPDATE person q SET email = NULL FROM ap_members m WHERE m.id = q.id AND m.shares_address;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'member_address_cleared', n::text);

    -- Removed rows typed with one of their addresses under another name (a removed member, mostly — 692 on kbs_local
    -- sat on a live owner's account): the address goes, the rest is somebody else's.
    UPDATE person q SET email = NULL
     WHERE q.removed AND lower(trim(q.email)) IN (SELECT address FROM ap_core_addresses)
       AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = q.id);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'removed_row_address_cleared', n::text);

    -- Other people's rows naming them as carer.
    UPDATE person q
       SET carer1_name = CASE WHEN q.carer1_id IN (SELECT id FROM ap_persons) THEN NULL ELSE q.carer1_name END,
           carer1_id   = CASE WHEN q.carer1_id IN (SELECT id FROM ap_persons) THEN NULL ELSE q.carer1_id END,
           carer2_name = CASE WHEN q.carer2_id IN (SELECT id FROM ap_persons) THEN NULL ELSE q.carer2_name END,
           carer2_id   = CASE WHEN q.carer2_id IN (SELECT id FROM ap_persons) THEN NULL ELSE q.carer2_id END
     WHERE (q.carer1_id IN (SELECT id FROM ap_persons) OR q.carer2_id IN (SELECT id FROM ap_persons))
       AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = q.id);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_as_carer_by_id', n::text);

    -- ... and by the typed name (narrowed first: abc_names() is plpgsql) — on their account's rows, or when nobody
    -- else bears the name: on anybody else's row a namesake may be the carer (see ap_carer_docs).
    UPDATE person q
       SET carer1_name = CASE WHEN public.abc_names(q.carer1_name) = ANY (CASE WHEN q.frontend_account_id = v_account_id
                                                                              THEN v_row_names ELSE v_unique_names END)
                              THEN NULL ELSE q.carer1_name END,
           carer2_name = CASE WHEN public.abc_names(q.carer2_name) = ANY (CASE WHEN q.frontend_account_id = v_account_id
                                                                              THEN v_row_names ELSE v_unique_names END)
                              THEN NULL ELSE q.carer2_name END
     WHERE q.id IN (SELECT c.id FROM (SELECT x.id, x.carer1_name AS n1, x.carer2_name AS n2 FROM person x
                                       WHERE x.carer1_name IS NOT NULL OR x.carer2_name IS NOT NULL OFFSET 0) c
                     WHERE public.abc_names(c.n1) = ANY (v_row_names) OR public.abc_names(c.n2) = ANY (v_row_names))
       AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = q.id);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_as_carer_by_name', n::text);

    -- Sessions: the person row is never deleted, so its cascade never fires. Revoke them.
    UPDATE auth_session a
       SET revoked = now(), revoked_reason = 'gdpr-anonymised'
     WHERE a.revoked IS NULL
       AND (a.person_id IN (SELECT id FROM ap_persons) OR (v_account_id IS NOT NULL AND a.account_id = v_account_id));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'auth_session_revoked', n::text);

    -- Their own account: unlink (audit_account_move records it), then delete — credentials go by cascade.
    IF v_account_id IS NOT NULL THEN
        UPDATE person q SET frontend_account_id = NULL WHERE q.frontend_account_id = v_account_id;
        DELETE FROM frontend_account fa WHERE fa.id = v_account_id;
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('deleted', 'frontend_account', n::text);

    -- Backups taken by maintenance scripts, not by migrations, so a database may lack them: reached through EXECUTE.
    -- They hold logins (their address) and names; no foreign key leads to them, so the reference guard cannot.
    IF to_regclass('public.frontend_account_dissolved_backup') IS NOT NULL THEN
        EXECUTE 'DELETE FROM public.frontend_account_dissolved_backup b
                  WHERE lower(trim(b.username)) IN (SELECT address FROM ap_addresses)';
        GET DIAGNOSTICS n = ROW_COUNT;
        RETURN QUERY VALUES ('deleted', 'frontend_account_dissolved_backup', n::text);
    END IF;
    IF to_regclass('public.member_email_nullify_backup') IS NOT NULL THEN
        EXECUTE 'DELETE FROM public.member_email_nullify_backup b
                  WHERE b.person_id IN (SELECT id FROM ap_persons) OR b.frontend_account_id = $1
                     OR lower(trim(b.email)) IN (SELECT address FROM ap_addresses)' USING v_account_id;
        GET DIAGNOSTICS n = ROW_COUNT;
        RETURN QUERY VALUES ('deleted', 'member_email_nullify_backup', n::text);
    END IF;
    IF to_regclass('public.spare_owner_row_backup') IS NOT NULL THEN
        EXECUTE 'DELETE FROM public.spare_owner_row_backup b
                  WHERE b.person_id IN (SELECT id FROM ap_persons) OR b.surviving_owner_id IN (SELECT id FROM ap_persons)
                     OR b.frontend_account_id = $1' USING v_account_id;
        GET DIAGNOSTICS n = ROW_COUNT;
        RETURN QUERY VALUES ('deleted', 'spare_owner_row_backup', n::text);
    END IF;

    -- Account moves and link changes: their rows' login and any note, and THEIR login wherever it appears — the
    -- unlink above wrote it into the rows of the account's removed members too (those rows' note is the run's own
    -- audit note, kept).
    UPDATE person_account_move m
       SET from_account_username = CASE WHEN m.from_account_username IS NULL THEN NULL
                                        WHEN m.person_id IN (SELECT id FROM ap_persons)
                                          OR lower(trim(m.from_account_username)) IN (SELECT address FROM ap_addresses)
                                        THEN v_email ELSE m.from_account_username END,
           note = CASE WHEN m.person_id IN (SELECT id FROM ap_persons) AND m.note IS NOT NULL AND m.moved_at < now()
                            AND m.note NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE m.note END
     WHERE (m.person_id IN (SELECT id FROM ap_persons) OR lower(trim(m.from_account_username)) IN (SELECT address FROM ap_addresses))
       AND (m.from_account_username IS DISTINCT FROM v_email
            OR (m.person_id IN (SELECT id FROM ap_persons) AND m.note IS NOT NULL AND m.moved_at < now()
                AND m.note NOT LIKE v_tag_prefix || '%'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_account_move', n::text);

    UPDATE person_link_change c
       SET note = v_tag
      FROM ap_persons s
     WHERE s.id = c.person_id AND c.note IS NOT NULL AND c.changed_at < now() AND c.note NOT LIKE v_tag_prefix || '%';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_link_change', n::text);

    -- Magic links of their addresses. cart.magic_link_id has no ON DELETE action: clear it first.
    UPDATE cart c SET magic_link_id = NULL
     WHERE c.magic_link_id IN (SELECT ml.id FROM magic_link ml
                                WHERE lower(trim(ml.email)) IN (SELECT address FROM ap_addresses)
                                   OR lower(trim(ml.old_email)) IN (SELECT address FROM ap_addresses));
    DELETE FROM magic_link ml
     WHERE lower(trim(ml.email)) IN (SELECT address FROM ap_addresses)
        OR lower(trim(ml.old_email)) IN (SELECT address FROM ap_addresses);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'magic_link', n::text);

    -- Bookings.
    UPDATE document d
       SET person_first_name = v_first, person_last_name = '#' || coalesce(d.person_id, p_person_id),
           person_name = NULL, person_lay_name = NULL,
           person_email = NULL, person_phone = NULL, person_street = NULL, person_post_code = NULL,
           person_latitude = NULL, person_longitude = NULL, person_passport = NULL,
           person_carer1_name = NULL, person_carer2_name = NULL,
           inet_ip = NULL, inet_geo_latitude = NULL, inet_geo_longitude = NULL,
           special_needs = CASE WHEN d.special_needs IS NOT NULL AND d.special_needs NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.special_needs END,
           comment       = CASE WHEN d.comment       IS NOT NULL AND d.comment       NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.comment END,
           request       = CASE WHEN d.request       IS NOT NULL AND d.request       NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.request END
      FROM ap_docs t WHERE t.id = d.id AND t.scope = 'full';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_full', n::text);

    UPDATE document d
       SET person_email = NULL, person_phone = NULL, person_street = NULL, person_post_code = NULL,
           person_latitude = NULL, person_longitude = NULL
      FROM ap_docs t WHERE t.id = d.id AND t.scope = 'contact';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_contact', n::text);

    UPDATE document d
       SET person_carer1_name = CASE WHEN d.person_carer1_id IN (SELECT id FROM ap_persons)
                                       OR public.abc_names(d.person_carer1_name) = ANY (v_row_names)
                                     THEN NULL ELSE d.person_carer1_name END,
           person_carer2_name = CASE WHEN d.person_carer2_id IN (SELECT id FROM ap_persons)
                                       OR public.abc_names(d.person_carer2_name) = ANY (v_row_names)
                                     THEN NULL ELSE d.person_carer2_name END
      FROM ap_docs t WHERE t.id = d.id AND t.cared AND t.scope <> 'full';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_carer', n::text);

    UPDATE document d SET person_email = NULL
      FROM ap_email_docs ed WHERE ed.id = d.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_their_address', n::text);

    -- The bearer key of their bookings' carts: links in old emails stop opening them — but only a cart holding no
    -- one else's booking. A cart is often a family's (12% of carts held several people's bookings when this was
    -- written): rotating it would cut the others' links too, while it opens nothing of theirs that still identifies
    -- them. Those carts are counted instead.
    UPDATE cart c SET uuid = gen_random_uuid()::text
     WHERE c.id IN (SELECT d.cart_id FROM document d JOIN ap_docs t ON t.id = d.id AND t.scope = 'full')
       AND NOT EXISTS (SELECT 1 FROM document o WHERE o.cart_id = c.id
                         AND NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = o.id AND t.scope = 'full'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'cart_uuid', n::text);
    RETURN QUERY VALUES ('kept', 'cart_shared_with_others',
        (SELECT count(DISTINCT d.cart_id) FROM document d JOIN ap_docs t ON t.id = d.id AND t.scope = 'full'
          WHERE d.cart_id IS NOT NULL
            AND EXISTS (SELECT 1 FROM document o WHERE o.cart_id = d.cart_id
                          AND NOT EXISTS (SELECT 1 FROM ap_docs t2 WHERE t2.id = o.id AND t2.scope = 'full')))::text);

    UPDATE document_line dl SET comment = v_tag
      FROM ap_docs t
     WHERE t.id = dl.document_id AND t.scope = 'full' AND dl.comment IS NOT NULL AND dl.comment NOT LIKE v_tag_prefix || '%';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line_comment', n::text);

    UPDATE document_line dl
       SET share_mate_owner_name = CASE WHEN dl.share_mate_owner_person_id IN (SELECT id FROM ap_persons)
                                          OR public.abc_names(dl.share_mate_owner_name) = ANY (v_row_names)
                                        THEN NULL ELSE dl.share_mate_owner_name END,
           share_owner_mate1_name = CASE WHEN public.abc_names(dl.share_owner_mate1_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate1_name END,
           share_owner_mate2_name = CASE WHEN public.abc_names(dl.share_owner_mate2_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate2_name END,
           share_owner_mate3_name = CASE WHEN public.abc_names(dl.share_owner_mate3_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate3_name END,
           share_owner_mate4_name = CASE WHEN public.abc_names(dl.share_owner_mate4_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate4_name END,
           share_owner_mate5_name = CASE WHEN public.abc_names(dl.share_owner_mate5_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate5_name END,
           share_owner_mate6_name = CASE WHEN public.abc_names(dl.share_owner_mate6_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate6_name END,
           share_owner_mate7_name = CASE WHEN public.abc_names(dl.share_owner_mate7_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate7_name END
      FROM ap_share_lines sl WHERE sl.id = dl.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line_room_names', n::text);

    -- Mails: subject and content of every mail to or about them, letters included; never transmitted or read.
    UPDATE mail m SET subject = v_tag, content = v_tag
      FROM ap_mails a
     WHERE a.id = m.id AND (m.subject NOT LIKE v_tag_prefix || '%' OR m.content NOT LIKE v_tag_prefix || '%');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'mail', n::text);

    UPDATE mail m SET from_name = NULL, from_email = NULL
      FROM ap_sender_mails sm WHERE sm.id = m.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'mail_sender', n::text);

    UPDATE recipient r SET name = NULL, email = v_email
      FROM ap_recipients ar WHERE ar.id = r.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'recipient', n::text);

    -- History: request, the comment quoting a mail we tagged, and changes redacted in place.
    UPDATE history h
       SET request = CASE WHEN hs.scope = 'full' AND h.request IS NOT NULL AND h.request NOT LIKE v_tag_prefix || '%'
                          THEN v_tag ELSE h.request END,
           comment = CASE WHEN h.comment IS NOT NULL AND h.comment NOT LIKE v_tag_prefix || '%'
                               AND h.mail_id IN (SELECT id FROM ap_mails)
                          THEN v_tag ELSE hs.new_comment END,
           changes = hs.new_changes,
           username = hs.new_username
      FROM ap_hist hs
     WHERE hs.id = h.id
       AND (   (hs.scope = 'full' AND h.request IS NOT NULL AND h.request NOT LIKE v_tag_prefix || '%')
            OR (h.comment IS NOT NULL AND h.comment NOT LIKE v_tag_prefix || '%' AND h.mail_id IN (SELECT id FROM ap_mails))
            OR hs.new_comment IS DISTINCT FROM h.comment
            OR hs.new_username IS DISTINCT FROM h.username
            -- Only a change in MEANING is written: re-serialising an unchanged array would rewrite every row
            OR (h.changes IS NOT NULL AND hs.new_changes IS DISTINCT FROM h.changes
                AND (   hs.new_changes IS NULL
                     OR NOT pg_input_is_valid(h.changes, 'jsonb')
                     OR hs.new_changes::jsonb IS DISTINCT FROM h.changes::jsonb)));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'history', n::text);

    -- Payments: the comment and what the gateway sent back; the money and the reference stay.
    UPDATE money_transfer mt
       SET comment = CASE WHEN mt.comment IS NOT NULL AND mt.comment NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE mt.comment END,
           gateway_response = NULL, gateway_generate_payment_url_response = NULL
      FROM ap_docs t
     WHERE t.id = mt.document_id AND (t.scope IN ('full', 'contact') OR t.paid)
       AND (   (mt.comment IS NOT NULL AND mt.comment NOT LIKE v_tag_prefix || '%')
            OR mt.gateway_response IS NOT NULL OR mt.gateway_generate_payment_url_response IS NOT NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'money_transfer', n::text);

    -- Chat: their messages anywhere, and every text message and the title of their conversations.
    UPDATE chat_message cm SET content = v_tag
     WHERE cm.kind = 'text' AND cm.content NOT LIKE v_tag_prefix || '%'
       AND (   cm.person_id IN (SELECT id FROM ap_persons)
            OR cm.conversation_id IN (SELECT cv.id FROM conversation cv
                                       WHERE cv.viewer_person_id IN (SELECT id FROM ap_persons)
                                          OR cv.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full')));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'chat_message', n::text);

    UPDATE conversation cv SET title = NULL
     WHERE cv.title IS NOT NULL
       AND (cv.viewer_person_id IN (SELECT id FROM ap_persons) OR cv.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'conversation', n::text);

    -- Invitations: the alias somebody gave them (as invitee). An inviter's own invitations name other people.
    UPDATE invitation i SET alias_first_name = v_first, alias_last_name = v_last
     WHERE (i.invitee_id IN (SELECT id FROM ap_persons) OR i.created_alias_person_id IN (SELECT id FROM ap_persons))
       AND (i.alias_first_name IS DISTINCT FROM v_first OR i.alias_last_name IS DISTINCT FROM v_last);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'invitation', n::text);

    UPDATE list_item li SET comment = v_tag
     WHERE li.comment IS NOT NULL AND li.comment NOT LIKE v_tag_prefix || '%'
       AND (li.person_id IN (SELECT id FROM ap_persons) OR li.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'list_item', n::text);

    UPDATE error er SET message = v_tag, user_agent = NULL, route = NULL
     WHERE er.user_person_id IN (SELECT id FROM ap_persons)
       AND (er.message IS NULL OR er.message NOT LIKE v_tag_prefix || '%' OR er.user_agent IS NOT NULL OR er.route IS NOT NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'error', n::text);

    UPDATE enqueued_request er
       SET request_string = v_tag,
           reply_string = CASE WHEN er.reply_string IS NOT NULL THEN v_tag END
      FROM ap_docs t
     WHERE t.id = er.document_id AND t.scope IN ('full', 'contact') AND er.execution_date IS NOT NULL
       AND (er.request_string NOT LIKE v_tag_prefix || '%' OR (er.reply_string IS NOT NULL AND er.reply_string NOT LIKE v_tag_prefix || '%'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'enqueued_request', n::text);

    -- Deleted outright: nothing points at them that must survive.
    WITH gone AS (
        DELETE FROM push_subscription_recipient psr
         WHERE psr.person_id IN (SELECT id FROM ap_persons)
            OR lower(trim(psr.email)) IN (SELECT address FROM ap_addresses)
            OR psr.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full')
        RETURNING psr.id, psr.subscription_id)
    -- The CTE's deletes are invisible below: a subscription goes only when every recipient it has is one of `gone`
    -- (a device shared by a family keeps the others' registrations).
    DELETE FROM push_subscription ps
     WHERE ps.id IN (SELECT subscription_id FROM gone)
       AND NOT EXISTS (SELECT 1 FROM push_subscription_recipient o
                        WHERE o.subscription_id = ps.id AND o.id NOT IN (SELECT id FROM gone));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'push_subscription', n::text);

    DELETE FROM subscription su WHERE su.person_id IN (SELECT id FROM ap_persons);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'subscription', n::text);

    -- Volunteering applications hold health, criminal-record and immigration answers: deleted, their timeline,
    -- communications, date proposals and assignments with them (ON DELETE CASCADE).
    DELETE FROM volunteering_application va
     WHERE va.person_id IN (SELECT id FROM ap_persons)
        OR (va.person_id IS NULL AND lower(trim(va.email)) IN (SELECT address FROM ap_addresses));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'volunteering_application', n::text);

    -- Mate invitations to their rooms.
    DELETE FROM mate_invite_token mit
     WHERE mit.owner_document_line_id IN (SELECT dl.id FROM document_line dl JOIN ap_docs t ON t.id = dl.document_id AND t.scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'mate_invite_token', n::text);

    -- One marker per booking whose data was removed. username is never 'online': no trigger sends anything.
    INSERT INTO history (document_id, comment, username, user_person_id)
    SELECT t.id, 'Personal data removed (GDPR erasure request)', 'gdpr-erasure', public.kbs_audit_person_id()
      FROM ap_docs t WHERE t.scope IN ('full', 'contact');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('inserted', 'history_marker', n::text);

    -- The run ledger. Once they are anonymised, no run may still tie their id to who it listed: the ticks (a booking
    -- ticked for its contact details keeps the other person's name), a refusal's text naming the person or account
    -- sharing their address, a grid listing candidates, members or namesakes (the runner stores none of those any
    -- more; rows written before it did are cleaned here too). Other people's runs whose refusal names them lose
    -- that text as well. Counts, keys and dates stay.
    UPDATE person_erasure_run r
       SET candidate_document_ids = '{}', member_ids = '{}', error = NULL,
           grid = CASE WHEN jsonb_typeof(r.grid) = 'array'
                       THEN (SELECT coalesce(jsonb_agg(g.e ORDER BY g.o), '[]'::jsonb)
                               FROM jsonb_array_elements(r.grid) WITH ORDINALITY AS g(e, o)
                              WHERE g.e ->> 'step' NOT IN ('candidate', 'member', 'namesake', 'ticks'))
                       ELSE r.grid END
     WHERE r.person_id IN (SELECT id FROM ap_persons);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_erasure_run_own', n::text);
    UPDATE person_erasure_run r
       SET error = CASE WHEN EXISTS (SELECT 1 FROM ap_persons s WHERE r.error ~ ('\mperson ' || s.id || '\M'))
                          OR (v_account_id IS NOT NULL AND r.error ~ ('\maccount ' || v_account_id || '\M'))
                        THEN NULL ELSE r.error END,
           -- a preview of somebody else that ticked their bookings, or them as a member
           candidate_document_ids = CASE WHEN r.candidate_document_ids && ARRAY(SELECT t.id FROM ap_docs t)
                                         THEN '{}'::integer[] ELSE r.candidate_document_ids END,
           member_ids = CASE WHEN r.member_ids && ARRAY(SELECT s.id FROM ap_persons s)
                             THEN '{}'::integer[] ELSE r.member_ids END
     WHERE r.person_id NOT IN (SELECT id FROM ap_persons)
       AND (   (r.error IS NOT NULL
                AND (   EXISTS (SELECT 1 FROM ap_persons s WHERE r.error ~ ('\mperson ' || s.id || '\M'))
                     OR (v_account_id IS NOT NULL AND r.error ~ ('\maccount ' || v_account_id || '\M'))))
            OR r.candidate_document_ids && ARRAY(SELECT t.id FROM ap_docs t)
            OR r.member_ids && ARRAY(SELECT s.id FROM ap_persons s));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_erasure_run_others', n::text);

    -- ================= CHECKS (raise = the caller rolls back) =================
    SELECT count(*) INTO n FROM person q JOIN ap_persons s ON s.id = q.id
     WHERE q.email IS NOT NULL OR q.phone IS NOT NULL OR q.street IS NOT NULL OR q.anonymised_date IS NULL;
    IF n > 0 THEN bad := bad || format(E'\n  person rows still identifying: %s', n); END IF;

    -- Live duplicates were refused; members' and removed rows' copies of the address were cleared
    SELECT count(*) INTO n FROM person q WHERE lower(trim(q.email)) IN (SELECT address FROM ap_core_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  person rows still holding their address: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ap_hist hs ON hs.id = h.id JOIN ap_hist_docs t ON t.id = h.document_id
     WHERE public.anonymise_person_comment(h.comment, v_row_names, t.cared) IS DISTINCT FROM h.comment;
    IF n > 0 THEN bad := bad || format(E'\n  history comments still naming them: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ap_hist hs ON hs.id = h.id
     WHERE lower(trim(h.username)) IN (SELECT a.address FROM ap_addresses a)
        OR (h.username LIKE 'guest:%' AND lower(substr(h.username, 7)) IN (SELECT left(a.address, 58) FROM ap_addresses a));
    IF n > 0 THEN bad := bad || format(E'\n  history rows still naming them as the user: %s', n); END IF;

    IF to_regclass('public.member_email_nullify_backup') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM public.member_email_nullify_backup b
                  WHERE lower(trim(b.email)) IN (SELECT address FROM ap_addresses) OR b.frontend_account_id = $1'
           INTO n USING v_account_id;
        IF n > 0 THEN bad := bad || format(E'\n  backup rows still holding their address: %s', n); END IF;
    END IF;

    SELECT count(*) INTO n FROM frontend_account fa
     WHERE fa.id = v_account_id OR lower(trim(fa.username)) IN (SELECT address FROM ap_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  accounts left: %s', n); END IF;

    SELECT count(*) INTO n FROM document d JOIN ap_docs t ON t.id = d.id AND t.scope IN ('full', 'contact')
     WHERE d.person_email IS NOT NULL OR d.person_phone IS NOT NULL OR d.person_street IS NOT NULL
        OR (t.scope = 'full' AND (d.person_first_name IS DISTINCT FROM v_first OR d.person_last_name IS DISTINCT FROM '#' || coalesce(d.person_id, p_person_id)
                                  OR d.inet_ip IS NOT NULL OR d.person_passport IS NOT NULL));
    IF n > 0 THEN bad := bad || format(E'\n  bookings still identifying: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ap_hist hs ON hs.id = h.id
     WHERE h.changes IS NOT NULL AND EXISTS (SELECT 1 FROM ap_addresses a WHERE position(a.address IN lower(h.changes)) > 0);
    IF n > 0 THEN bad := bad || format(E'\n  history.changes still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ap_hist hs ON hs.id = h.id AND hs.scope = 'full'
     WHERE pg_input_is_valid(h.changes, 'jsonb') AND jsonb_typeof(h.changes::jsonb) = 'array'
       AND EXISTS (SELECT 1 FROM jsonb_array_elements(h.changes::jsonb) e
                    WHERE e ->> '$codec' = 'AddDocumentEvent'
                      -- a name the event never carried is no name left (a booking of an existing person sends
                      -- none: the replay reads it from the document row, anonymised and checked above)
                      AND (   (e ? 'firstName' AND e ->> 'firstName' IS DISTINCT FROM v_first)
                           OR (e ? 'lastName' AND coalesce(e ->> 'lastName', '') NOT LIKE '#%')));
    IF n > 0 THEN bad := bad || format(E'\n  history.changes still holding a name: %s', n); END IF;

    SELECT count(*) INTO n FROM document d WHERE lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  bookings still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM person_account_move m WHERE lower(trim(m.from_account_username)) IN (SELECT address FROM ap_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  account moves still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM document d JOIN ap_docs t ON t.id = d.id AND t.cared
     WHERE public.abc_names(d.person_carer1_name) = ANY (v_row_names) OR public.abc_names(d.person_carer2_name) = ANY (v_row_names);
    IF n > 0 THEN bad := bad || format(E'\n  bookings still naming them as carer: %s', n); END IF;

    SELECT count(*) INTO n FROM recipient r JOIN ap_recipients ar ON ar.id = r.id
     WHERE lower(trim(r.email)) IN (SELECT address FROM ap_addresses) OR r.name IS NOT NULL;
    IF n > 0 THEN bad := bad || format(E'\n  recipients still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM mail m JOIN ap_mails a ON a.id = m.id
     WHERE m.subject NOT LIKE v_tag_prefix || '%' OR m.content NOT LIKE v_tag_prefix || '%';
    IF n > 0 THEN bad := bad || format(E'\n  mails not rewritten: %s', n); END IF;

    SELECT count(*) INTO n FROM magic_link ml
     WHERE lower(trim(ml.email)) IN (SELECT address FROM ap_addresses) OR lower(trim(ml.old_email)) IN (SELECT address FROM ap_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  magic links left: %s', n); END IF;

    SELECT count(*) INTO n FROM auth_session a
     WHERE a.revoked IS NULL AND (a.person_id IN (SELECT id FROM ap_persons) OR (v_account_id IS NOT NULL AND a.account_id = v_account_id));
    IF n > 0 THEN bad := bad || format(E'\n  live sessions left: %s', n); END IF;

    IF bad <> '' THEN
        RAISE EXCEPTION 'Personal data survives the anonymisation of person % [db=%]:% — ROLLBACK and investigate', p_person_id, current_database(), bad;
    END IF;

    SELECT count(*) INTO n FROM mail;
    IF n <> v_mail_rows_before THEN
        RAISE EXCEPTION 'The mail table gained or lost rows (% now, % before) — a trigger fired: ROLLBACK', n, v_mail_rows_before;
    END IF;
    SELECT count(*) INTO n FROM mail WHERE NOT transmitted;
    IF n <> v_mail_queued_before THEN
        RAISE EXCEPTION 'The unsent mail queue changed (% now, % before) — ROLLBACK', n, v_mail_queued_before;
    END IF;

    RETURN QUERY VALUES ('status', 'result', 'anonymisation OK — uncommitted; COMMIT to apply, ROLLBACK for a free dry run');
END
$anon$;

REVOKE ALL ON FUNCTION public.anonymise_person(integer, integer[], integer[], boolean) FROM PUBLIC;

COMMENT ON FUNCTION public.anonymise_person(integer, integer[], integer[], boolean) IS
'GDPR erasure of one customer (V0153): removes everything that identifies the person (and its linked copies) from their account, bookings, history, mails, payments and chat, keeping amounts, dates and gateway references. Requires an explicit REPEATABLE READ transaction and SET LOCAL kbs.erase_confirm = ''ANONYMISE PERSON <id>''. Never commits — the caller reads the grid, then COMMIT or ROLLBACK. Docs: aggregate repo, docs/operations/gdpr-person-erasure-reference.md.';

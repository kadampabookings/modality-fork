-- V0163: anonymise_booker() — the GDPR right to erasure (Art. 17) for somebody who only ever booked WITHOUT an
-- account, started from one of their bookings.
--
-- WHY. anonymise_person() (V0153) erases a customer from their person row. A booking made without an account
-- (document.person_id IS NULL) has no person row: its person_* snapshot is all there is of the booker, so V0153
-- cannot be asked for them. On staging in November 2026, 88,581 such bookings carried 37,700 distinct addresses,
-- 16,089 of them on no person row and no account. The law and the method are V0153's — irreversible anonymisation
-- is erasure (Recital 26), the bookings and payments are accounting records kept under Art. 17(3)(b) — so this
-- REMOVES EVERYTHING THAT IDENTIFIES THE BOOKER AND KEEPS THE FINANCIAL SKELETON, with V0153's helpers
-- (anonymise_person_changes, _comment, _blocking, _blockers). anonymise_person() itself is not touched. The
-- per-request checklist: the aggregate repo's docs/operations/gdpr-person-erasure-reference.md, "A booker without
-- an account".
--
-- WHO IS "THE BOOKER". The person named on the seed booking: its name N (person_abc_names, two words at least) is
-- theirs — the operator checked the requester is that person — and the seed is ALWAYS anonymised in full,
-- whatever it carries. Its address A is PROBABLY theirs: a booking's address is typed, by whoever made the booking
-- (a parent's on a child's booking, an organiser's), unlike a customer's own row. So:
--   * another booking without an account with A AND N: theirs, in full, without a tick (A must be present: two
--     bookings without an address are not tied by it);
--   * A under another name: a CANDIDATE (action 'contact', match 'email') — ticked, that booking loses its contact
--     details; offered only when A is FREE (below);
--   * N under another or no address: a CANDIDATE removed in full if ticked;
--   * a CUSTOMER's own booking carrying A and N (made under a parent's account, mostly — 957 on kbs_local in November
--     2026): a CANDIDATE removed in full if ticked; the booking stays on that account, whose row is not touched;
--   * the seed's phone (and the A+N bookings'): a CANDIDATE, contact details only; a phone on more than 5 other
--     bookings made without an account is shared and not matched on, as in V0153;
--   * N typed as carer on a booking tied to them (theirs, or one carrying A when A is theirs): cleared without a
--     tick; elsewhere a CANDIDATE (action 'carer'). There is no "nobody else bears the name" shortcut as in V0153:
--     other bookers without an account bear names no person row shows. A carer slot or a room-owner slot that
--     points at one of their bookings BY ID (document.person_carerN_document_id, document_line.
--     share_mate_owner_document_line_id) is theirs whatever name it shows, and is cleared — with that name in the
--     booking's history. Their name typed as carer on a customer's row is left alone: nothing ties it to them.
-- THE ADDRESS. A is OWNED when a person row (any name), an account login, a person's volunteering application or
-- a customer's own booking holds it: it is somebody else's too, and stays theirs everywhere — only this booker's
-- bookings lose it (they are anonymised in full), and the owners are listed (step 'addressOwner', ids) so staff
-- can switch to Anonymise customer if it is in fact the same person. Otherwise A is FREE, and it is THEIRS — every
-- address-wide step runs (magic links, mails and recipients by address, push subscriptions, anonymous
-- volunteering applications, history usernames) — only when every booking under A with another name is ticked.
-- Until then (step booker/address = 'shared') it stays on the unticked ones; a volunteering application made without
-- an account under A in another name keeps it 'shared' too (it is somebody else's, never touched). A booking's OWN
-- addresses — the one it carries and those its history shows it was booked under — go from its own mails'
-- recipients and senders and from its own history's username whatever A's state: those rows describe that booking,
-- and V0153 leaves them behind on a full candidate under an address that is not the person's. A recipient of their
-- bookings' mails bearing N goes too, whatever its address.
-- THEIR VOLUNTEERING APPLICATIONS are those made without an account under A in their name, or tied to one of their
-- bookings: deleted. Their photos are files outside the database that nothing leads to once the row is gone, so the
-- grid lists them (step 'photo', never stored) for staff to delete.
-- THE BOOKER HAS A CUSTOMER RECORD when a person row named N (not anonymised; a linked copy stands for the person
-- it copies) holds A, sits on an account whose login is A or on which a live row holds A, or has a booking of its
-- own carrying A and N: that is the same person, refused below — anonymise that customer instead (V0153). The same
-- test runs again for every booking anonymised in full, under every address it carries or carried: a ticked name
-- candidate booked under the address of a customer bearing their name is that customer's. On
-- staging in November 2026 about 40,000 of the 88,581 bookings without an account were in that case (38,102 with a
-- live row's address and name, 2,222 more with the name of a member of that row's account).
-- OTHER CUSTOMERS NAMED N (step 'namesake') are listed, never touched, as in V0153.
--
-- WHAT STOPS IT (each raises '[Key] text', the key mapped by the back office; nothing is written):
--   PersonAnonymiseNotFoundError         no such booking
--   PersonAnonymiseAlreadyDoneError      its booker was anonymised before (names 'Anonymised' / '#...', no address)
--   PersonAnonymiseBookerHasAccountError the booking belongs to a person: anonymise that customer
--   PersonAnonymiseBookerIsCustomerError the booker, or a ticked candidate, has a customer record (above): 'person <id>' each
--   PersonAnonymiseActiveBookingsError   one of their bookings (in full) is not cancelled and not over
--   PersonAnonymiseBalanceError          one of those has a balance either way
--   PersonAnonymiseQueuedMailError       a mail to or about them is still waiting to be sent (last 30 days)
--   PersonAnonymiseQueuedPayloadsError   an un-executed KBS2 request still holds one of their bookings
--   PersonAnonymiseCandidateError        a ticked booking is not one of their candidates (a preview drops it)
--   PersonAnonymiseUnknownReferenceError a foreign key to document this version does not know about
--
-- WHAT IT DOES. The tag is V0153's; a NOT NULL name becomes 'Anonymised' / '#B<seed id>', a NOT NULL address
-- 'anonymised-b<seed id>@invalid'. As V0153 does for the same rows: the bookings ('full': the person_* snapshot,
-- IP, geocodes, free text; 'contact': address, phone, street; 'carer': the slot naming them), the carts' links and
-- magic link (not a cart shared with someone else's booking), line comments and room-owner/mate names, mails (letters
-- included) and their recipients and senders, history (request, changes redacted in place, comments, usernames,
-- one marker per booking), payments' comments and gateway replies (amounts and transaction_ref kept), chat of
-- their bookings, list comments, executed KBS2 payloads, magic links and push subscriptions of their address (when
-- theirs), their volunteering applications (above), mate invitations to their rooms.
-- No person row, account or session is touched: a booker has none.
--
-- THE LEDGER. Runs are rows of person_erasure_run (V0153) keyed person_id = -<seed booking id>, document_id =
-- the seed: V0153's frozen scrub reaches other runs only WHERE person_id NOT IN (...), which a NULL key never
-- satisfies, so a negative key lets an anonymise_person() commit clear a booker run's ticks and refusal texts too.
-- anonymise_booker_ledger_tidy() clears the booker runs whose seed an anonymise_person() run anonymised (V0153
-- cannot know them); the runner calls it in every commit's transaction. This function clears its own runs, every
-- run whose ticks touch its bookings, and refusal texts naming 'booking <id>' of them.
--
-- CHECKS before it returns (any failure raises: the caller rolls back): every foreign key into document is one this
-- version handles; afterwards no booking in scope identifies anybody, no address of theirs and no booking's own
-- address is left in the rows it touched, no carer slot or name they held survives, redacting the history again
-- would change nothing, no history change array gained a null element, none of their applications is left, the mail
-- table gained or lost no row and the unsent queue did not change.
--
-- TRIGGERS. None disabled. The document triggers fire as for V0153's bookings without an account (set_abc_names
-- recomputes from the pseudonym; record_changes_for_notification writes id-only rows); no history row is inserted
-- with username 'online', no mail row is inserted, transmitted or read.
--
-- INTERLOCKS, as V0153: SET LOCAL kbs.erase_confirm = 'ANONYMISE BOOKER <seed booking id>'; a REPEATABLE READ
-- transaction; the call a LATER statement of it; EXECUTE revoked from PUBLIC. It never commits. The back office
-- runs it through modality-event-server-lifecycle-plugin (PersonAnonymisationRunner), Data Protection Officers only.
--
-- THE DataProtection OPERATION. Seeded below: the operation a "Data Protection Officer" role carries. The anonymisation
-- endpoints (this function's and anonymise_person()'s) ask for it through RoleOperationMembership.holdsThroughRole --
-- an organisation-wide, not read-only access row -- and, unlike every RouteAccessGuard check, a super administrator
-- does NOT pass without it (David, 2026-11-06 and 2026-11-17: strictly DPO). Granted to no one here: a super
-- administrator builds the role in Authorizations and assigns it, and that grant is visible there. NO grant_route: it
-- gates endpoints, not a page -- the browser asks the server (modality/service/dpo/access) rather than reading its
-- own grant list, where a super administrator's 'operation:*' would pass. frontend = false for the same reason.
-- Set kbs.audit_person_id to the staff member's person id first.
--
-- SHIPPED = FROZEN. Never edit once applied; a change ships as a new version. The known-references list below must
-- follow every migration that adds a foreign key to document.
--
-- ANONYMISER. person_erasure_run.document_id is covered by the table's classification in the aggregate repo's
-- scripts/gdpr-anonymise/10-anon-helpers.sql.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

-- ===== The DataProtection operation (see above) =================================================================

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT 'DataProtection', 'Data Protection Officer (GDPR)',
       true, false, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'DataProtection');

-- ===== person_erasure_run: runs started from a booking ==========================================================

ALTER TABLE public.person_erasure_run ADD COLUMN IF NOT EXISTS document_id integer;

ALTER TABLE public.person_erasure_run DROP CONSTRAINT IF EXISTS person_erasure_run_subject_chk;
ALTER TABLE public.person_erasure_run ADD CONSTRAINT person_erasure_run_subject_chk
    CHECK ((document_id IS NULL AND person_id > 0) OR (document_id IS NOT NULL AND person_id = -document_id));

COMMENT ON TABLE public.person_erasure_run IS
    'Customer anonymisation runs started from the back office: one row per anonymise_person() (V0153) or anonymise_booker() (V0163) run, preview or commit. Counts, booking ids and event facts, never personal data. Not in the domain model: the anonymisation endpoints of modality-event-server-lifecycle-plugin (Data Protection Officers only) are the only way in.';
COMMENT ON COLUMN public.person_erasure_run.person_id IS
    'The person the run acted on; for a run started from a booking made without an account (anonymise_booker, V0163), MINUS that booking''s id (document_id): V0153''s scrub of other runs, frozen, reaches only rows WHERE person_id NOT IN (...), which a NULL would never satisfy. No foreign key: the record outlives whatever happens to the row.';
COMMENT ON COLUMN public.person_erasure_run.document_id IS
    'For a run started from a booking made without an account (anonymise_booker, V0163): that booking (person_id is then its negative). NULL for a customer''s run. No foreign key.';

-- ===== anonymise_booker_ledger_tidy() ===========================================================================

-- The booker runs whose seed booking has since been anonymised — by anonymise_person() too, which takes such a
-- booking as one of the customer's and cannot know a booker run was started from it — keep counts, keys and dates
-- only: their ticks (other people's bookings), refusal texts (the customers they named) and listed steps go, as
-- V0153 does for its own runs. The runner calls it in every commit's transaction; the reference doc's manual
-- recipe after every manual commit. Returns how many runs it changed.
CREATE OR REPLACE FUNCTION public.anonymise_booker_ledger_tidy()
RETURNS integer
LANGUAGE sql
AS $tidy$
    WITH tidied AS (
        UPDATE public.person_erasure_run r
           SET candidate_document_ids = '{}', member_ids = '{}', error = NULL,
               grid = CASE WHEN jsonb_typeof(r.grid) = 'array'
                           THEN (SELECT coalesce(jsonb_agg(g.e ORDER BY g.o), '[]'::jsonb)
                                   FROM jsonb_array_elements(r.grid) WITH ORDINALITY AS g(e, o)
                                  WHERE g.e ->> 'step' NOT IN ('candidate', 'member', 'namesake', 'addressOwner', 'ticks'))
                           ELSE r.grid END
          FROM public.document d
         WHERE d.id = r.document_id
           AND d.person_first_name = 'Anonymised' AND d.person_last_name LIKE '#%' AND d.person_email IS NULL
           AND (   cardinality(r.candidate_document_ids) > 0 OR cardinality(r.member_ids) > 0 OR r.error IS NOT NULL
                OR (jsonb_typeof(r.grid) = 'array'
                    AND EXISTS (SELECT 1 FROM jsonb_array_elements(r.grid) g
                                 WHERE g ->> 'step' IN ('candidate', 'member', 'namesake', 'addressOwner', 'ticks'))))
        RETURNING 1)
    SELECT count(*)::integer FROM tidied
$tidy$;

REVOKE ALL ON FUNCTION public.anonymise_booker_ledger_tidy() FROM PUBLIC;

-- ===== anonymise_booker_customers() =============================================================================

-- The customer records of the person named p_name (as public.abc_names() spells it) under one of p_addresses: a person
-- row bearing the name that holds one of the addresses, sits on an account whose login is one or on which a live row
-- holds one, or has a booking of its own carrying one. 'person <id>' each, the form V0153's ledger scrub and the back
-- office read (a linked copy stands for the person it copies); NULL when there is none.
CREATE OR REPLACE FUNCTION public.anonymise_booker_customers(p_name text, p_addresses text[])
RETURNS text
LANGUAGE sql STABLE
AS $customers$
    SELECT string_agg('person ' || x.pid, ', ' ORDER BY x.pid)
      FROM (SELECT DISTINCT coalesce(q.account_person_id, q.id) AS pid
              FROM person q
             WHERE q.anonymised_date IS NULL AND q.abc_names = p_name
               AND (   lower(trim(q.email)) = ANY (p_addresses)
                    OR q.frontend_account_id IN (SELECT fa.id FROM frontend_account fa WHERE lower(trim(fa.username)) = ANY (p_addresses))
                    OR q.frontend_account_id IN (SELECT o.frontend_account_id FROM person o
                                                  WHERE NOT o.removed AND o.frontend_account_id IS NOT NULL
                                                    AND lower(trim(o.email)) = ANY (p_addresses))
                    OR EXISTS (SELECT 1 FROM document d WHERE d.person_id = q.id AND lower(trim(d.person_email)) = ANY (p_addresses)))) x
$customers$;

REVOKE ALL ON FUNCTION public.anonymise_booker_customers(text, text[]) FROM PUBLIC;

-- ===== anonymise_booker() =======================================================================================

CREATE OR REPLACE FUNCTION public.anonymise_booker(p_document_id integer,
                                                   p_candidate_document_ids integer[] DEFAULT '{}'::integer[],
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
    b             record;
    v_address     text;
    v_name        text;
    v_names       text[];
    v_owned       boolean := false;
    v_other_applications bigint := 0;
    v_theirs      boolean := false;
    v_address_state text;
    v_addresses   text[];
    v_shared_phones bigint;
    v_text        text;
    v_mail_rows_before   bigint;
    v_mail_queued_before bigint;
    n             bigint;
    bad           text := '';
BEGIN
    v_tag := v_tag_prefix || ' on ' || current_date || ']';

    -- ================= INTERLOCKS =================
    IF p_document_id IS NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseNotFoundError] the booking id is NULL';
    END IF;
    IF current_setting('transaction_isolation') NOT IN ('repeatable read', 'serializable') THEN
        RAISE EXCEPTION 'anonymise_booker must run in a REPEATABLE READ transaction — start with: BEGIN ISOLATION LEVEL REPEATABLE READ;';
    END IF;
    IF transaction_timestamp() = statement_timestamp() THEN
        RAISE EXCEPTION 'anonymise_booker refuses to run as a single autocommit statement — it would commit with no review. BEGIN ISOLATION LEVEL REPEATABLE READ; SET LOCAL kbs.erase_confirm = ...; SELECT * FROM anonymise_booker(...); then COMMIT or ROLLBACK, each statement sent separately';
    END IF;
    IF v_confirm IS DISTINCT FROM ('ANONYMISE BOOKER ' || p_document_id) THEN
        RAISE EXCEPTION 'anonymise_booker: missing statement of intent — run  SET LOCAL kbs.erase_confirm = ''ANONYMISE BOOKER %'';  in this transaction first (found: %)',
                        p_document_id, coalesce(v_confirm, '<unset>');
    END IF;

    -- ================= THE SEED BOOKING =================
    SELECT d.id, d.ref, d.person_id, d.person_first_name, d.person_last_name, d.person_email, d.person_abc_names,
           d.person_phone
      INTO b FROM document d WHERE d.id = p_document_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION '[PersonAnonymiseNotFoundError] booking % does not exist', p_document_id;
    END IF;
    -- The pseudonym AND no address: a name anybody can type is no proof the erasure happened.
    IF b.person_first_name = 'Anonymised' AND b.person_last_name LIKE '#%' AND b.person_email IS NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseAlreadyDoneError] the booker of booking % was anonymised already', p_document_id;
    END IF;
    IF b.person_id IS NOT NULL THEN
        -- a linked copy stands for the person it copies: that is the one Anonymise customer accepts
        SELECT coalesce(q.account_person_id, q.id) INTO n FROM person q WHERE q.id = b.person_id;
        RAISE EXCEPTION '[PersonAnonymiseBookerHasAccountError] booking % belongs to person %: anonymise that customer instead',
                        p_document_id, coalesce(n, b.person_id);
    END IF;

    -- Deferred triggers fired at COMMIT read this session temp table; a safety net, as in V0077.
    PERFORM set_transaction_parameters(true);

    -- The pseudonym of every booking anonymised in full: all of them are bookings without an account, so it never
    -- depends on document.person_id ('#' || NULL would be NULL, and jsonb_set(..., NULL) a null array element).
    v_last  := '#B' || p_document_id;
    v_email := 'anonymised-b' || p_document_id || '@invalid';
    v_address := CASE WHEN position('@' IN coalesce(trim(b.person_email), '')) > 1 THEN lower(trim(b.person_email)) END;
    -- Two words at least, as V0153: a single word matches too many people to be a match.
    v_name := CASE WHEN position(' ' IN trim(coalesce(b.person_abc_names, ''))) > 0 THEN b.person_abc_names END;
    v_names := CASE WHEN v_name IS NOT NULL THEN ARRAY[v_name] ELSE '{}'::text[] END;

    -- Every foreign key into document must be one this version handles (or knows to be an id that may stay).
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_text
      FROM (SELECT format('%s.%s', c.relname, a.attname) AS k
              FROM pg_constraint con
              JOIN pg_class c ON c.oid = con.conrelid
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
             WHERE con.contype = 'f' AND con.confrelid = 'public.document'::regclass) fk
     WHERE k <> ALL (ARRAY[
        -- handled: rewritten or deleted below
        'history.document_id', 'mail.document_id', 'money_transfer.document_id', 'document_line.document_id',
        'conversation.document_id', 'list_item.document_id', 'enqueued_request.document_id',
        'push_subscription_recipient.document_id', 'volunteering_application.document_id',
        'document.person_carer1_document_id', 'document.person_carer2_document_id',
        -- ids only, no text about the booker
        'document.group_document_id']);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseUnknownReferenceError] foreign keys this version does not handle: % — ship a new version of anonymise_booker() first', v_text;
    END IF;

    -- ================= A CUSTOMER RECORD OF THE SAME PERSON =================
    -- Their name AND their address on a person row (or the account it hangs off), or on a booking of a customer
    -- bearing their name: that is a customer, whom Anonymise customer erases with this booking. 'person <id>' each,
    -- the form V0153's ledger scrub looks for.
    IF v_address IS NOT NULL AND v_name IS NOT NULL THEN
        v_text := public.anonymise_booker_customers(v_name, ARRAY[v_address]);
        IF v_text IS NOT NULL THEN
            RAISE EXCEPTION '[PersonAnonymiseBookerIsCustomerError] the booker of booking % has a customer record: % — anonymise that customer instead',
                            p_document_id, v_text;
        END IF;
    END IF;

    -- ================= THE ADDRESS =================
    DROP TABLE IF EXISTS ab_address_owners, ab_phones, ab_candidates, ab_addresses, ab_carer_docs, ab_docs,
                         ab_doc_addresses, ab_recipients, ab_sender_mails, ab_mails, ab_events, ab_share_lines,
                         ab_email_docs, ab_hist_docs, ab_hist, ab_namesakes, ab_linked_names, ab_applications;

    -- Who else holds A: listed (ids; the names travel in the endpoint's reply, never in this grid). An account
    -- without an owner row still makes A somebody else's (v_owned), with nobody to list.
    CREATE TEMP TABLE ab_address_owners (person_id integer PRIMARY KEY, via text NOT NULL);
    IF v_address IS NOT NULL THEN
        INSERT INTO ab_address_owners
        SELECT DISTINCT coalesce(q.account_person_id, q.id), 'person' FROM person q
         WHERE q.anonymised_date IS NULL AND lower(trim(q.email)) = v_address
        ON CONFLICT DO NOTHING;
        INSERT INTO ab_address_owners
        SELECT DISTINCT q.id, 'account' FROM frontend_account fa JOIN person q ON q.frontend_account_id = fa.id AND q.owner
         WHERE lower(trim(fa.username)) = v_address
        ON CONFLICT DO NOTHING;
        INSERT INTO ab_address_owners
        SELECT DISTINCT va.person_id, 'volunteering' FROM volunteering_application va
         WHERE va.person_id IS NOT NULL AND lower(trim(va.email)) = v_address
        ON CONFLICT DO NOTHING;
        INSERT INTO ab_address_owners
        SELECT DISTINCT coalesce(q.account_person_id, q.id), 'booking' FROM document d JOIN person q ON q.id = d.person_id
         WHERE lower(trim(d.person_email)) = v_address
           -- one carrying their name too is theirs, made under somebody's account: a candidate (below)
           AND NOT coalesce(d.person_abc_names = v_name, false)
        ON CONFLICT DO NOTHING;
        v_owned := EXISTS (SELECT 1 FROM ab_address_owners)
                   OR EXISTS (SELECT 1 FROM frontend_account fa WHERE lower(trim(fa.username)) = v_address);
        -- A volunteering application made without an account under A in another name is somebody else's: it is never
        -- touched, and A, which it still carries, is then never theirs.
        SELECT count(*) INTO v_other_applications FROM volunteering_application va
         WHERE va.person_id IS NULL AND lower(trim(va.email)) = v_address
           AND NOT coalesce(public.abc_names(va.first_name || ' ' || va.last_name) = v_name, false);
    END IF;

    -- ================= PHONES =================
    -- The seed's and the A+N bookings', as the last 9 digits (V0153). A phone on more than 5 OTHER bookings made
    -- without an account is a shared number (a centre's, a family's) and is set aside.
    CREATE TEMP TABLE ab_phones (phone text PRIMARY KEY, guest_bookings bigint);
    INSERT INTO ab_phones (phone)
    SELECT DISTINCT right(dg, 9) FROM (
        SELECT regexp_replace(b.person_phone, '\D', '', 'g') AS dg
        UNION
        SELECT regexp_replace(d.person_phone, '\D', '', 'g') FROM document d
         WHERE d.person_id IS NULL AND v_address IS NOT NULL AND v_name IS NOT NULL
           AND lower(trim(d.person_email)) = v_address AND d.person_abc_names = v_name
    ) t WHERE length(coalesce(dg, '')) >= 9;
    UPDATE ab_phones ph SET guest_bookings = c.n
      FROM (SELECT right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) AS phone, count(*) AS n
              FROM document d
             WHERE d.person_id IS NULL AND d.id <> p_document_id
               AND length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
               AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ab_phones)
               -- coalesce: a booking without an address must count, not vanish into NOT NULL
               AND NOT coalesce(v_address IS NOT NULL AND v_name IS NOT NULL
                                AND lower(trim(d.person_email)) = v_address AND d.person_abc_names = v_name, false)
             GROUP BY 1) c
     WHERE c.phone = ph.phone;
    DELETE FROM ab_phones WHERE guest_bookings > 5;
    GET DIAGNOSTICS v_shared_phones = ROW_COUNT;

    -- ================= CANDIDATES =================
    -- Other bookings made without an account that match them. by_email: A, under their name, or under any name
    -- while A is free (owned, A under another name is somebody else's booking, not a match).
    CREATE TEMP TABLE ab_candidates AS
    SELECT d.id, d.ref, d.event_id, d.person_id AS customer_id,
           coalesce(v_address IS NOT NULL AND lower(trim(d.person_email)) = v_address
                    AND (NOT v_owned OR d.person_abc_names = v_name), false) AS by_email,
           coalesce(d.person_abc_names = v_name, false) AS by_name,
           (length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
            AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ab_phones)) AS by_phone
      FROM document d
     WHERE d.id <> p_document_id
       AND (   (d.person_id IS NULL
                AND (   (v_address IS NOT NULL AND lower(trim(d.person_email)) = v_address)
                     OR d.person_abc_names = v_name
                     OR (length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
                         AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ab_phones))))
            -- a customer's own booking made under their name and address (a parent's account booking for them, or
            -- for the same human under another name: the customer-record test below refuses a row bearing N)
            OR (d.person_id IS NOT NULL AND v_address IS NOT NULL AND v_name IS NOT NULL
                AND lower(trim(d.person_email)) = v_address AND d.person_abc_names = v_name));
    DELETE FROM ab_candidates WHERE NOT (by_email OR by_name OR by_phone);
    ALTER TABLE ab_candidates ADD COLUMN action text, ADD COLUMN carer_match boolean NOT NULL DEFAULT false;
    -- on a customer's account = perhaps them, in full if ticked (the booking stays on that account); email + name =
    -- them, in full, no tick; email alone = the same address under another name (staff tick: the
    -- address may be theirs, or a parent's typed on their booking); name = perhaps them under another address;
    -- phone alone = perhaps a shared phone.
    UPDATE ab_candidates SET action = CASE WHEN customer_id IS NOT NULL THEN 'full'
                                           WHEN by_email AND by_name THEN 'auto'
                                           WHEN by_email THEN 'contact'
                                           WHEN by_name THEN 'full'
                                           ELSE 'contact' END;
    ALTER TABLE ab_candidates ADD PRIMARY KEY (id);

    -- A is theirs when it is free and every other booking carrying it is ticked (another name, or a customer's). Ticks only make it truer,
    -- and the candidates above do not depend on it: the same ticks on the same data give the same answer, so a
    -- commit with its preview's ticks reproduces the preview.
    v_theirs := v_address IS NOT NULL AND NOT v_owned AND v_other_applications = 0
                AND NOT EXISTS (SELECT 1 FROM ab_candidates c
                                 WHERE c.by_email AND c.action IN ('contact', 'full') AND NOT coalesce(c.id = ANY (v_ticked), false));
    v_address_state := CASE WHEN v_address IS NULL THEN 'none' WHEN v_owned THEN 'owned'
                            WHEN v_theirs THEN 'theirs' ELSE 'shared' END;
    CREATE TEMP TABLE ab_addresses (address text PRIMARY KEY);
    IF v_theirs THEN
        INSERT INTO ab_addresses VALUES (v_address);
    END IF;
    ANALYZE ab_addresses;
    -- Read only now: the address steps and the history redaction below see A exactly when it is theirs.
    SELECT coalesce(array_agg(address), '{}'::text[]) INTO v_addresses FROM ab_addresses;

    -- Carer names are typed (V0153). Narrowed first to bookings that carry one: public.abc_names() is plpgsql.
    -- Tied = the booking is theirs (the seed, A+N), or carries their address when it is theirs; otherwise a
    -- namesake may be the carer, and the booking is a candidate.
    CREATE TEMP TABLE ab_carer_docs AS
    SELECT c.id
      FROM (SELECT x.id, public.abc_names(x.n1) AS n1, public.abc_names(x.n2) AS n2
              FROM (SELECT d.id, d.person_carer1_name AS n1, d.person_carer2_name AS n2 FROM document d
                     WHERE (d.person_carer1_name IS NOT NULL OR d.person_carer2_name IS NOT NULL)
                       AND cardinality(v_names) > 0
                    OFFSET 0) x
            OFFSET 0) c
     WHERE c.n1 = ANY (v_names) OR c.n2 = ANY (v_names);
    ALTER TABLE ab_carer_docs ADD COLUMN tied boolean;
    UPDATE ab_carer_docs cd SET tied = cd.id = p_document_id
        OR EXISTS (SELECT 1 FROM ab_candidates c WHERE c.id = cd.id AND c.action = 'auto')
        OR EXISTS (SELECT 1 FROM document d WHERE d.id = cd.id AND lower(trim(d.person_email)) IN (SELECT address FROM ab_addresses));
    UPDATE ab_candidates c SET carer_match = true FROM ab_carer_docs cd WHERE cd.id = c.id AND NOT cd.tied;
    INSERT INTO ab_candidates (id, ref, event_id, by_email, by_name, by_phone, action, carer_match)
    SELECT d.id, d.ref, d.event_id, false, true, false, 'carer', true
      FROM ab_carer_docs cd JOIN document d ON d.id = cd.id
     WHERE NOT cd.tied
    ON CONFLICT (id) DO NOTHING;

    -- A preview (lenient) drops a tick that is no longer a candidate; a commit refuses it.
    IF p_lenient THEN
        v_ticked := ARRAY(SELECT DISTINCT x FROM unnest(v_ticked) AS x
                           WHERE EXISTS (SELECT 1 FROM ab_candidates c WHERE c.id = x AND c.action IN ('full', 'contact', 'carer'))
                           ORDER BY x);
    END IF;
    SELECT string_agg(coalesce(x::text, 'NULL'), ', ') INTO v_text
      FROM unnest(v_ticked) AS x
     WHERE x IS NULL OR NOT EXISTS (SELECT 1 FROM ab_candidates c WHERE c.id = x AND c.action IN ('full', 'contact', 'carer'));
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseCandidateError] bookings % are not candidates of booking %', v_text, p_document_id;
    END IF;

    -- The bookings, each in one scope; cared = a carer slot of it names them (that slot is cleared).
    CREATE TEMP TABLE ab_docs (id integer PRIMARY KEY, scope text NOT NULL, cared boolean NOT NULL DEFAULT false);
    INSERT INTO ab_docs VALUES (p_document_id, 'full');
    INSERT INTO ab_docs SELECT c.id, 'full' FROM ab_candidates c
     WHERE c.action = 'auto' OR (c.action = 'full' AND c.id = ANY (v_ticked)) ON CONFLICT DO NOTHING;
    INSERT INTO ab_docs SELECT c.id, 'contact' FROM ab_candidates c
     WHERE c.action = 'contact' AND c.id = ANY (v_ticked) ON CONFLICT DO NOTHING;
    INSERT INTO ab_docs (id, scope, cared)
    SELECT cd.id, 'carer', true FROM ab_carer_docs cd WHERE cd.tied OR cd.id = ANY (v_ticked)
    ON CONFLICT (id) DO UPDATE SET cared = true;
    -- A carer slot pointing at one of their bookings by id is theirs, whatever name it shows.
    INSERT INTO ab_docs (id, scope, cared)
    SELECT d.id, 'carer', true FROM document d
     WHERE d.person_carer1_document_id = ANY (ARRAY(SELECT t.id FROM ab_docs t WHERE t.scope = 'full'))
        OR d.person_carer2_document_id = ANY (ARRAY(SELECT t.id FROM ab_docs t WHERE t.scope = 'full'))
    ON CONFLICT (id) DO UPDATE SET cared = true;
    ANALYZE ab_docs;

    -- Each booking's own address, read before anything is written: it goes from that booking's own mails and
    -- history whatever A's state (those rows describe that booking).
    CREATE TEMP TABLE ab_doc_addresses (id integer NOT NULL, address text NOT NULL, PRIMARY KEY (id, address));
    INSERT INTO ab_doc_addresses
    SELECT d.id, lower(trim(d.person_email)) FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope IN ('full', 'contact')
     WHERE position('@' IN coalesce(trim(d.person_email), '')) > 1;
    -- ... and the addresses it was booked under before (changed since): its own history still shows them
    INSERT INTO ab_doc_addresses
    SELECT DISTINCT h.document_id, lower(trim(e ->> 'email'))
      FROM ab_docs t JOIN history h ON h.document_id = t.id
     CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN pg_input_is_valid(h.changes, 'jsonb')
                                                  THEN CASE WHEN jsonb_typeof(h.changes::jsonb) = 'array' THEN h.changes::jsonb END
                                             END) e
     WHERE t.scope IN ('full', 'contact') AND e ->> '$codec' = 'AddDocumentEvent'
       AND position('@' IN coalesce(trim(e ->> 'email'), '')) > 1
    ON CONFLICT DO NOTHING;
    ANALYZE ab_doc_addresses;

    -- The customer-record test again, for every booking anonymised in full under every address it carries or
    -- carried: a ticked name candidate made under the address of a customer bearing their name is that customer.
    IF v_name IS NOT NULL THEN
        v_text := public.anonymise_booker_customers(v_name, ARRAY(SELECT DISTINCT da.address FROM ab_doc_addresses da
                                                                    JOIN ab_docs t ON t.id = da.id AND t.scope = 'full'));
        IF v_text IS NOT NULL THEN
            RAISE EXCEPTION '[PersonAnonymiseBookerIsCustomerError] the booker of booking % has a customer record: % — anonymise that customer instead',
                            p_document_id, v_text;
        END IF;
    END IF;

    -- Volunteering applications made without an account that are theirs: under A in their name, or tied to one of
    -- their bookings. Deleted below; their photos (files outside the database) are listed for staff to delete.
    CREATE TEMP TABLE ab_applications AS
    SELECT va.id, nullif(va.photo, '') AS photo FROM volunteering_application va
     WHERE va.person_id IS NULL
       AND (   (v_address IS NOT NULL AND v_name IS NOT NULL AND lower(trim(va.email)) = v_address
                AND coalesce(public.abc_names(va.first_name || ' ' || va.last_name) = v_name, false))
            OR va.document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full'));

    -- ================= REFUSALS (before any write) =================
    SELECT string_agg(DISTINCT coalesce(x.ref::text, 'id ' || x.document_id), ', ') INTO v_text
      FROM public.anonymise_person_blocking(ARRAY(SELECT t.id FROM ab_docs t WHERE t.scope = 'full')) x
     WHERE x.kind = 'active';
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseActiveBookingsError] the booker of booking % has bookings that are not over (refs %): cancel them, or wait until they end', p_document_id, v_text;
    END IF;

    SELECT string_agg(DISTINCT coalesce(x.ref::text, 'id ' || x.document_id), ', ') INTO v_text
      FROM public.anonymise_person_blocking(ARRAY(SELECT t.id FROM ab_docs t WHERE t.scope = 'full')) x
     WHERE x.kind = 'balance';
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseBalanceError] the booker of booking % has bookings with money still owed either way (refs %): settle them first', p_document_id, v_text;
    END IF;

    SELECT count(*) INTO n FROM enqueued_request er JOIN ab_docs t ON t.id = er.document_id AND t.scope IN ('full', 'contact')
     WHERE er.execution_date IS NULL;
    IF n > 0 THEN
        RAISE EXCEPTION '[PersonAnonymiseQueuedPayloadsError] % un-executed KBS2 requests still hold bookings of the booker of booking %', n, p_document_id;
    END IF;

    -- The mails to or about them: of their bookings ('full', 'contact'), to or from their address (only when it is
    -- theirs: recipient and mail are the biggest tables after history, scanned once), and the recipients and
    -- senders of their bookings' own mails under that booking's own address.
    CREATE TEMP TABLE ab_recipients (id integer PRIMARY KEY, mail_id integer);
    CREATE TEMP TABLE ab_sender_mails (id integer PRIMARY KEY);
    IF v_theirs THEN
        INSERT INTO ab_recipients
        SELECT r.id, r.mail_id FROM recipient r WHERE lower(trim(r.email)) IN (SELECT address FROM ab_addresses);
        INSERT INTO ab_sender_mails
        SELECT m.id FROM mail m WHERE lower(trim(m.from_email)) IN (SELECT address FROM ab_addresses);
    END IF;
    INSERT INTO ab_recipients
    SELECT r.id, r.mail_id FROM ab_doc_addresses da JOIN mail m ON m.document_id = da.id JOIN recipient r ON r.mail_id = m.id
     WHERE lower(trim(r.email)) = da.address
    ON CONFLICT DO NOTHING;
    INSERT INTO ab_sender_mails
    SELECT m.id FROM ab_doc_addresses da JOIN mail m ON m.document_id = da.id
     WHERE lower(trim(m.from_email)) = da.address
    ON CONFLICT DO NOTHING;
    -- a recipient of their own bookings' mails bearing their name, under whatever address
    IF cardinality(v_names) > 0 THEN
        INSERT INTO ab_recipients
        SELECT r.id, r.mail_id FROM ab_docs t JOIN mail m ON m.document_id = t.id AND t.scope = 'full'
          JOIN recipient r ON r.mail_id = m.id
         WHERE r.name IS NOT NULL AND public.abc_names(r.name) = ANY (v_names)
        ON CONFLICT DO NOTHING;
    END IF;
    ANALYZE ab_recipients;
    CREATE TEMP TABLE ab_mails (id integer PRIMARY KEY);
    INSERT INTO ab_mails
    SELECT m.id FROM mail m JOIN ab_docs t ON t.id = m.document_id AND t.scope IN ('full', 'contact')
    UNION SELECT r.mail_id FROM ab_recipients r WHERE r.mail_id IS NOT NULL
    UNION SELECT sm.id FROM ab_sender_mails sm;
    ANALYZE ab_mails;

    -- Still waiting to be sent: rewriting it would send the tag (V0153).
    SELECT count(*) INTO n FROM mail m JOIN ab_mails a ON a.id = m.id
     WHERE NOT m.transmitted AND m.date > now() - interval '30 days';
    IF n > 0 THEN
        RAISE EXCEPTION '[PersonAnonymiseQueuedMailError] % mails to or about the booker of booking % are still waiting to be sent: try again once they have gone', n, p_document_id;
    END IF;

    -- Events of their bookings, and other people's lines naming them as room owner or mate there: by id first (a
    -- mate's line pointing at one of their lines), then by name, each present name normalised once (V0153).
    CREATE TEMP TABLE ab_events AS
    SELECT DISTINCT d.event_id FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope = 'full' WHERE d.event_id IS NOT NULL;
    ANALYZE ab_events;
    CREATE TEMP TABLE ab_share_lines (id integer PRIMARY KEY, document_id integer NOT NULL, by_id boolean NOT NULL);
    INSERT INTO ab_share_lines
    SELECT dl.id, dl.document_id, true
      FROM ab_events e
      JOIN document d ON d.event_id = e.event_id
      JOIN document_line dl ON dl.document_id = d.id
     WHERE dl.share_mate_owner_document_line_id IN (SELECT ol.id FROM document_line ol JOIN ab_docs t ON t.id = ol.document_id AND t.scope = 'full')
       AND NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.id = d.id AND t.scope = 'full');
    IF cardinality(v_names) > 0 THEN
        INSERT INTO ab_share_lines
        SELECT DISTINCT c.id, c.document_id, false
          FROM (SELECT dl.id, dl.document_id,
                       ARRAY[dl.share_mate_owner_name, dl.share_owner_mate1_name, dl.share_owner_mate2_name,
                             dl.share_owner_mate3_name, dl.share_owner_mate4_name, dl.share_owner_mate5_name,
                             dl.share_owner_mate6_name, dl.share_owner_mate7_name] AS names
                  FROM ab_events e
                  JOIN document d ON d.event_id = e.event_id
                  JOIN document_line dl ON dl.document_id = d.id
                 WHERE NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.id = d.id AND t.scope = 'full')
                   AND (dl.share_mate_owner_name IS NOT NULL OR dl.share_owner_mate1_name IS NOT NULL
                        OR dl.share_owner_mate2_name IS NOT NULL OR dl.share_owner_mate3_name IS NOT NULL
                        OR dl.share_owner_mate4_name IS NOT NULL OR dl.share_owner_mate5_name IS NOT NULL
                        OR dl.share_owner_mate6_name IS NOT NULL OR dl.share_owner_mate7_name IS NOT NULL)
                OFFSET 0) c -- a fence: the name test below must not be pushed into this scan
          CROSS JOIN LATERAL unnest(c.names) AS nm(name)
         WHERE nm.name IS NOT NULL AND public.abc_names(nm.name) = ANY (v_names)
        ON CONFLICT DO NOTHING;
    END IF;
    ANALYZE ab_share_lines;

    -- Somebody else's bookings carrying their address (when it is theirs): that address goes, nothing else. Every
    -- booking without an account under it is in scope by then, so only a stray one can be here.
    CREATE TEMP TABLE ab_email_docs (id integer PRIMARY KEY);
    IF v_theirs THEN
        INSERT INTO ab_email_docs
        SELECT d.id FROM document d
         WHERE lower(trim(d.person_email)) IN (SELECT address FROM ab_addresses)
           AND NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.id = d.id AND t.scope IN ('full', 'contact'));
    END IF;

    -- The names a carer slot or a room-mate line shows where it points at one of their bookings BY ID: theirs, however
    -- spelled. The slot is cleared below; its booking's history is redacted with the same name (that booking only).
    CREATE TEMP TABLE ab_linked_names (document_id integer NOT NULL, abc text NOT NULL, PRIMARY KEY (document_id, abc));
    INSERT INTO ab_linked_names
    SELECT DISTINCT x.id, x.abc
      FROM (SELECT d.id, public.abc_names(CASE WHEN k.slot = 1 THEN d.person_carer1_name ELSE d.person_carer2_name END) AS abc
              FROM (SELECT d0.id, d0.person_carer1_name, d0.person_carer2_name, d0.person_carer1_document_id,
                           d0.person_carer2_document_id
                      FROM document d0
                     WHERE d0.person_carer1_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                        OR d0.person_carer2_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                    OFFSET 0) d
             CROSS JOIN (VALUES (1), (2)) AS k(slot)
             WHERE (k.slot = 1 AND d.person_carer1_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                    AND d.person_carer1_name IS NOT NULL)
                OR (k.slot = 2 AND d.person_carer2_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                    AND d.person_carer2_name IS NOT NULL)
            UNION
            SELECT sl.document_id, public.abc_names(dl.share_mate_owner_name)
              FROM ab_share_lines sl JOIN document_line dl ON dl.id = sl.id
             WHERE sl.by_id AND dl.share_mate_owner_name IS NOT NULL) x
     WHERE coalesce(x.abc, '') <> '';

    -- The history rows to look at, by booking id (history's document_id index), each redacted ONCE here (V0153).
    CREATE TEMP TABLE ab_hist_docs (id integer PRIMARY KEY, scope text NOT NULL, cared boolean NOT NULL);
    INSERT INTO ab_hist_docs SELECT t.id, t.scope, t.cared FROM ab_docs t;
    INSERT INTO ab_hist_docs SELECT ed.id, 'other', false FROM ab_email_docs ed ON CONFLICT DO NOTHING;
    INSERT INTO ab_hist_docs SELECT DISTINCT sl.document_id, 'other', false FROM ab_share_lines sl ON CONFLICT DO NOTHING;
    -- The other bookings of their bookings' carts: a guest signed in by a cart or magic link acts on the whole cart.
    INSERT INTO ab_hist_docs
    SELECT DISTINCT d2.id, 'other', false
      FROM ab_docs t JOIN document d ON d.id = t.id AND d.cart_id IS NOT NULL
      JOIN document d2 ON d2.cart_id = d.cart_id
     WHERE t.scope IN ('full', 'contact')
    ON CONFLICT DO NOTHING;
    ANALYZE ab_hist_docs;
    CREATE TEMP TABLE ab_hist AS
    SELECT h.id, t.scope, nm.names,
           public.anonymise_person_changes(h.changes, t.scope, nm.names, v_addresses, v_first, v_last, v_tag, t.cared) AS new_changes,
           public.anonymise_person_comment(h.comment, nm.names, t.cared) AS new_comment,
           -- who acted: a guest is 'guest:' + their address (cut to 64), an SSO login the address — their address
           -- anywhere, or one of the booking's own addresses on its own history
           CASE WHEN h.username LIKE 'guest:%'
                     AND (   lower(substr(h.username, 7)) IN (SELECT left(a.address, 58) FROM ab_addresses a)
                          OR lower(substr(h.username, 7)) IN (SELECT left(da.address, 58) FROM ab_doc_addresses da WHERE da.id = t.id))
                THEN 'guest:' || v_email
                WHEN lower(trim(h.username)) IN (SELECT a.address FROM ab_addresses a)
                  OR lower(trim(h.username)) IN (SELECT da.address FROM ab_doc_addresses da WHERE da.id = t.id)
                THEN v_email::text
                ELSE h.username END AS new_username
      FROM ab_hist_docs t
      JOIN history h ON h.document_id = t.id
      -- their name, and the names tied to them by id on that booking
      CROSS JOIN LATERAL (SELECT v_names || coalesce(ARRAY(SELECT ln.abc FROM ab_linked_names ln WHERE ln.document_id = t.id), '{}'::text[]) AS names) nm;
    ALTER TABLE ab_hist ADD PRIMARY KEY (id);
    ANALYZE ab_hist;

    -- A redaction must keep every change array valid for the replay: no element may have turned into null.
    SELECT count(*) INTO n FROM ab_hist hs JOIN history h ON h.id = hs.id
     WHERE hs.new_changes IS NOT NULL AND pg_input_is_valid(hs.new_changes, 'jsonb')
       AND jsonb_typeof(hs.new_changes::jsonb) = 'array'
       AND (SELECT count(*) FROM jsonb_array_elements(hs.new_changes::jsonb) e WHERE jsonb_typeof(e) = 'null')
         > (SELECT count(*) FROM jsonb_array_elements(CASE WHEN pg_input_is_valid(h.changes, 'jsonb') AND jsonb_typeof(h.changes::jsonb) = 'array'
                                                           THEN h.changes::jsonb ELSE '[]'::jsonb END) e
             WHERE jsonb_typeof(e) = 'null');
    IF n > 0 THEN
        RAISE EXCEPTION 'anonymise_booker would leave % history change arrays with a null element (booking %) — nothing written', n, p_document_id;
    END IF;

    -- Other customers bearing their name: listed, never touched (V0153).
    CREATE TEMP TABLE ab_namesakes AS
    SELECT x.id, x.frontend_account_id AS account_id, x.owner,
           (SELECT count(*) FROM document d WHERE d.person_id = x.id) AS bookings
      FROM person x
     WHERE cardinality(v_names) > 0 AND x.abc_names = ANY (v_names) AND NOT x.removed AND x.account_person_id IS NULL;

    -- ================= THE GRID =================
    SELECT count(*) INTO v_mail_rows_before FROM mail;
    SELECT count(*) INTO v_mail_queued_before FROM mail WHERE NOT transmitted;

    RETURN QUERY VALUES
        ('where',  'database',  current_database()::text),
        ('where',  'run_at',    now()::text),
        ('where',  'tag',       v_tag::text),
        ('booker', 'document_id',    p_document_id::text),
        ('booker', 'ref',            b.ref::text),
        ('booker', 'named',          (v_name IS NOT NULL)::text),
        ('booker', 'has_address',    (v_address IS NOT NULL)::text),
        ('booker', 'address',        v_address_state),
        ('booker', 'address_owners', (SELECT count(*) FROM ab_address_owners)::text),
        -- which bookings (and how), and which applications: a commit must find the same ones, not only as many
        ('booker', 'scope_digest',   md5(coalesce((SELECT string_agg(t.id || ':' || t.scope || ':' || t.cared, ',' ORDER BY t.id) FROM ab_docs t), '')
                                         || '|' || coalesce((SELECT string_agg(c.id || ':' || c.action, ',' ORDER BY c.id) FROM ab_candidates c), '')
                                         || '|' || coalesce((SELECT string_agg(a.id::text, ',' ORDER BY a.id) FROM ab_applications a), ''))),
        ('scope',  'guest_bookings_matched', (SELECT count(*) FROM ab_candidates WHERE action = 'auto')::text),
        ('scope',  'candidates',             (SELECT count(*) FROM ab_candidates WHERE action IN ('full', 'contact', 'carer'))::text),
        ('scope',  'candidates_ticked',      cardinality(v_ticked)::text),
        ('scope',  'address_candidates_unticked',
                   (SELECT count(*) FROM ab_candidates c
                     WHERE c.by_email AND c.action IN ('contact', 'full') AND NOT coalesce(c.id = ANY (v_ticked), false))::text),
        ('scope',  'other_applications_under_address', v_other_applications::text),
        ('scope',  'volunteering_applications', (SELECT count(*) FROM ab_applications)::text),
        ('scope',  'bookings_with_their_address', (SELECT count(*) FROM ab_email_docs)::text),
        ('scope',  'shared_phones_ignored',  v_shared_phones::text),
        ('scope',  'namesakes',              (SELECT count(*) FROM ab_namesakes)::text),
        ('scope',  'as_carer',               (SELECT count(*) FROM ab_docs WHERE cared AND scope <> 'full')::text),
        ('scope',  'named_on_room_lines',    (SELECT count(*) FROM ab_share_lines)::text),
        ('scope',  'mails',                  (SELECT count(*) FROM ab_mails)::text),
        ('scope',  'volunteering_photos_outside_db', (SELECT count(*) FROM ab_applications WHERE photo IS NOT NULL)::text);

    -- One row per candidate, for the screen: never the booking's names or addresses (V0153's shape).
    RETURN QUERY
    SELECT 'candidate'::text, c.id::text,
           jsonb_build_object('documentId', c.id, 'ref', c.ref, 'eventId', c.event_id, 'eventName', e.name,
                              'eventStart', to_char(e.start_date, 'YYYY-MM-DD'),
                              'match', concat_ws('+', CASE WHEN c.by_email THEN 'email' END,
                                                      CASE WHEN c.by_name THEN 'name' END,
                                                      CASE WHEN c.by_phone THEN 'phone' END),
                              'action', c.action, 'ticked', c.id = ANY (v_ticked), 'carerMatch', c.carer_match,
                              -- made under a customer's account (the id only; who it is travels in the reply)
                              'customerId', c.customer_id,
                              'blockedBy', CASE WHEN c.action = 'full' THEN public.anonymise_person_blockers(ARRAY[c.id]) END)::text
      FROM ab_candidates c LEFT JOIN event e ON e.id = c.event_id
     WHERE c.action IN ('full', 'contact', 'carer')
     ORDER BY e.start_date DESC NULLS LAST, c.id;

    -- The ticks this run ran with (a preview drops stale ones): the very set a commit of this preview must send.
    RETURN QUERY VALUES ('ticks', 'candidates', array_to_string(v_ticked, ',')),
                        ('ticks', 'members',    '');

    RETURN QUERY
    SELECT 'namesake'::text, x.id::text,
           jsonb_build_object('personId', x.id, 'accountId', x.account_id, 'owner', x.owner, 'bookings', x.bookings,
                              'familyNames', 0)::text
      FROM ab_namesakes x ORDER BY x.bookings DESC, x.id LIMIT 50;

    RETURN QUERY
    SELECT 'addressOwner'::text, o.person_id::text, jsonb_build_object('personId', o.person_id, 'via', o.via)::text
      FROM ab_address_owners o ORDER BY o.person_id LIMIT 20;

    -- The photo files of the applications deleted below: outside the database, so staff delete them, and once the
    -- row is gone nothing else leads to them. For the screen only (the runner never stores this step).
    RETURN QUERY
    SELECT 'photo'::text, a.id::text, a.photo::text FROM ab_applications a WHERE a.photo IS NOT NULL ORDER BY a.id;

    -- ================= THE ANONYMISATION =================

    -- Bookings.
    UPDATE document d
       SET person_first_name = v_first, person_last_name = v_last,
           person_name = NULL, person_lay_name = NULL,
           person_email = NULL, person_phone = NULL, person_street = NULL, person_post_code = NULL,
           person_latitude = NULL, person_longitude = NULL, person_passport = NULL,
           person_carer1_name = NULL, person_carer2_name = NULL,
           inet_ip = NULL, inet_geo_latitude = NULL, inet_geo_longitude = NULL,
           special_needs = CASE WHEN d.special_needs IS NOT NULL AND d.special_needs NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.special_needs END,
           comment       = CASE WHEN d.comment       IS NOT NULL AND d.comment       NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.comment END,
           request       = CASE WHEN d.request       IS NOT NULL AND d.request       NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.request END
      FROM ab_docs t WHERE t.id = d.id AND t.scope = 'full';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_full', n::text);

    UPDATE document d
       SET person_email = NULL, person_phone = NULL, person_street = NULL, person_post_code = NULL,
           person_latitude = NULL, person_longitude = NULL
      FROM ab_docs t WHERE t.id = d.id AND t.scope = 'contact';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_contact', n::text);

    UPDATE document d
       SET person_carer1_name = CASE WHEN d.person_carer1_document_id IN (SELECT t2.id FROM ab_docs t2 WHERE t2.scope = 'full')
                                       OR public.abc_names(d.person_carer1_name) = ANY (v_names)
                                     THEN NULL ELSE d.person_carer1_name END,
           person_carer2_name = CASE WHEN d.person_carer2_document_id IN (SELECT t2.id FROM ab_docs t2 WHERE t2.scope = 'full')
                                       OR public.abc_names(d.person_carer2_name) = ANY (v_names)
                                     THEN NULL ELSE d.person_carer2_name END
      FROM ab_docs t WHERE t.id = d.id AND t.cared AND t.scope <> 'full';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_carer', n::text);

    UPDATE document d SET person_email = NULL
      FROM ab_email_docs ed WHERE ed.id = d.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_their_address', n::text);

    -- The bearer key of their bookings' carts, unless the cart holds someone else's booking too (V0153).
    -- ... and its magic link, which a shared address keeps: the cart no longer opens through it
    UPDATE cart c SET uuid = gen_random_uuid()::text, magic_link_id = NULL
     WHERE c.id IN (SELECT d.cart_id FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope = 'full')
       AND NOT EXISTS (SELECT 1 FROM document o WHERE o.cart_id = c.id
                         AND NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.id = o.id AND t.scope = 'full'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'cart_uuid', n::text);
    RETURN QUERY VALUES ('kept', 'cart_shared_with_others',
        (SELECT count(DISTINCT d.cart_id) FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope = 'full'
          WHERE d.cart_id IS NOT NULL
            AND EXISTS (SELECT 1 FROM document o WHERE o.cart_id = d.cart_id
                          AND NOT EXISTS (SELECT 1 FROM ab_docs t2 WHERE t2.id = o.id AND t2.scope = 'full')))::text);

    UPDATE document_line dl SET comment = v_tag
      FROM ab_docs t
     WHERE t.id = dl.document_id AND t.scope = 'full' AND dl.comment IS NOT NULL AND dl.comment NOT LIKE v_tag_prefix || '%';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line_comment', n::text);

    UPDATE document_line dl
       SET share_mate_owner_name = CASE WHEN sl.by_id OR public.abc_names(dl.share_mate_owner_name) = ANY (v_names)
                                        THEN NULL ELSE dl.share_mate_owner_name END,
           share_owner_mate1_name = CASE WHEN public.abc_names(dl.share_owner_mate1_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate1_name END,
           share_owner_mate2_name = CASE WHEN public.abc_names(dl.share_owner_mate2_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate2_name END,
           share_owner_mate3_name = CASE WHEN public.abc_names(dl.share_owner_mate3_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate3_name END,
           share_owner_mate4_name = CASE WHEN public.abc_names(dl.share_owner_mate4_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate4_name END,
           share_owner_mate5_name = CASE WHEN public.abc_names(dl.share_owner_mate5_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate5_name END,
           share_owner_mate6_name = CASE WHEN public.abc_names(dl.share_owner_mate6_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate6_name END,
           share_owner_mate7_name = CASE WHEN public.abc_names(dl.share_owner_mate7_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate7_name END
      FROM ab_share_lines sl WHERE sl.id = dl.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line_room_names', n::text);

    -- Magic links of their address (only when it is theirs). cart.magic_link_id has no ON DELETE action.
    IF v_theirs THEN
        UPDATE cart c SET magic_link_id = NULL
         WHERE c.magic_link_id IN (SELECT ml.id FROM magic_link ml
                                    WHERE lower(trim(ml.email)) IN (SELECT address FROM ab_addresses)
                                       OR lower(trim(ml.old_email)) IN (SELECT address FROM ab_addresses));
        DELETE FROM magic_link ml
         WHERE lower(trim(ml.email)) IN (SELECT address FROM ab_addresses)
            OR lower(trim(ml.old_email)) IN (SELECT address FROM ab_addresses);
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('deleted', 'magic_link', n::text);

    -- Mails: subject and content of every mail to or about them, letters included; never transmitted or read.
    UPDATE mail m SET subject = v_tag, content = v_tag
      FROM ab_mails a
     WHERE a.id = m.id AND (m.subject NOT LIKE v_tag_prefix || '%' OR m.content NOT LIKE v_tag_prefix || '%');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'mail', n::text);

    UPDATE mail m SET from_name = NULL, from_email = NULL
      FROM ab_sender_mails sm WHERE sm.id = m.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'mail_sender', n::text);

    UPDATE recipient r SET name = NULL, email = v_email
      FROM ab_recipients ar WHERE ar.id = r.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'recipient', n::text);

    -- History: request, the comment quoting a mail we tagged, changes redacted in place, the user (V0153).
    UPDATE history h
       SET request = CASE WHEN hs.scope = 'full' AND h.request IS NOT NULL AND h.request NOT LIKE v_tag_prefix || '%'
                          THEN v_tag ELSE h.request END,
           comment = CASE WHEN h.comment IS NOT NULL AND h.comment NOT LIKE v_tag_prefix || '%'
                               AND h.mail_id IN (SELECT id FROM ab_mails)
                          THEN v_tag ELSE hs.new_comment END,
           changes = hs.new_changes,
           username = hs.new_username
      FROM ab_hist hs
     WHERE hs.id = h.id
       AND (   (hs.scope = 'full' AND h.request IS NOT NULL AND h.request NOT LIKE v_tag_prefix || '%')
            OR (h.comment IS NOT NULL AND h.comment NOT LIKE v_tag_prefix || '%' AND h.mail_id IN (SELECT id FROM ab_mails))
            OR hs.new_comment IS DISTINCT FROM h.comment
            OR hs.new_username IS DISTINCT FROM h.username
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
      FROM ab_docs t
     WHERE t.id = mt.document_id AND t.scope IN ('full', 'contact')
       AND (   (mt.comment IS NOT NULL AND mt.comment NOT LIKE v_tag_prefix || '%')
            OR mt.gateway_response IS NOT NULL OR mt.gateway_generate_payment_url_response IS NOT NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'money_transfer', n::text);

    -- Chat: every text message and the title of their bookings' conversations.
    UPDATE chat_message cm SET content = v_tag
     WHERE cm.kind = 'text' AND cm.content NOT LIKE v_tag_prefix || '%'
       AND cm.conversation_id IN (SELECT cv.id FROM conversation cv
                                   WHERE cv.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'chat_message', n::text);

    UPDATE conversation cv SET title = NULL
     WHERE cv.title IS NOT NULL AND cv.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'conversation', n::text);

    UPDATE list_item li SET comment = v_tag
     WHERE li.comment IS NOT NULL AND li.comment NOT LIKE v_tag_prefix || '%'
       AND li.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'list_item', n::text);

    UPDATE enqueued_request er
       SET request_string = v_tag,
           reply_string = CASE WHEN er.reply_string IS NOT NULL THEN v_tag END
      FROM ab_docs t
     WHERE t.id = er.document_id AND t.scope IN ('full', 'contact') AND er.execution_date IS NOT NULL
       AND (er.request_string NOT LIKE v_tag_prefix || '%' OR (er.reply_string IS NOT NULL AND er.reply_string NOT LIKE v_tag_prefix || '%'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'enqueued_request', n::text);

    -- Deleted outright: push registrations of their address or their bookings (a device shared with others keeps
    -- theirs, V0153).
    WITH gone AS (
        DELETE FROM push_subscription_recipient psr
         WHERE lower(trim(psr.email)) IN (SELECT address FROM ab_addresses)
            OR psr.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full')
        RETURNING psr.id, psr.subscription_id)
    DELETE FROM push_subscription ps
     WHERE ps.id IN (SELECT subscription_id FROM gone)
       AND NOT EXISTS (SELECT 1 FROM push_subscription_recipient o
                        WHERE o.subscription_id = ps.id AND o.id NOT IN (SELECT id FROM gone));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'push_subscription', n::text);

    -- Volunteering applications made without an account that are theirs (ab_applications): health,
    -- criminal-record and immigration answers, deleted with their timeline (ON DELETE CASCADE). The photo is a file
    -- outside the database: the grid counts it, staff delete it (reference doc).
    DELETE FROM volunteering_application va WHERE va.id IN (SELECT a.id FROM ab_applications a);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'volunteering_application', n::text);

    DELETE FROM mate_invite_token mit
     WHERE mit.owner_document_line_id IN (SELECT dl.id FROM document_line dl JOIN ab_docs t ON t.id = dl.document_id AND t.scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'mate_invite_token', n::text);

    -- One marker per booking whose data was removed. username is never 'online': no trigger sends anything.
    INSERT INTO history (document_id, comment, username, user_person_id)
    SELECT t.id, 'Personal data removed (GDPR erasure request)', 'gdpr-erasure', public.kbs_audit_person_id()
      FROM ab_docs t WHERE t.scope IN ('full', 'contact');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('inserted', 'history_marker', n::text);

    -- The run ledger: no run may still tie these bookings to whom it listed. Runs started from one of them keep
    -- counts, keys and dates; any run's ticks touching them, and refusal texts naming one, go.
    UPDATE person_erasure_run r
       SET candidate_document_ids = '{}', member_ids = '{}', error = NULL,
           grid = CASE WHEN jsonb_typeof(r.grid) = 'array'
                       THEN (SELECT coalesce(jsonb_agg(g.e ORDER BY g.o), '[]'::jsonb)
                               FROM jsonb_array_elements(r.grid) WITH ORDINALITY AS g(e, o)
                              WHERE g.e ->> 'step' NOT IN ('candidate', 'member', 'namesake', 'addressOwner', 'ticks'))
                       ELSE r.grid END
     WHERE r.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_erasure_run_own', n::text);
    UPDATE person_erasure_run r
       SET candidate_document_ids = CASE WHEN r.candidate_document_ids && ARRAY(SELECT t.id FROM ab_docs t)
                                         THEN '{}'::integer[] ELSE r.candidate_document_ids END,
           error = CASE WHEN EXISTS (SELECT 1 FROM ab_docs t WHERE t.scope = 'full' AND r.error ~ ('\mbooking ' || t.id || '\M'))
                        THEN NULL ELSE r.error END
     WHERE NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.scope = 'full' AND t.id = r.document_id)
       AND (   r.candidate_document_ids && ARRAY(SELECT t.id FROM ab_docs t)
            OR (r.error IS NOT NULL
                AND EXISTS (SELECT 1 FROM ab_docs t WHERE t.scope = 'full' AND r.error ~ ('\mbooking ' || t.id || '\M'))));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_erasure_run_others', n::text);

    -- ================= CHECKS (raise = the caller rolls back) =================
    SELECT count(*) INTO n FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope IN ('full', 'contact')
     WHERE d.person_email IS NOT NULL OR d.person_phone IS NOT NULL OR d.person_street IS NOT NULL
        OR (t.scope = 'full' AND (d.person_first_name IS DISTINCT FROM v_first OR d.person_last_name IS NULL
                                  OR d.person_last_name IS DISTINCT FROM v_last
                                  OR d.inet_ip IS NOT NULL OR d.person_passport IS NOT NULL));
    IF n > 0 THEN bad := bad || format(E'\n  bookings still identifying: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id JOIN ab_hist_docs t ON t.id = h.document_id
     WHERE public.anonymise_person_comment(h.comment, hs.names, t.cared) IS DISTINCT FROM h.comment;
    IF n > 0 THEN bad := bad || format(E'\n  history comments still naming them: %s', n); END IF;

    -- Redacting again finds nothing more: no name of theirs (or tied to them by id), no address of theirs is left
    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id JOIN ab_hist_docs t ON t.id = h.document_id
     WHERE pg_input_is_valid(h.changes, 'jsonb')
       AND public.anonymise_person_changes(h.changes, t.scope, hs.names, v_addresses, v_first, v_last, v_tag, t.cared)::jsonb
           IS DISTINCT FROM h.changes::jsonb;
    IF n > 0 THEN bad := bad || format(E'\n  history.changes not fully redacted: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id
     WHERE lower(trim(h.username)) IN (SELECT a.address FROM ab_addresses a)
        OR (h.username LIKE 'guest:%' AND lower(substr(h.username, 7)) IN (SELECT left(a.address, 58) FROM ab_addresses a))
        OR lower(trim(h.username)) IN (SELECT da.address FROM ab_doc_addresses da WHERE da.id = h.document_id)
        OR (h.username LIKE 'guest:%' AND lower(substr(h.username, 7)) IN (SELECT left(da.address, 58) FROM ab_doc_addresses da WHERE da.id = h.document_id));
    IF n > 0 THEN bad := bad || format(E'\n  history rows still naming them as the user: %s', n); END IF;

    -- Their address anywhere in the rows looked at; a booking's own address in its own rows.
    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id
     WHERE h.changes IS NOT NULL
       AND (   EXISTS (SELECT 1 FROM ab_addresses a WHERE position(a.address IN lower(h.changes)) > 0)
            OR EXISTS (SELECT 1 FROM ab_doc_addresses da WHERE da.id = h.document_id AND position(da.address IN lower(h.changes)) > 0));
    IF n > 0 THEN bad := bad || format(E'\n  history.changes still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id AND hs.scope = 'full'
     WHERE pg_input_is_valid(h.changes, 'jsonb') AND jsonb_typeof(h.changes::jsonb) = 'array'
       AND EXISTS (SELECT 1 FROM jsonb_array_elements(h.changes::jsonb) e
                    WHERE e ->> '$codec' = 'AddDocumentEvent'
                      AND (   (e ? 'firstName' AND e ->> 'firstName' IS DISTINCT FROM v_first)
                           OR (e ? 'lastName' AND coalesce(e ->> 'lastName', '') NOT LIKE '#%')));
    IF n > 0 THEN bad := bad || format(E'\n  history.changes still holding a name: %s', n); END IF;

    SELECT count(*) INTO n FROM document d WHERE lower(trim(d.person_email)) IN (SELECT address FROM ab_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  bookings still holding their address: %s', n); END IF;

    SELECT count(*) INTO n FROM document d JOIN ab_docs t ON t.id = d.id AND t.cared
     WHERE public.abc_names(d.person_carer1_name) = ANY (v_names) OR public.abc_names(d.person_carer2_name) = ANY (v_names)
        OR (d.person_carer1_name IS NOT NULL AND d.person_carer1_document_id IN (SELECT t2.id FROM ab_docs t2 WHERE t2.scope = 'full'))
        OR (d.person_carer2_name IS NOT NULL AND d.person_carer2_document_id IN (SELECT t2.id FROM ab_docs t2 WHERE t2.scope = 'full'));
    IF n > 0 THEN bad := bad || format(E'\n  bookings still naming them as carer: %s', n); END IF;

    SELECT count(*) INTO n FROM document_line dl JOIN ab_share_lines sl ON sl.id = dl.id
     WHERE (sl.by_id AND dl.share_mate_owner_name IS NOT NULL)
        OR public.abc_names(dl.share_mate_owner_name) = ANY (v_names);
    IF n > 0 THEN bad := bad || format(E'\n  room lines still naming them: %s', n); END IF;

    SELECT count(*) INTO n FROM recipient r JOIN ab_recipients ar ON ar.id = r.id
     WHERE r.name IS NOT NULL OR r.email IS DISTINCT FROM v_email;
    IF n > 0 THEN bad := bad || format(E'\n  recipients still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM volunteering_application va WHERE va.id IN (SELECT a.id FROM ab_applications a);
    IF n > 0 THEN bad := bad || format(E'\n  volunteering applications left: %s', n); END IF;

    SELECT count(*) INTO n FROM mail m JOIN ab_sender_mails sm ON sm.id = m.id
     WHERE m.from_email IS NOT NULL OR m.from_name IS NOT NULL;
    IF n > 0 THEN bad := bad || format(E'\n  mails still naming them as the sender: %s', n); END IF;

    SELECT count(*) INTO n FROM mail m JOIN ab_mails a ON a.id = m.id
     WHERE m.subject NOT LIKE v_tag_prefix || '%' OR m.content NOT LIKE v_tag_prefix || '%';
    IF n > 0 THEN bad := bad || format(E'\n  mails not rewritten: %s', n); END IF;

    SELECT count(*) INTO n FROM magic_link ml
     WHERE lower(trim(ml.email)) IN (SELECT address FROM ab_addresses) OR lower(trim(ml.old_email)) IN (SELECT address FROM ab_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  magic links left: %s', n); END IF;

    IF bad <> '' THEN
        RAISE EXCEPTION 'Personal data survives the anonymisation of the booker of booking % [db=%]:% — ROLLBACK and investigate', p_document_id, current_database(), bad;
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

REVOKE ALL ON FUNCTION public.anonymise_booker(integer, integer[], boolean) FROM PUBLIC;

COMMENT ON FUNCTION public.anonymise_booker(integer, integer[], boolean) IS
'GDPR erasure of somebody who only ever booked without an account (V0163), started from one of their bookings: removes everything that identifies them from their bookings, history, mails, payments and chat, keeping amounts, dates and gateway references. Requires an explicit REPEATABLE READ transaction and SET LOCAL kbs.erase_confirm = ''ANONYMISE BOOKER <document id>''. Never commits — the caller reads the grid, then COMMIT or ROLLBACK. Docs: aggregate repo, docs/operations/gdpr-person-erasure-reference.md.';

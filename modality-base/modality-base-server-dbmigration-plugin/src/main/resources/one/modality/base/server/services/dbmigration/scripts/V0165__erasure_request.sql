-- V0165: erasure_request -- the Data Protection Officer's register of GDPR erasure requests (Art. 17).
--
-- WHY. A request to be erased must be answered within one month of receipt (Art. 12(3)), extendable by two more
-- in some cases, and the controller must be able to show that it was (Art. 5(2), accountability). Until now
-- nothing in KBS recorded a request: anonymise_person() (V0153) and anonymise_booker() (V0163) record their RUNS in
-- person_erasure_run, but not that somebody asked, when, how they were identified, why a request waits, or that
-- the steps outside KBS (Jotform, the Knack apps, the payment provider, the mailbox) were done. The back office's "Erasure requests"
-- page (Data Protection Officers only) keeps that register here, one row per request, from receipt to closing.
--
-- WHAT A ROW HOLDS. Dates (received, held until, created, updated, closed), closed-set codes (status, how the
-- requester's identity was confirmed, the subject's kind), ids (the subject -- a person id, or the document id of
-- the booking a booker without an account was reached through -- the anonymisation run that erased them, the staff
-- person ids who created and closed the request) and one boolean per processor for the outside-KBS checklist. NO NAME, EMAIL,
-- PHONE OR FREE TEXT, on purpose: the register outlives the erasure, and a name kept here would undo it. The
-- page finds the person by email, booking reference or name each time, and holds what it typed in memory only.
-- The CHECKs below make the table unable to hold text beyond its codes (outside is a small object, which the server
-- writes whole from its closed list of keys).
--
-- NO FOREIGN KEYS, on purpose, like person_erasure_run: the record must outlive whatever happens to the person,
-- the booking or the run, and the staff member.
--
-- NOT IN THE DOMAIN MODEL. No DQL can read or write it, and clients cannot send raw SQL (ClientQueryGuard,
-- ClientSubmitGuard), so the erasure-request endpoints of modality-event-server-lifecycle-plugin (ErasureRequests:
-- modality/service/dpo/erasure/requests and requests/save) -- Data Protection Officers only, re-checked on every
-- call -- are the only way in. The server sets created_by / closed_by from the session, never from the client.
-- PUBLIC holds nothing on it or its sequence.
--
-- ANONYMISER. To be classified in the aggregate repo's scripts/gdpr-anonymise/10-anon-helpers.sql as kept: dates,
-- codes, ids and booleans, no name, address or free text -- the record that requests were answered. The erasure
-- routines leave it alone (scripts/gdpr-erasure/manifest.tsv: its text-like columns hold codes only).
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

CREATE TABLE IF NOT EXISTS public.erasure_request (
    id                   serial      PRIMARY KEY,
    -- When the request reached the organisation (any channel): the one-month deadline runs from it.
    received_on          date        NOT NULL,
    status               text        NOT NULL
        CONSTRAINT erasure_request_status_chk CHECK (status IN ('open', 'on_hold', 'done', 'refused')),
    -- Set exactly when the request is on hold: until when (an upcoming booking, money still owed).
    on_hold_until        date,
    -- How the requester was confirmed to be the person: a reply from their address, an identity document, other.
    verified_by          text
        CONSTRAINT erasure_request_verified_by_chk CHECK (verified_by IN ('email', 'id', 'other')),
    -- Who is erased: a customer (subject_id = person id) or a booker without an account (subject_id = the id of
    -- the booking they were reached through, the seed of anonymise_booker()).
    subject_kind         text
        CONSTRAINT erasure_request_subject_kind_chk CHECK (subject_kind IN ('person', 'booker')),
    subject_id           integer
        CONSTRAINT erasure_request_subject_id_chk CHECK (subject_id > 0),
    -- The person_erasure_run (commit) that erased them. No foreign key: see above.
    run_id               integer
        CONSTRAINT erasure_request_run_id_chk CHECK (run_id > 0),
    -- When a complete check last found nothing identifying the subject left, after the recorded run. Stamped by the
    -- server's check itself, never sent by the page; cleared when a save changes the subject or the run.
    checked_at           timestamptz,
    -- The places outside KBS the DPO ticked off, one boolean per processor key (jotform, knackSttp, knackAbsence,
    -- payments, mailbox in 2026-11). The keys are the server's closed list (ErasureRequests.OUTSIDE_KEYS), which writes
    -- this column whole; the CHECK only keeps it a small object, so adding a processor needs no migration.
    outside              jsonb       NOT NULL DEFAULT '{}'::jsonb
        CONSTRAINT erasure_request_outside_chk CHECK (
            jsonb_typeof(outside) = 'object' AND octet_length(outside::text) <= 1000),
    created_at           timestamptz NOT NULL DEFAULT now(),
    -- The Data Protection Officer who recorded it, from the server session. A person id, never a name.
    created_by_person_id integer     NOT NULL,
    updated_at           timestamptz,
    -- Set exactly when the request is done or refused, with who closed it.
    closed_at            timestamptz,
    closed_by_person_id  integer,
    CONSTRAINT erasure_request_on_hold_chk CHECK ((status = 'on_hold') = (on_hold_until IS NOT NULL)),
    CONSTRAINT erasure_request_subject_chk CHECK ((subject_kind IS NULL) = (subject_id IS NULL)),
    CONSTRAINT erasure_request_closed_chk CHECK ((status IN ('done', 'refused')) = (closed_at IS NOT NULL)),
    CONSTRAINT erasure_request_closed_by_chk CHECK ((closed_at IS NULL) = (closed_by_person_id IS NULL))
);

REVOKE ALL ON TABLE public.erasure_request FROM PUBLIC;
REVOKE ALL ON SEQUENCE public.erasure_request_id_seq FROM PUBLIC;

COMMENT ON TABLE public.erasure_request IS
    'The Data Protection Officer''s register of GDPR erasure requests (V0165): one row per request, from receipt to closing. Dates, codes, ids and a checklist of booleans - never a name, email, phone or free text (the record outlives the erasure). No foreign keys. Not in the domain model: the erasure-request endpoints of modality-event-server-lifecycle-plugin (Data Protection Officers only) are the only way in.';
COMMENT ON COLUMN public.erasure_request.received_on IS
    'When the request reached the organisation. The one-month answer deadline (Art. 12(3)) is received_on + 1 month, computed when read.';
COMMENT ON COLUMN public.erasure_request.status IS
    'open | on_hold (waiting until on_hold_until: an upcoming booking, money still owed) | done | refused.';
COMMENT ON COLUMN public.erasure_request.on_hold_until IS
    'Set exactly when status = on_hold: the date the request is looked at again.';
COMMENT ON COLUMN public.erasure_request.verified_by IS
    'How the requester was confirmed to be the person: email (a reply from their address) | id (an identity document) | other. NULL = not recorded yet. Never the evidence itself.';
COMMENT ON COLUMN public.erasure_request.subject_kind IS
    'person = a customer (subject_id is their person id); booker = somebody who only booked without an account (subject_id is the booking they were reached through). NULL with subject_id until the DPO picks who is erased.';
COMMENT ON COLUMN public.erasure_request.subject_id IS
    'A person id or a document id, per subject_kind. No foreign key: the record outlives the rows.';
COMMENT ON COLUMN public.erasure_request.run_id IS
    'The person_erasure_run (commit) that erased the subject. No foreign key.';
COMMENT ON COLUMN public.erasure_request.checked_at IS
    'When a complete check (every area searched) last found nothing identifying the subject left, after the recorded run. Stamped by the server''s check, never sent by the client; cleared when a save changes the subject or the run.';
COMMENT ON COLUMN public.erasure_request.outside IS
    'The outside-KBS checklist: one boolean per processor where the person''s data may also live - jotform (its forms, volunteering applications among them), knackSttp and knackAbsence (the Knack apps), payments (the payment provider), mailbox (the organisation''s mailboxes and spreadsheets). The keys are the server''s closed list (ErasureRequests.OUTSIDE_KEYS); the CHECK only keeps it a small object. Codes and booleans, no personal data.';
COMMENT ON COLUMN public.erasure_request.created_at IS
    'When the request was recorded in KBS.';
COMMENT ON COLUMN public.erasure_request.created_by_person_id IS
    'The Data Protection Officer who recorded it, from the server session (never sent by the client). A person id; no foreign key.';
COMMENT ON COLUMN public.erasure_request.updated_at IS
    'When it was last saved after its creation. NULL = never.';
COMMENT ON COLUMN public.erasure_request.closed_at IS
    'Set exactly when status is done or refused: when it became so.';
COMMENT ON COLUMN public.erasure_request.closed_by_person_id IS
    'The Data Protection Officer who closed it, from the server session. Set exactly with closed_at.';

-- Ownership, as V0146 and V0153: hand the table (and, with it, its serial sequence) to whoever owns public.person,
-- warning rather than failing, because a tidy-up must never abort the boot chain.
DO $$
DECLARE
    app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        BEGIN
            EXECUTE format('ALTER TABLE public.erasure_request OWNER TO %I', app_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0165: could not realign the owner of public.erasure_request to % (%)', app_owner, SQLERRM;
        END;
    END IF;
END $$;

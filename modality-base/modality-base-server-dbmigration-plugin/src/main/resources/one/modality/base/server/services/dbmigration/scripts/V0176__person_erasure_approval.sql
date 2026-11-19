-- V0176: person_erasure_approval — the other organisations' Data Protection Officers agree to an "erase everywhere".
--
-- WHY. A DPO erases what their organisations hold (anonymise_person() / anonymise_booker() with their organisations,
-- V0175); the other organisations' bookings are kept, unlinked. To erase the whole person instead, every other
-- organisation's DPO must agree (David, 19 Nov 2026). The request IS a preview run already in person_erasure_run (an
-- 'everywhere' preview, p_organization_ids NULL): its grid is the baseline the commit is compared with. This table
-- adds only the answers: one row per organisation asked, for that run.
--
-- WHAT A ROW HOLDS. Ids (the run, the organisation, the staff members who asked and decided), dates (asked, decided,
-- expires = asked + 14 days by default, set by the server) and a state code. NO NAME, EMAIL OR FREE TEXT, on purpose:
-- a decline carries no reason, and the row outlives the erasure it approves. Who the subject is follows from the run
-- (person_erasure_run.person_id / document_id), as for every run.
--   pending   asked, not answered            approved / declined   answered (decided_by_person_id, decided_at set)
--   withdrawn the asker took the request back (pending rows only)
-- An approval past expires_at no longer counts; the server reports it as expired (no state is written for it).
--
-- NO FOREIGN KEY INTO person, on purpose: anonymise_person() refuses to run while a foreign key into person exists
-- that it was not written for ([PersonAnonymiseUnknownReferenceError]), and these are staff ids, kept as ids like
-- person_erasure_run.requested_by_person_id. run_id references person_erasure_run (a run is never deleted; CASCADE
-- for a hand clean-up), organization_id references organization.
--
-- NOT IN THE DOMAIN MODEL. No DQL reads or writes it; the approval endpoints of modality-event-server-lifecycle-plugin
-- (modality/service/dpo/erasure/approval/ask, approvals, approval/decide, approval/withdraw — Data Protection Officers
-- only, grants checked fresh) are the only way in. The server sets the person ids from the session, never the client.
-- PUBLIC holds nothing on it or its sequence.
--
-- ANONYMISER. Classified in the aggregate repo's scripts/gdpr-anonymise/10-anon-helpers.sql ('*', kept: ids, dates
-- and a state code; the staff ids are the record of who agreed). scripts/gdpr-erasure/manifest.tsv: state is a code.
--
-- DDL: a new table, its unique key and index. The foreign keys take a SHARE ROW EXCLUSIVE lock on person_erasure_run and
-- organization for the length of this transaction (writes wait, reads do not). Safe to re-run.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

CREATE TABLE IF NOT EXISTS public.person_erasure_approval (
    id                   serial      PRIMARY KEY,
    -- The proposal: the asker's 'everywhere' preview run.
    run_id               integer     NOT NULL
        REFERENCES public.person_erasure_run (id) ON DELETE CASCADE,
    -- The organisation whose DPO is asked.
    organization_id      integer     NOT NULL
        REFERENCES public.organization (id),
    state                text        NOT NULL
        CONSTRAINT person_erasure_approval_state_chk CHECK (state IN ('pending', 'approved', 'declined', 'withdrawn')),
    -- The DPO who asked, from the server session. A person id, never a name; no foreign key (see above).
    asked_by_person_id   integer     NOT NULL,
    asked_at             timestamptz NOT NULL DEFAULT now(),
    -- The DPO who approved or declined, from the server session; no foreign key.
    decided_by_person_id integer,
    decided_at           timestamptz,
    expires_at           timestamptz NOT NULL,
    CONSTRAINT person_erasure_approval_run_organization_uq UNIQUE (run_id, organization_id),
    -- an answer names who gave it and when
    CONSTRAINT person_erasure_approval_decided_chk
        CHECK (state NOT IN ('approved', 'declined') OR (decided_by_person_id IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT person_erasure_approval_expires_chk CHECK (expires_at > asked_at)
);

-- "Waiting for your approval": the pending rows of the caller's organisations.
CREATE INDEX IF NOT EXISTS person_erasure_approval_organization_state_idx
    ON public.person_erasure_approval (organization_id, state);

REVOKE ALL ON TABLE public.person_erasure_approval FROM PUBLIC;
REVOKE ALL ON SEQUENCE public.person_erasure_approval_id_seq FROM PUBLIC;

-- Ownership, as V0146 / V0172: hand the new table (and its serial sequence) to whoever owns public.person, so a pg_dump
-- run as that role (backups, the staging refresh) is not refused on it.
DO $$
DECLARE
    app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        BEGIN
            EXECUTE format('ALTER TABLE public.person_erasure_approval OWNER TO %I', app_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0176: could not realign the owner of public.person_erasure_approval to % (%)', app_owner, SQLERRM;
        END;
    END IF;
END $$;

COMMENT ON TABLE public.person_erasure_approval IS
    'Approvals of an "erase everywhere" (V0176): for a proposal (an everywhere preview run of person_erasure_run), one row per organisation whose Data Protection Officer was asked. Ids, dates and a state code only - no name or free text. No foreign key into person. Not in the domain model: the approval endpoints of modality-event-server-lifecycle-plugin are the only way in.';
COMMENT ON COLUMN public.person_erasure_approval.state IS
    'pending | approved | declined | withdrawn (by the asker, pending rows only). A row past expires_at no longer counts, whatever its state.';
COMMENT ON COLUMN public.person_erasure_approval.expires_at IS
    'Until when an approval counts: asked_at + 14 days, set by the server (inside the one-month answer deadline of Art. 12(3)).';

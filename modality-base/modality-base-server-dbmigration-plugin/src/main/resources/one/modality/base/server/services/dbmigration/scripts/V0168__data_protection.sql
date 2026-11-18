-- V0168: data protection -- the DataProtection operation, the retention_policy ledger, and what an
-- event_erasure_run now records about its period and scope.
--
-- WHY. Removing guests' personal data after an event (erase_past_event(), V0077) was a super-administrator
-- action with one hard-coded period (three months, EventErasureConfig.RETENTION_MONTHS). It now belongs to
-- the Data Protection Officer: the back office's Data protection area, its endpoints in
-- modality-event-server-lifecycle-plugin (DataProtectionAccess), and periods the DPO sets instead of code.
--
-- DataProtection. The operation a "Data Protection Officer" role carries (seeded by V0163; the insert below is
-- a no-op wherever it ran). The endpoints ask for it through the authorization framework (EventErasureAccess,
-- OperationAuthority): held for a whole organisation, on an access row that is not read-only. A super
-- administrator does NOT pass without it (David, 2026-11-06: strictly DPO), because the operation is
-- explicit-only (V0166): a wildcard grant does not cover it. Granted to no one here: a super administrator builds
-- the role in Authorizations and assigns it, and that grant is visible there. NO grant_route: it gates
-- endpoints, not a page. frontend = false.
--
-- retention_policy. Append-only: a period is never edited in place. Setting one supersedes the current row
-- for the same stage and target and inserts the new one; removing an override only supersedes. So the table
-- is also the record of who decided what, and when -- the DPO's sign-off is the first 'default' row of a
-- stage, and until it exists that stage is locked (the endpoints refuse every removal).
--   stage  finalise = remove critical free text (V0077's scope); archive = anonymise the registration (a
--          later migration's routine; its period may be set before the routine exists).
--   scope  default (no ids), organization (organization_id), event (event_id). An ongoing event takes its
--          period from its own 'event' row only -- never inherited (David, 2026-11-06); a finished event
--          resolves event -> organization -> default.
-- No foreign keys to organization or event, like event_erasure_run: the record must outlive them, and a
-- back-office delete must not be refused because a period was once set (deletes rely on 23503).
--
-- event_erasure_run gains what a run was judged by: its stage, its kind (the whole event, or only the
-- bookings of people who left before departed_before -- ongoing events), the months it used (the commit's
-- drift check compares them with its dry run's), and whether a person or (later) a schedule started it.
-- Existing rows are V0077 runs on whole events, started by hand: the defaults say exactly that.
--
-- ANONYMISER. retention_policy is classified whole-table 'kept' in the aggregate repo's
-- scripts/gdpr-anonymise/10-anon-helpers.sql: periods, ids and staff person ids; `reason` is free text the
-- DPO types about a policy, not about a guest. event_erasure_run is already whole-table 'kept' -- its new
-- columns are a stage, a kind, a date, a count and a flag, none about a person.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only, explicit_only)
SELECT 'DataProtection', 'Data protection: retention periods, removals, anonymisation',
       true, false, false, false, false, true
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'DataProtection');

CREATE TABLE IF NOT EXISTS public.retention_policy (
    id                         serial      PRIMARY KEY,
    stage                      text        NOT NULL
        CONSTRAINT retention_policy_stage_chk CHECK (stage IN ('finalise', 'archive')),
    scope                      text        NOT NULL
        CONSTRAINT retention_policy_scope_chk CHECK (scope IN ('default', 'organization', 'event')),
    -- No foreign keys: see above.
    organization_id            integer,
    event_id                   integer,
    -- Months after the anchor: the later of the event's end and its recordings closing date for a finished
    -- event, the person's departure for an ongoing one.
    months                     smallint    NOT NULL
        CONSTRAINT retention_policy_months_chk CHECK (months BETWEEN 1 AND 240),
    reason                     text
        CONSTRAINT retention_policy_reason_len_chk CHECK (reason IS NULL OR length(reason) <= 500),
    -- Staff person ids, never names; no foreign key, like event_erasure_run.requested_by_person_id.
    set_by_person_id           integer     NOT NULL,
    set_at                     timestamptz NOT NULL DEFAULT now(),
    superseded_at              timestamptz,
    superseded_by_person_id    integer,
    CONSTRAINT retention_policy_target_chk CHECK (
           (scope = 'default'      AND organization_id IS NULL     AND event_id IS NULL)
        OR (scope = 'organization' AND organization_id IS NOT NULL AND event_id IS NULL)
        OR (scope = 'event'        AND organization_id IS NULL     AND event_id IS NOT NULL)),
    CONSTRAINT retention_policy_superseded_chk CHECK ((superseded_at IS NULL) = (superseded_by_person_id IS NULL))
);

-- One current row per stage and target.
CREATE UNIQUE INDEX IF NOT EXISTS retention_policy_current_default_idx
    ON public.retention_policy (stage) WHERE scope = 'default' AND superseded_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS retention_policy_current_organization_idx
    ON public.retention_policy (stage, organization_id) WHERE scope = 'organization' AND superseded_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS retention_policy_current_event_idx
    ON public.retention_policy (stage, event_id) WHERE scope = 'event' AND superseded_at IS NULL;

REVOKE ALL ON TABLE public.retention_policy FROM PUBLIC;
REVOKE ALL ON SEQUENCE public.retention_policy_id_seq FROM PUBLIC;

COMMENT ON TABLE public.retention_policy IS
    'Retention periods set by the Data Protection Officer (V0168), append-only: a change supersedes the current row. '
    'No current default row for a stage = that stage is locked. Not in the domain model: the dpo/retention endpoints '
    'of modality-event-server-lifecycle-plugin are the only way in.';
COMMENT ON COLUMN public.retention_policy.stage IS
    'finalise = remove critical free text (erase_past_event); archive = anonymise the registration.';
COMMENT ON COLUMN public.retention_policy.scope IS
    'default | organization | event. A finished event resolves event -> organization -> default; an ongoing event uses its event row only.';
COMMENT ON COLUMN public.retention_policy.months IS
    'Months after the anchor: the later of event end and recordings closing date (finished event), or the person''s departure (ongoing event).';
COMMENT ON COLUMN public.retention_policy.reason IS
    'Why the DPO set this period, typed by the DPO. About the policy, never about a guest.';
COMMENT ON COLUMN public.retention_policy.set_by_person_id IS
    'The staff member (holding DataProtection) who set it. A person id, never a name; no foreign key.';
COMMENT ON COLUMN public.retention_policy.superseded_at IS
    'When a later change replaced or removed this row. NULL = current.';

ALTER TABLE public.event_erasure_run
    ADD COLUMN IF NOT EXISTS stage text NOT NULL DEFAULT 'finalise',
    ADD COLUMN IF NOT EXISTS kind text NOT NULL DEFAULT 'event',
    ADD COLUMN IF NOT EXISTS departed_before date,
    ADD COLUMN IF NOT EXISTS retention_months smallint,
    ADD COLUMN IF NOT EXISTS triggered_by text NOT NULL DEFAULT 'manual';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'event_erasure_run_stage_chk') THEN
        ALTER TABLE public.event_erasure_run
            ADD CONSTRAINT event_erasure_run_stage_chk CHECK (stage IN ('finalise', 'archive')),
            ADD CONSTRAINT event_erasure_run_kind_chk CHECK (kind IN ('event', 'departed')),
            ADD CONSTRAINT event_erasure_run_departed_chk CHECK ((kind = 'departed') = (departed_before IS NOT NULL)),
            ADD CONSTRAINT event_erasure_run_triggered_by_chk CHECK (triggered_by IN ('manual', 'auto'));
    END IF;
END $$;

COMMENT ON COLUMN public.event_erasure_run.stage IS 'finalise (erase_past_event) or archive.';
COMMENT ON COLUMN public.event_erasure_run.kind IS
    'event = every booking of the event; departed = only bookings whose person left before departed_before (ongoing events).';
COMMENT ON COLUMN public.event_erasure_run.departed_before IS 'For a departed run: the cutoff, today minus the event''s period.';
COMMENT ON COLUMN public.event_erasure_run.retention_months IS
    'The period the run was judged by (retention_policy). NULL for runs before V0168.';
COMMENT ON COLUMN public.event_erasure_run.triggered_by IS 'manual = started by a person; auto = a schedule (not built yet).';

-- Ownership, as V0146: hand the new table (and its serial sequence) to whoever owns public.person.
DO $$
DECLARE
    app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        BEGIN
            EXECUTE format('ALTER TABLE public.retention_policy OWNER TO %I', app_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0168: could not realign the owner of public.retention_policy to % (%)', app_owner, SQLERRM;
        END;
    END IF;
END $$;

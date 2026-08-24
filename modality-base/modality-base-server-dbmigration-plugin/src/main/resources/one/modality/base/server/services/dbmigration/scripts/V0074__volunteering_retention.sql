-- Volunteering GDPR retention: installs the sweep machinery (functions + audit table).
--
-- Volunteering runs against a perpetual 'ongoing' event that never closes, so the
-- agreed event-closure clean-up will never fire for it. Retention is therefore keyed
-- on each application's own lifecycle: its stay dates (end_date) and its last recorded
-- activity (volunteering_timeline). This migration only INSTALLS the machinery —
-- nothing here runs by itself and shipping it changes no data. The retention job
-- (kbs-server-volunteeringretention-plugin) calls volunteering_retention_sweep() on a
-- schedule, dry-run by default until the retention periods are ratified, and every run
-- (dry or live) records a counts-only audit row in volunteering_retention_run.
--
-- Field dispositions broadly follow the anonymiser's classification (aggregate repo,
-- scripts/gdpr-anonymise/10-anon-helpers.sql) and the wholesale-tag convention of
-- scripts/erase-past-event-special-needs.sql: free text is replaced with a visible tag
-- so staff can tell "deliberately removed" from "there never was any"; credentials and
-- format-bearing fields are NULLed. NULL values are left NULL. One deliberate
-- divergence: the manifest KEEPS diet on staging (DPO sign-off for test data), but
-- retention still erases a real dietary need at stage 1 because it can imply religion
-- or health; the default 'none' means no requirement and is left alone. The applicant photo is
-- NOT touched here: its CDN object must be deleted first, so the retention job owns
-- photo (volunteering_retention_due_photos lists what is due), and stage 2 refuses to
-- delete a row while photo is still set — a row deletion must never orphan a CDN
-- object. Operations reference: docs/operations/volunteering-retention.md (aggregate).
--
-- NOTE for the planned pre-launch column drops (health_info, criminal_convictions,
-- reference_phone, dates_notes, visa_applied_date, visa_approved_date, date_of_birth):
-- the sweep still covers the droppable text columns because KBS2-imported rows hold
-- real values today. The migration that drops any of them MUST, in the same script,
-- CREATE OR REPLACE volunteering_retention_stage1_pending() and
-- volunteering_retention_sweep() without those columns — the functions take the table's
-- row type, so a dropped column breaks them at their next call otherwise.

CREATE TABLE IF NOT EXISTS volunteering_retention_run (
    id          serial PRIMARY KEY,
    run_at      timestamptz NOT NULL DEFAULT now(),
    dry_run     boolean NOT NULL,
    batch_limit integer NOT NULL,
    periods     jsonb NOT NULL,
    counts      jsonb NOT NULL,
    duration_ms integer,
    caller      text
);

COMMENT ON TABLE volunteering_retention_run IS
    'One row per volunteering retention sweep (dry or live): the period parameters used '
    'and per-rule row counts. Bookkeeping only — never holds personal data.';

-- Last recorded activity: the newest timeline entry (written on every status change),
-- floored at created_at. NULL created_at (defensive: imports) yields NULL, and a NULL
-- anchor never satisfies "anchor + period < now()" — unknown age means keep.
CREATE OR REPLACE FUNCTION volunteering_retention_last_activity(a volunteering_application)
RETURNS timestamp LANGUAGE sql STABLE AS $$
    SELECT GREATEST(
        COALESCE((SELECT max(t.date) FROM volunteering_timeline t WHERE t.application_id = a.id),
                 a.created_at),
        a.created_at)
$$;

-- Did this applicant actually serve? Status reaches 'active'/'completed' only through
-- service, and document.arrived covers bookings marked arrived without the status
-- catching up.
CREATE OR REPLACE FUNCTION volunteering_retention_served(a volunteering_application)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT a.status IN ('active', 'completed')
        OR EXISTS (SELECT 1 FROM document d WHERE d.id = a.document_id AND d.arrived)
$$;

-- The retention clock's zero point. For applications refused or abandoned
-- ('rejected'/'cancelled'/'no-response') the relationship ended at the last action.
-- Every other status still anticipates or contains the stay, so the clock never
-- starts before the planned end_date — an application confirmed in January for an
-- October stay must keep its data through the stay, however quiet its timeline.
CREATE OR REPLACE FUNCTION volunteering_retention_anchor(a volunteering_application)
RETURNS timestamp LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN a.status IN ('rejected', 'cancelled', 'no-response')
            THEN volunteering_retention_last_activity(a)
        ELSE GREATEST(a.end_date::timestamp, volunteering_retention_last_activity(a))
    END
$$;

-- True while any stage-1 field still holds a value the sweep would change. Keeps
-- re-runs exact no-ops and the audit counts honest. This list and the UPDATE's SET
-- list in the sweep below MUST stay column-for-column in step — the rehearsal's
-- completeness assertion (no due row stays pending after a live run) is what catches
-- a column present here but missing there; a NEW personal-data column must be added
-- to both, and the runbook's 16a note covers removals.
CREATE OR REPLACE FUNCTION volunteering_retention_stage1_pending(a volunteering_application, p_tag text)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT (a.fitness_level IS NOT NULL AND a.fitness_level <> p_tag)
        OR (a.health_info IS NOT NULL AND a.health_info <> p_tag)
        OR (a.criminal_convictions IS NOT NULL AND a.criminal_convictions <> p_tag)
        OR (a.immigration_status_other IS NOT NULL AND a.immigration_status_other <> p_tag)
        OR (a.visa_notes IS NOT NULL AND a.visa_notes <> p_tag)
        OR (a.visit_reason_other IS NOT NULL AND a.visit_reason_other <> p_tag)
        OR (a.why_volunteer IS NOT NULL AND a.why_volunteer <> p_tag)
        OR (a.previous_experience IS NOT NULL AND a.previous_experience <> p_tag)
        OR (a.previous_nkt_details IS NOT NULL AND a.previous_nkt_details <> p_tag)
        OR (a.additional_info IS NOT NULL AND a.additional_info <> p_tag)
        OR (a.notes IS NOT NULL AND a.notes <> p_tag)
        OR (a.date_notes IS NOT NULL AND a.date_notes <> p_tag)
        OR (a.dates_notes IS NOT NULL AND a.dates_notes <> p_tag)
        OR (a.occupation IS NOT NULL AND a.occupation <> p_tag)
        OR (a.how_heard_about_us IS NOT NULL AND a.how_heard_about_us <> p_tag)
        OR (a.emergency_contact_name IS NOT NULL AND a.emergency_contact_name <> p_tag)
        OR (a.emergency_contact_relationship IS NOT NULL AND a.emergency_contact_relationship <> p_tag)
        OR (a.reference_name IS NOT NULL AND a.reference_name <> p_tag)
        OR (a.reference_job_title IS NOT NULL AND a.reference_job_title <> p_tag)
        OR (a.reference_notes IS NOT NULL AND a.reference_notes <> p_tag)
        OR a.emergency_contact_phone IS NOT NULL
        OR a.reference_phone IS NOT NULL
        OR a.reference_email IS NOT NULL
        OR (a.diet IS NOT NULL AND a.diet <> 'none')  -- 'none' is the column default: no requirement, no data
        OR a.kbs_diet_item_id IS NOT NULL
        OR a.share_code IS NOT NULL
        OR a.arrival_confirmation_token IS NOT NULL
$$;

-- True once the application's full retention period (stage 2) has passed.
-- Shared by the blocked-on-photo gauge, the dry count and the live delete, so the
-- three can never disagree.
CREATE OR REPLACE FUNCTION volunteering_retention_stage2_due(
    a volunteering_application, p_never_served_months integer, p_served_months integer)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT volunteering_retention_anchor(a)
           + make_interval(months => CASE WHEN volunteering_retention_served(a)
                                          THEN p_served_months
                                          ELSE p_never_served_months END) < now()
$$;

-- Photos whose stage-1 moment has passed. The retention job deletes the CDN object
-- first and NULLs photo only on success, so a failed delete is simply re-listed here
-- on the next run. Random order, deliberately: with a stable order a handful of
-- persistently failing keys would occupy the head of every batch and starve the rest
-- of the backlog for good. A NULL limit means no limit (the sweep's photo_due gauge
-- reuses this function that way).
--
-- A key claimed by more than one application is never listed. The photo column is
-- written by the public application form, so one applicant can name another's storage
-- key; deleting on the first row's schedule would destroy a picture the second row
-- still needs. Shared keys surface as stage2_blocked_on_photo and want a manual ruling.
-- (The job separately refuses any value that is not a server-issued volunteers/ key.)
CREATE OR REPLACE FUNCTION volunteering_retention_due_photos(p_stage1_months integer, p_limit integer)
RETURNS TABLE(id integer, photo text) LANGUAGE sql STABLE AS $$
    SELECT a.id, a.photo::text
    FROM volunteering_application a
    WHERE a.photo IS NOT NULL
      AND volunteering_retention_anchor(a) + make_interval(months => p_stage1_months) < now()
      AND NOT EXISTS (SELECT 1 FROM volunteering_application b
                      WHERE b.photo = a.photo AND b.id <> a.id)
    ORDER BY random()
    LIMIT p_limit
$$;

CREATE OR REPLACE FUNCTION volunteering_retention_sweep(
    p_dry_run                    boolean,
    p_batch_limit                integer,
    p_token_grace_days           integer,
    p_share_code_max_age_days    integer,
    p_stage1_months              integer,
    p_special_needs_months       integer,
    p_stage2_never_served_months integer,
    p_stage2_served_months       integer,
    p_caller                     text
) RETURNS TABLE(rule text, affected bigint)
LANGUAGE plpgsql AS $fn$
-- Count semantics: on a dry run every rule reports its FULL backlog (no batch limit —
-- that is the sizing number the ratification process watches); on a live run the
-- stage-1/stage-2 rules report the rows actually written this run, bounded by
-- p_batch_limit. photo_due and stage2_blocked_on_photo are gauges of current state in
-- both modes, not per-run deltas.
DECLARE
    tag CONSTANT text := '[personal data removed (GDPR)]';
    started timestamptz := clock_timestamp();
    n bigint;
    results jsonb := '{}'::jsonb;
BEGIN
    -- Blue/green deploys briefly run two servers; only one sweep may write at a time.
    -- The loser reports itself and does nothing — not even an audit row, so the audit
    -- trail stays one row per real run.
    IF NOT pg_try_advisory_xact_lock(hashtextextended('volunteering_retention_sweep', 0)) THEN
        RETURN QUERY SELECT 'skipped_lock'::text, 1::bigint;
        RETURN;
    END IF;

    -- Rule 0a: arrival-confirmation bearer tokens, once used or expired plus grace.
    IF p_dry_run THEN
        SELECT count(*) INTO n FROM volunteering_application a
        WHERE a.arrival_confirmation_token IS NOT NULL
          AND (a.arrival_token_used_at    < now() - make_interval(days => p_token_grace_days)
            OR a.arrival_token_expires_at < now() - make_interval(days => p_token_grace_days));
    ELSE
        UPDATE volunteering_application a
           SET arrival_confirmation_token = NULL
         WHERE a.arrival_confirmation_token IS NOT NULL
           AND (a.arrival_token_used_at    < now() - make_interval(days => p_token_grace_days)
             OR a.arrival_token_expires_at < now() - make_interval(days => p_token_grace_days));
        GET DIAGNOSTICS n = ROW_COUNT;
    END IF;
    results := results || jsonb_build_object('token_arrival', n);
    RETURN QUERY SELECT 'token_arrival'::text, n;

    -- Rule 0b: date-proposal action tokens (expiry columns are DATE-typed).
    IF p_dry_run THEN
        SELECT count(*) INTO n FROM volunteering_date_proposal p
        WHERE p.action_token IS NOT NULL
          AND (p.action_token_used_at    < current_date - p_token_grace_days
            OR p.action_token_expires_at < current_date - p_token_grace_days);
    ELSE
        UPDATE volunteering_date_proposal p
           SET action_token = NULL
         WHERE p.action_token IS NOT NULL
           AND (p.action_token_used_at    < current_date - p_token_grace_days
             OR p.action_token_expires_at < current_date - p_token_grace_days);
        GET DIAGNOSTICS n = ROW_COUNT;
    END IF;
    results := results || jsonb_build_object('token_proposal', n);
    RETURN QUERY SELECT 'token_proposal'::text, n;

    -- Rule 0c: Home Office share codes expire 90 days after issue. We do not know the
    -- issue date, so the clock runs from the application's LAST ACTIVITY, not its
    -- creation: staff can enter a fresh code on an old application (the back-office
    -- field editor allows it), and a creation-keyed rule would wipe that code the same
    -- night. A file quiet for the full period cannot hold a live code entered after
    -- its last recorded action; the narrow residual (a code typed without any status
    -- change and then 90 quiet days) is accepted — the code has expired at the Home
    -- Office by then anyway. NULL rather than tag: varchar(20) cannot hold the tag,
    -- and the anonymiser treats credentials the same way.
    IF p_dry_run THEN
        SELECT count(*) INTO n FROM volunteering_application a
        WHERE a.share_code IS NOT NULL
          AND volunteering_retention_last_activity(a) < now() - make_interval(days => p_share_code_max_age_days);
    ELSE
        UPDATE volunteering_application a
           SET share_code = NULL
         WHERE a.share_code IS NOT NULL
           AND volunteering_retention_last_activity(a) < now() - make_interval(days => p_share_code_max_age_days);
        GET DIAGNOSTICS n = ROW_COUNT;
    END IF;
    results := results || jsonb_build_object('share_code', n);
    RETURN QUERY SELECT 'share_code'::text, n;

    -- Stage 1: erase the fields whose purpose ends with the stay (or with the refusal).
    -- Tagged columns keep NULL as NULL (CASE yields NULL when the column is NULL, so a
    -- never-answered field is not tagged); NULLed columns are credentials, third-party
    -- contact routes, or FK/format fields the tag cannot represent. This SET list and
    -- volunteering_retention_stage1_pending above MUST stay column-for-column in step.
    IF p_dry_run THEN
        SELECT count(*) INTO n FROM volunteering_application a
        WHERE volunteering_retention_anchor(a) + make_interval(months => p_stage1_months) < now()
          AND volunteering_retention_stage1_pending(a, tag);
    ELSE
        UPDATE volunteering_application a
           SET fitness_level                  = CASE WHEN a.fitness_level                  IS NOT NULL THEN tag END,
               health_info                    = CASE WHEN a.health_info                    IS NOT NULL THEN tag END,
               criminal_convictions           = CASE WHEN a.criminal_convictions           IS NOT NULL THEN tag END,
               immigration_status_other       = CASE WHEN a.immigration_status_other       IS NOT NULL THEN tag END,
               visa_notes                     = CASE WHEN a.visa_notes                     IS NOT NULL THEN tag END,
               visit_reason_other             = CASE WHEN a.visit_reason_other             IS NOT NULL THEN tag END,
               why_volunteer                  = CASE WHEN a.why_volunteer                  IS NOT NULL THEN tag END,
               previous_experience            = CASE WHEN a.previous_experience            IS NOT NULL THEN tag END,
               previous_nkt_details           = CASE WHEN a.previous_nkt_details           IS NOT NULL THEN tag END,
               additional_info                = CASE WHEN a.additional_info                IS NOT NULL THEN tag END,
               notes                          = CASE WHEN a.notes                          IS NOT NULL THEN tag END,
               date_notes                     = CASE WHEN a.date_notes                     IS NOT NULL THEN tag END,
               dates_notes                    = CASE WHEN a.dates_notes                    IS NOT NULL THEN tag END,
               occupation                     = CASE WHEN a.occupation                     IS NOT NULL THEN tag END,
               how_heard_about_us             = CASE WHEN a.how_heard_about_us             IS NOT NULL THEN tag END,
               emergency_contact_name         = CASE WHEN a.emergency_contact_name         IS NOT NULL THEN tag END,
               emergency_contact_relationship = CASE WHEN a.emergency_contact_relationship IS NOT NULL THEN tag END,
               reference_name                 = CASE WHEN a.reference_name                 IS NOT NULL THEN tag END,
               reference_job_title            = CASE WHEN a.reference_job_title            IS NOT NULL THEN tag END,
               reference_notes                = CASE WHEN a.reference_notes                IS NOT NULL THEN tag END,
               emergency_contact_phone        = NULL,
               reference_phone                = NULL,
               reference_email                = NULL,
               diet                           = CASE WHEN a.diet = 'none' THEN a.diet END,
               kbs_diet_item_id               = NULL,
               share_code                     = NULL,
               arrival_confirmation_token     = NULL
         WHERE a.id IN (
               SELECT a2.id FROM volunteering_application a2
               WHERE volunteering_retention_anchor(a2) + make_interval(months => p_stage1_months) < now()
                 AND volunteering_retention_stage1_pending(a2, tag)
               ORDER BY a2.id
               LIMIT p_batch_limit);
        GET DIAGNOSTICS n = ROW_COUNT;
    END IF;
    results := results || jsonb_build_object('stage1_fields', n);
    RETURN QUERY SELECT 'stage1_fields'::text, n;

    -- Stage 1b: area staffing needs — free text often explains a health-related reason.
    -- Keyed on the need's own dates (it has no person or application link). The table
    -- is small; no batch limit.
    IF p_dry_run THEN
        SELECT count(*) INTO n FROM volunteering_special_needs s
        WHERE s.reason IS NOT NULL AND s.reason <> tag
          AND s.end_date + make_interval(months => p_special_needs_months) < now();
    ELSE
        UPDATE volunteering_special_needs s
           SET reason = tag
         WHERE s.reason IS NOT NULL AND s.reason <> tag
           AND s.end_date + make_interval(months => p_special_needs_months) < now();
        GET DIAGNOSTICS n = ROW_COUNT;
    END IF;
    results := results || jsonb_build_object('special_needs_reason', n);
    RETURN QUERY SELECT 'special_needs_reason'::text, n;

    -- Photos due for CDN deletion (informational — the retention job does the work,
    -- and only where CDN deletion is enabled for the environment). Reuses the job's
    -- own listing function so gauge and worklist cannot drift apart.
    SELECT count(*) INTO n FROM volunteering_retention_due_photos(p_stage1_months, NULL);
    results := results || jsonb_build_object('photo_due', n);
    RETURN QUERY SELECT 'photo_due'::text, n;

    -- Stage 2: delete the application row once the retention period has passed.
    -- Children (assignments, communications, proposals, timeline) go with it via
    -- ON DELETE CASCADE; the linked booking document is deliberately untouched.
    -- 'photo IS NULL' is the interlock: a row is never deleted while its CDN object
    -- may still exist, otherwise the object would be orphaned with no handle left.
    SELECT count(*) INTO n FROM volunteering_application a
    WHERE a.photo IS NOT NULL
      AND volunteering_retention_stage2_due(a, p_stage2_never_served_months, p_stage2_served_months);
    results := results || jsonb_build_object('stage2_blocked_on_photo', n);
    RETURN QUERY SELECT 'stage2_blocked_on_photo'::text, n;

    IF p_dry_run THEN
        SELECT count(*) INTO n FROM volunteering_application a
        WHERE a.photo IS NULL
          AND volunteering_retention_stage2_due(a, p_stage2_never_served_months, p_stage2_served_months);
    ELSE
        DELETE FROM volunteering_application a
         WHERE a.id IN (
               SELECT a2.id FROM volunteering_application a2
               WHERE a2.photo IS NULL
                 AND volunteering_retention_stage2_due(a2, p_stage2_never_served_months, p_stage2_served_months)
               ORDER BY a2.id
               LIMIT p_batch_limit);
        GET DIAGNOSTICS n = ROW_COUNT;
    END IF;
    results := results || jsonb_build_object('stage2_deleted', n);
    RETURN QUERY SELECT 'stage2_deleted'::text, n;

    INSERT INTO volunteering_retention_run (dry_run, batch_limit, periods, counts, duration_ms, caller)
    VALUES (p_dry_run, p_batch_limit,
            jsonb_build_object(
                'token_grace_days',           p_token_grace_days,
                'share_code_max_age_days',    p_share_code_max_age_days,
                'stage1_months',              p_stage1_months,
                'special_needs_months',       p_special_needs_months,
                'stage2_never_served_months', p_stage2_never_served_months,
                'stage2_served_months',       p_stage2_served_months),
            results,
            (extract(epoch FROM clock_timestamp() - started) * 1000)::integer,
            p_caller);
END;
$fn$;

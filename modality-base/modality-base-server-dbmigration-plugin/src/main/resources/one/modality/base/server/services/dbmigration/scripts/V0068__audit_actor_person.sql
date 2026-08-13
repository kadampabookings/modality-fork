-- V0068: the audit trail records WHO as a person id, not only as free text.
--
-- WHY A COLUMN. V0063 and V0065 record what changed and when, and identify the actor through
-- `kbs.audit_note` — a free-text setting that, until the submit provider started stamping the
-- principal, only maintenance scripts ever set. Text answers a human reading one row; it cannot
-- answer "everything this staff member did", cannot join to a name, and drifts in format.
--
-- The precedent is History, which carries BOTH: `userPerson` (typed) and `username` (text).
-- That pairing is not redundancy — it is exactly the merge problem below.
--
-- WHY NOT A FOREIGN KEY. Same reasoning as V0063's other person references: an audit row must
-- outlive a hard-deleted person, which a CASCADE would erase and a RESTRICT would forbid; and
-- `person` already has 28 inbound FKs, the ones lacking ON DELETE being precisely what makes
-- MergeCustomersDialog's hard merge abort. A 29th would make that worse. So: a plain integer,
-- joinable and indexable, enforcing nothing.
--
-- WHAT MERGING DOES TO IT. MergeCustomersDialog hand-enumerates the tables it re-points
-- (Document.person, History.userPerson, Error.userPerson, Person.accountPerson, Driver.person)
-- and then hard-deletes the merged person. Anything outside that list is left pointing at an id
-- that no longer exists — which is already true of the person references in both audit tables.
-- These columns must therefore be added to that list; the domain-model entries that make it
-- possible from the back office ship alongside. The `note` stays as the frozen record of what
-- was true at the time, exactly as History.username does next to History.userPerson.

ALTER TABLE public.person_account_move
    ADD COLUMN IF NOT EXISTS changed_by_person_id integer;

ALTER TABLE public.person_link_change
    ADD COLUMN IF NOT EXISTS changed_by_person_id integer;

COMMENT ON COLUMN public.person_account_move.changed_by_person_id IS
    'Person who made the change, from kbs.audit_person_id. Plain integer, not a foreign key: an '
    'audit row must outlive a hard-deleted person. Re-point it when merging persons.';
COMMENT ON COLUMN public.person_link_change.changed_by_person_id IS
    'Person who made the change, from kbs.audit_person_id. Plain integer, not a foreign key: an '
    'audit row must outlive a hard-deleted person. Re-point it when merging persons.';

CREATE INDEX IF NOT EXISTS person_account_move_actor_idx ON public.person_account_move (changed_by_person_id);
CREATE INDEX IF NOT EXISTS person_link_change_actor_idx  ON public.person_link_change  (changed_by_person_id);

-- Where the actor id comes from, in order of preference:
--
--   1. `kbs.audit_person_id`, set explicitly. Nothing sets it yet — it is here so a future
--      typed hand-off from the server needs no migration to take effect, only a value.
--   2. Parsed out of `kbs.audit_note`, which the submit provider already stamps as
--      "user:person=6801,account=7397" from ModalityUserPrincipal.toString().
--
-- Parsing a format is not the design I would choose freely; supplying the id directly would
-- mean an interface shared between webfx-stack and Modality, and modality-crm-shared-authn's
-- module-info is WebFX-generated, so that means a webfx.xml dependency plus a regeneration
-- sweep for one integer. The coupling is instead pinned from the Java side by a test on
-- ModalityUserPrincipal.toString(), which names this function so the link is discoverable from
-- both ends. When the explicit setting arrives, this fallback simply stops being reached.
--
-- Reading it must NEVER abort the write it is recording: a bad value would otherwise raise
-- 22P02 out of a trigger and take down the booking that fired it. Failures yield null instead.
CREATE OR REPLACE FUNCTION public.kbs_audit_person_id() RETURNS integer
    LANGUAGE plpgsql
AS $$
BEGIN
    RETURN coalesce(
        nullif(current_setting('kbs.audit_person_id', true), '')::integer,
        -- Only matches the principal form; a script naming itself in the note yields null,
        -- which is correct — a maintenance run is not a person.
        substring(nullif(current_setting('kbs.audit_note', true), '') from 'person=(\d+)')::integer);
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END $$;

-- ─── Both triggers now record the actor ───────────────────────────────
--
-- Replaced inside a DO block that tolerates insufficient_privilege. These functions are owned by
-- whichever role first ran V0063/V0065 — on staging that is kbs3_staging_server, NOT the `kbs`
-- that owns the tables — and only an owner may CREATE OR REPLACE. A database whose connect role
-- has since changed would otherwise fail here, and a failing migration aborts the server's whole
-- boot chain. Degrading is the right trade: the columns still exist, the trail keeps recording
-- everything except the actor, and the log says exactly what to fix.

DO $do$
BEGIN
    EXECUTE $fn$
CREATE OR REPLACE FUNCTION public.trigger_person_audit_account_move() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- `UPDATE OF frontend_account_id` fires whenever the column appears in the SET list,
    -- including no-op writes; only real moves are worth recording.
    IF NEW.frontend_account_id IS DISTINCT FROM OLD.frontend_account_id THEN
        INSERT INTO person_account_move (
            person_id, from_account_id, to_account_id, from_account_username,
            was_owner, was_removed, note, changed_by_person_id)
        VALUES (
            OLD.id,
            OLD.frontend_account_id,
            NEW.frontend_account_id,
            (SELECT fa.username FROM frontend_account fa WHERE fa.id = OLD.frontend_account_id),
            OLD.owner IS TRUE,
            OLD.removed IS TRUE,
            nullif(current_setting('kbs.audit_note', true), ''),
            public.kbs_audit_person_id());
    END IF;
    RETURN NEW;
END $$;
    $fn$;
EXCEPTION WHEN insufficient_privilege THEN
    RAISE WARNING 'V0068: cannot replace trigger_person_audit_account_move (owned by %). '
                  'Account moves will record no actor until an owner re-runs this function body.',
                  (SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE proname = 'trigger_person_audit_account_move');
END $do$;

DO $do$
BEGIN
    EXECUTE $fn$
CREATE OR REPLACE FUNCTION public.trigger_person_audit_link_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    kind text;
BEGIN
    IF NEW.account_person_id IS NOT DISTINCT FROM OLD.account_person_id
       AND NEW.account_person_revoked_date IS NOT DISTINCT FROM OLD.account_person_revoked_date THEN
        RETURN NEW;
    END IF;

    kind := CASE
        WHEN OLD.account_person_id IS NULL AND NEW.account_person_id IS NOT NULL THEN 'linked'
        WHEN OLD.account_person_id IS NOT NULL AND NEW.account_person_id IS NULL THEN 'unlinked'
        WHEN OLD.account_person_revoked_date IS NULL
             AND NEW.account_person_revoked_date IS NOT NULL THEN 'revoked'
        WHEN OLD.account_person_revoked_date IS NOT NULL
             AND NEW.account_person_revoked_date IS NULL THEN 'restored'
        ELSE 'relinked'
    END;

    INSERT INTO person_link_change (
        person_id, account_id, change_type,
        old_account_person_id, new_account_person_id,
        old_revoked_date, new_revoked_date, note, changed_by_person_id)
    VALUES (
        OLD.id,
        OLD.frontend_account_id,
        kind,
        OLD.account_person_id,
        NEW.account_person_id,
        OLD.account_person_revoked_date,
        NEW.account_person_revoked_date,
        nullif(current_setting('kbs.audit_note', true), ''),
        public.kbs_audit_person_id());

    RETURN NEW;
END $$;
    $fn$;
EXCEPTION WHEN insufficient_privilege THEN
    RAISE WARNING 'V0068: cannot replace trigger_person_audit_link_change (owned by %). '
                  'Link changes will record no actor until an owner re-runs this function body.',
                  (SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE proname = 'trigger_person_audit_link_change');
END $do$;

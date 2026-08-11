-- V0065: revoking a booking link stops being a soft-delete, and leaves a trail.
--
-- WHAT LINKING IS. `person.account_person_id` marks a member row as standing in for someone
-- whose real account is elsewhere. It grants that person access to the bookings on the row —
-- orders shared with the booker, materials transferred to them — and it lets the booker keep
-- booking for them without asking again. It is, in effect, a standing permission.
--
-- HOW IT WAS WITHDRAWN. `revokeManagerAccess` set **removed = true** on the member row in the
-- booker's account (and on any reciprocal row), and deleted the Invitation. It worked, in that
-- the row vanished from the booker's member list and picker so they could no longer book for
-- that person, while `account_person_id` survived untouched so the beneficiary kept everything
-- already booked for them. But it withdraws a permission by deleting a record, and the two are
-- not the same thing:
--
--   * RE-ADDING MAKES A DUPLICATE. The row is invisible to the booker afterwards, so a change
--     of mind means adding the person again — a second person row for one human in one
--     account, the older one holding all the history. That is exactly the mess that took
--     merge-duplicate-signup-accounts.sql, V0063 and a week of repair to clean up.
--   * `removed` MEANS DELETED. This row is not: it holds live bookings and a live link that
--     still grants media access. A "deleted" row that silently grants access is a trap for
--     the next person who assumes removed rows are inert.
--   * THERE IS NO WAY BACK. Nothing un-removes it, and no screen offers to.
--   * THE INVITATION IS DELETED, taking the record of who invited whom with it.
--
-- WHAT REPLACES IT. A nullable date: NULL means the link is live, a date means it was
-- withdrawn then. A date rather than a boolean because it answers "when" for support, and
-- `date` rather than a timestamp because that is what every sibling marker on this table
-- already is — gender_changed_date (V0034), details_edited_date, details_confirmed_date —
-- and the domain model maps that type to Temporal.PlainDate. The exact instant is not lost:
-- person_link_change.changed_at keeps it.
--
-- The link itself STAYS. Revoking must not confiscate what was already given: the bookings
-- were made for that person and the materials are theirs, so `account_person_id` is untouched
-- and every access query keeps working unchanged. What the flag governs is the FUTURE — the
-- member list and the booking picker hide revoked rows, so the booker cannot keep booking on
-- that link, and the beneficiary's "who can book for me" list drops the entry.
--
-- Restoring is then a one-field write to the same row, with the history intact.

ALTER TABLE public.person
    ADD COLUMN IF NOT EXISTS account_person_revoked_date date;

COMMENT ON COLUMN public.person.account_person_revoked_date IS
    'When the account_person_id link was withdrawn. NULL = live. The link itself is kept so '
    'the linked person retains access to what was already booked for them; this only stops '
    'the holder of this row booking on it again.';

-- Partial index: the queries that care ask for live links only, and revoked rows are the
-- rare case.
CREATE INDEX IF NOT EXISTS person_account_person_live_idx
    ON public.person (account_person_id)
    WHERE account_person_id IS NOT NULL AND account_person_revoked_date IS NULL;

-- ─── Audit ────────────────────────────────────────────────────────────
--
-- A sibling of person_account_move (V0063) rather than more columns on it: that table answers
-- "which account does this person belong to", this one answers "who was allowed to book for
-- whom, and until when". Same principles, and for the same reasons — a trigger so that the
-- back office, the front office, maintenance scripts and hand-written SQL all feed one trail
-- without knowing it exists, and no foreign keys so a row outlives a hard-deleted person
-- (`person` already has 28 inbound FKs, and the ones without ON DELETE are what make
-- MergeCustomersDialog's hard merge abort — a 29th blocker would make that worse).

CREATE TABLE IF NOT EXISTS public.person_link_change (
    id                     serial PRIMARY KEY,
    -- The member row whose link changed, and the account holding it: the booker's side.
    person_id              integer     NOT NULL,
    account_id             integer,
    -- 'linked' | 'revoked' | 'restored' | 'unlinked'. Text rather than an enum so a later
    -- kind of change does not need a type migration to be recordable.
    change_type            text        NOT NULL,
    -- Both sides of the link as it stood, so a reversal has everything it needs.
    old_account_person_id  integer,
    new_account_person_id  integer,
    old_revoked_date       date,
    new_revoked_date       date,
    changed_at             timestamptz NOT NULL DEFAULT now(),
    changed_by             text        NOT NULL DEFAULT current_user,
    -- Free-text context via `SET LOCAL kbs.audit_note = '...'`, as V0063 does: staff actions
    -- leave it null, bulk runs name themselves so one is distinguishable from the other.
    note                   text
);

CREATE INDEX IF NOT EXISTS person_link_change_person_idx ON public.person_link_change (person_id);
CREATE INDEX IF NOT EXISTS person_link_change_target_idx ON public.person_link_change (new_account_person_id);

CREATE OR REPLACE FUNCTION public.trigger_person_audit_link_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    kind text;
BEGIN
    -- `UPDATE OF <col>` fires whenever the column is in the SET list, including no-op writes,
    -- so compare before recording anything.
    IF NEW.account_person_id IS NOT DISTINCT FROM OLD.account_person_id
       AND NEW.account_person_revoked_date IS NOT DISTINCT FROM OLD.account_person_revoked_date THEN
        RETURN NEW;
    END IF;

    kind := CASE
        -- Order matters: a row can gain a link and a revocation in one statement, and the
        -- link is the more informative half.
        WHEN OLD.account_person_id IS NULL AND NEW.account_person_id IS NOT NULL THEN 'linked'
        WHEN OLD.account_person_id IS NOT NULL AND NEW.account_person_id IS NULL THEN 'unlinked'
        WHEN OLD.account_person_revoked_date IS NULL
             AND NEW.account_person_revoked_date IS NOT NULL THEN 'revoked'
        WHEN OLD.account_person_revoked_date IS NOT NULL
             AND NEW.account_person_revoked_date IS NULL THEN 'restored'
        -- Link re-pointed at a different person, or a revocation re-dated.
        ELSE 'relinked'
    END;

    INSERT INTO person_link_change (
        person_id, account_id, change_type,
        old_account_person_id, new_account_person_id,
        old_revoked_date, new_revoked_date, note)
    VALUES (
        OLD.id,
        OLD.frontend_account_id,
        kind,
        OLD.account_person_id,
        NEW.account_person_id,
        OLD.account_person_revoked_date,
        NEW.account_person_revoked_date,
        nullif(current_setting('kbs.audit_note', true), ''));

    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS audit_link_change ON public.person;
CREATE TRIGGER audit_link_change
    AFTER UPDATE OF account_person_id, account_person_revoked_date ON public.person
    FOR EACH ROW EXECUTE FUNCTION public.trigger_person_audit_link_change();

-- Ownership, so every role can write the audit rows the trigger inserts. Without this the
-- table belongs to the migration's connect role and every other role gets "permission denied"
-- the moment a link changes — the failure V0064 exists to repair.
DO $$
DECLARE person_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO person_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF person_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.person_link_change OWNER TO %I', person_owner);
        EXECUTE format('ALTER SEQUENCE public.person_link_change_id_seq OWNER TO %I', person_owner);
    END IF;
END $$;

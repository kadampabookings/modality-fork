-- V0063: audit trail for persons moving between frontend accounts.
--
-- WHY. Moving a person to another account is a routine back-office operation
-- (MergeAccountsDialog: `update Person set frontendAccount=$1, owner=false where
-- frontendAccount=$2`, followed by deleting the emptied account) and it is currently
-- UNTRACEABLE. The persons move, the account row is deleted, and nothing records where
-- they came from — so support cannot answer "why is this person on this account", and
-- there is no undo. The same gap applies to any one-off SQL that re-parents a person.
--
-- A trigger rather than client-side inserts: the back-office dialogs, maintenance
-- scripts and manual SQL then all feed the same trail for free, with no new domain
-- entity and no DomainModel regeneration.
--
-- The trigger reads the OLD row, so was_owner / was_removed capture the state to restore
-- on reversal. It also resolves the source account's username while that row still
-- exists — callers move the persons BEFORE deleting the emptied account, so the lookup
-- succeeds; it is nullable because that ordering is convention, not a constraint.
--
-- NOTE ON THE MISSING FOREIGN KEYS. person_id and the two account ids are plain integers
-- on purpose:
--   * an audit row must outlive a hard-deleted person, which a CASCADE would erase and a
--     RESTRICT would forbid;
--   * `person` already has 28 inbound foreign keys, and the ones without an ON DELETE
--     clause are what makes MergeCustomersDialog's hard merge abort. Adding a 29th
--     blocker would make that worse.
--
-- The table is not (yet) part of the domain model, so it is invisible to the back office.
-- Surfacing "moved from account #1234 on <date>" on the customer page is a separate step
-- and needs the DomainModel regenerated from the KBS2 system.script.

CREATE TABLE IF NOT EXISTS public.person_account_move (
    id                    serial PRIMARY KEY,
    person_id             integer     NOT NULL,
    from_account_id       integer,
    to_account_id         integer,
    -- Captured before the source account is deleted: the only surviving record of the
    -- login address the person used to sign in with.
    from_account_username varchar(127),
    was_owner             boolean     NOT NULL,
    was_removed           boolean     NOT NULL,
    moved_at              timestamptz NOT NULL DEFAULT now(),
    moved_by              text        NOT NULL DEFAULT current_user,
    -- Free-text context, set by callers through `SET LOCAL kbs.audit_note = '...'`.
    -- Back-office merges leave it null; maintenance scripts name themselves here so a
    -- bulk run is distinguishable from a staff action.
    note                  text
);

CREATE INDEX IF NOT EXISTS person_account_move_person_idx ON public.person_account_move (person_id);
CREATE INDEX IF NOT EXISTS person_account_move_from_idx   ON public.person_account_move (from_account_id);

CREATE OR REPLACE FUNCTION public.trigger_person_audit_account_move() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- `UPDATE OF frontend_account_id` fires whenever the column appears in the SET list,
    -- including no-op writes; only real moves are worth recording.
    IF NEW.frontend_account_id IS DISTINCT FROM OLD.frontend_account_id THEN
        INSERT INTO person_account_move (
            person_id, from_account_id, to_account_id, from_account_username,
            was_owner, was_removed, note)
        VALUES (
            OLD.id,
            OLD.frontend_account_id,
            NEW.frontend_account_id,
            (SELECT fa.username FROM frontend_account fa WHERE fa.id = OLD.frontend_account_id),
            OLD.owner IS TRUE,
            OLD.removed IS TRUE,
            nullif(current_setting('kbs.audit_note', true), ''));
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS audit_account_move ON public.person;
CREATE TRIGGER audit_account_move
    AFTER UPDATE OF frontend_account_id ON public.person
    FOR EACH ROW EXECUTE FUNCTION public.trigger_person_audit_account_move();

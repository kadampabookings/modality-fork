-- V0062: sync the account login from the OWNER's email, not the lowest-id person's.
--
-- trigger_person_on_email_change_update_frontend_username still identified the account
-- holder the way the schema did before `person.owner` existed — "the person with no
-- lower id on this account":
--
--     update frontend_account set username = NEW.email
--      where id = NEW.frontend_account_id
--        and not exists (select * from person
--                         where frontend_account_id = NEW.frontend_account_id
--                           and id < NEW.id);
--
-- Two live consequences, measured on staging 2026-08-09:
--
--  * WRONG PERSON SPEAKS FOR THE ACCOUNT. On 14 accounts the lowest-id row is a
--    NON-OWNER member (import/merge artefacts). Editing that member's email — something
--    /members offers routinely — silently rewrites the account's login username, leaving
--    the owner signing in with an address the system no longer holds.
--  * OWNER SILENTLY IGNORED. On 4 accounts the owner is not the lowest-id row, so their
--    email changes never reached the login at all.
--
-- Note this trigger is NOT the main source of the person.email / username drift: of 195
-- drifted owners, 191 ARE the lowest-id row, and they are markedly older records (mean
-- person id 6.6k vs 30.5k) — mismatches born at INSERT (this trigger only fires on
-- UPDATE OF email) or from direct username edits, never re-synced since. Those are
-- legacy data, unaffected by this change.
--
-- `email IS NOT NULL` is defence in depth: a bulk write that nulls emails must never be
-- able to null a login. The one-off sweep in
-- scripts/nullify-member-emails-matching-account-login.sql excludes lowest-id rows for
-- exactly that reason; this makes the database refuse it regardless.
--
-- Accounts with NO person flagged owner (25 on staging) stop syncing rather than falling
-- back to lowest-id: with no owner, there is no one whose address is the login by right,
-- and guessing is what produced the hazard above. They are being fixed at source — the
-- two KBS2 front ends now set person.owner on account creation — and can be backfilled
-- (see the same scripts/ directory). Accounts with several owners (5 on staging) keep
-- last-writer-wins, unchanged.

CREATE OR REPLACE FUNCTION public.trigger_person_on_email_change_update_frontend_username() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.owner IS TRUE AND NEW.email IS NOT NULL THEN
        UPDATE frontend_account SET username = NEW.email WHERE id = NEW.frontend_account_id;
    END IF;
    RETURN NEW;
END $$;

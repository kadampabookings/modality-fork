-- V0099: a super administrator's reopening of a closed password is recorded like every other rescue.
--
-- "Stop my password working" (V0096) closes a password and every emailed way in, so an owner who then loses every
-- passkey is let back in only by a super administrator. That rescue reopens the password, and recovery with it, on
-- the strength of a check made out of band: exactly the request a social engineer would make. So it is recorded
-- where the other rescues are, in second_factor_reset, with the approver and the note of the check they made. The
-- row is written in the same statement as the lift (AccountSignInRestrictionStore.openPasswordByAdministrator).
--
-- The one change: 'PASSWORD' joins the kinds the table admits. The note column is already classified for the
-- anonymiser (scripts/gdpr-anonymise/10-anon-helpers.sql), and its deletion path is the account's own cascade.
--
-- Lock: replacing a CHECK takes ACCESS EXCLUSIVE on second_factor_reset for as long as validating its rows takes.
-- The table is written only by rescues and read only by the support screen, a handful of rows, so nothing is held
-- up — unlike the hot tables the lock-timeout rule (V0070) is about.
--
-- Idempotent: the constraint is dropped and re-created with the wider list.
ALTER TABLE public.second_factor_reset DROP CONSTRAINT IF EXISTS second_factor_reset_what_check;
ALTER TABLE public.second_factor_reset ADD CONSTRAINT second_factor_reset_what_check
    CHECK (what IN ('TOTP', 'BACKUP_CODES', 'PASSKEY', 'PASSWORD'));

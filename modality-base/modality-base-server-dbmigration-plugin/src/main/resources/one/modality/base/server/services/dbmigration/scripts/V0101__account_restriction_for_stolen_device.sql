-- V0101: the panic button — "my device has been stolen" — and the rescue that undoes it.
--
-- The control disables the account AND ends every session of the person, because neither alone does the job:
-- revoking leaves the thief free to sign back in with a password that is member-grade, and disabling alone does
-- not end a session in progress — renewal does not read the account, so a thief who never reconnects renews
-- straight through it. Both, and then the promise holds: after one access window every token of that person has
-- had to renew and been refused. The stolen device may also hold a passkey, so every passkey of the account is
-- rejected in the same statement; the owner enrols again afterwards.
--
-- TWO CHECK CONSTRAINTS WIDEN. Nothing else changes: the restriction row and the rescue record already have the
-- shape this needs, which is why this migration is four lines and not a table.
--
--   * account_sign_in_restriction.method admits 'account' beside 'password' (V0096 kept method as a column
--     precisely so that a second way of closing an account would be a widened CHECK, not a migration against a
--     boolean). The restriction row is what keeps `frontend_account.disabled` from being overloaded: a super
--     administrator must be able to tell "the owner hit the panic button", who is expected back within the hour,
--     from "an administrator disabled this account", who is not — and the row carries who, when and their note.
--   * second_factor_reset.what admits 'ACCOUNT', so the rescue is recorded where every other rescue is (V0099
--     did the same for 'PASSWORD'). Re-enabling an account on the strength of an out-of-band check is exactly
--     the request a social engineer would make, so it is recorded with the approver and the check they made.
--
-- Lock: replacing a CHECK takes ACCESS EXCLUSIVE on the table for as long as validating its rows takes. Both
-- tables are small and written only by these controls — unlike the hot tables the lock-timeout rule (V0070) is
-- about — so nothing is held up.
--
-- Idempotent: each constraint is dropped and re-created with the wider list.
ALTER TABLE public.account_sign_in_restriction DROP CONSTRAINT IF EXISTS account_sign_in_restriction_method_check;
ALTER TABLE public.account_sign_in_restriction ADD CONSTRAINT account_sign_in_restriction_method_check
    CHECK (method IN ('password', 'account'));

ALTER TABLE public.second_factor_reset DROP CONSTRAINT IF EXISTS second_factor_reset_what_check;
ALTER TABLE public.second_factor_reset ADD CONSTRAINT second_factor_reset_what_check
    CHECK (what IN ('TOTP', 'BACKUP_CODES', 'PASSKEY', 'PASSWORD', 'ACCOUNT'));

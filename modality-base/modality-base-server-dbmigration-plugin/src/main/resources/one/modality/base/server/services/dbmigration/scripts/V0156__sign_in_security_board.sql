-- V0156: the super administrators' Sign-in security page — blocking an account, and signing someone out.
--
-- TWO CHECK CONSTRAINTS WIDEN. Nothing else changes: as in V0101, the restriction row and the audit record already
-- have the shape this needs.
--
--   * account_sign_in_restriction.method admits 'blocked': a super administrator closed the account on purpose. It is
--     kept apart from 'account' (the owner's own "my device was stolen", V0101) for the reason V0101 gives — the two
--     are expected back on very different terms — and so that lifting either can never re-open an account the other
--     still holds closed. The row carries who blocked it, when and their note; lifting keeps the row.
--   * second_factor_reset.what admits 'SESSIONS' (a super administrator signed someone out: one session, or all of
--     them, with the number ended in sessions_revoked) and 'UNBLOCK' (the lift, with the approver and the note of the
--     check they made, where every other rescue is recorded).
--
-- No column is added, so neither the staging anonymiser's classification nor the erasure manifest changes: both
-- tables are already classified, and the new rows carry the same kinds of value as the old ones.
--
-- Lock: replacing a CHECK takes ACCESS EXCLUSIVE on the table for as long as validating its rows takes. Both tables
-- are small and written only by these controls, so nothing is held up.
--
-- Idempotent: each constraint is dropped and re-created with the wider list.
ALTER TABLE public.account_sign_in_restriction DROP CONSTRAINT IF EXISTS account_sign_in_restriction_method_check;
ALTER TABLE public.account_sign_in_restriction ADD CONSTRAINT account_sign_in_restriction_method_check
    CHECK (method IN ('password', 'account', 'blocked'));

ALTER TABLE public.second_factor_reset DROP CONSTRAINT IF EXISTS second_factor_reset_what_check;
ALTER TABLE public.second_factor_reset ADD CONSTRAINT second_factor_reset_what_check
    CHECK (what IN ('TOTP', 'BACKUP_CODES', 'PASSKEY', 'PASSWORD', 'ACCOUNT', 'ENROLMENT_WINDOW', 'SESSIONS', 'UNBLOCK'));

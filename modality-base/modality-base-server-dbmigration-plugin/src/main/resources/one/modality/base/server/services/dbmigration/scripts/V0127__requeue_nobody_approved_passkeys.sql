-- Re-queues every passkey that nobody approved, so that turning WEBAUTHN_BACKOFFICE_APPROVAL on
-- means what it says. Gate 3 of the back-office second-factor rollout
-- (docs/security/backoffice-second-factor-totp-design.md, "What must be true before
-- BACKOFFICE_SECOND_FACTOR leaves off").
--
-- WHY: with WEBAUTHN_BACKOFFICE_APPROVAL off — the shipped default since 2026-09-14 — the gateway
-- stores every self-enrolled passkey APPROVED with decided_by_person_id NULL. Approved by nobody,
-- and at login indistinguishable from one a super administrator decided. The moment the policy
-- counts a passkey as a strong factor, such a row IS both factors in one gesture, behind nothing
-- but the password that enrolled it — the very hole the approval switch exists to close, and one
-- the switch cannot close retroactively on its own.
--
-- decided_by_person_id IS NULL is exactly the right marker: a person-approved row carries the
-- approver's id (V0089), so nothing any person actually decided is touched here. decided_at is
-- left as it stands; it is already NULL on these rows by construction, since only a decision
-- writes it, and clearing it would erase rather than reveal any anomaly.
--
-- INERT ON ITS OWN, AND THAT IS THE POINT. WebAuthnCredentialStore.opensBackofficeLogin returns
-- true for a PENDING row while the switch is off, so on the day this runs nothing changes for
-- anybody: the rows move to PENDING and keep working exactly as before. What it does is populate
-- the approval queue (/passkey-approvals), so the decisions can be taken BEFORE the switch goes on
-- and nobody's passkey stops working at the moment of the flip. The other order — switch first,
-- re-queue second — reaches the same end state through a window in which working passkeys stop
-- working with no queue yet to rescue them.
--
-- NOBODY IS LOCKED OUT even if the switch goes on with rows still pending.
-- PasskeySecondFactorVerifier.isEnrolled answers FALSE for an account whose only passkey is pending
-- while the gate is on ("an account with three pending ones is not", in its own words), so under
-- BACKOFFICE_SECOND_FACTOR=optional the back-office password login mints as it always did, instead
-- of being held for a factor that cannot be completed. The account to look at twice is one that has
-- deliberately closed its own password (account_sign_in_restriction, V0096): it relies on the
-- passkey, so a pending passkey plus a closed password leaves the magic link as the only way in.
-- The notice below counts those rather than deciding on their behalf — the condition is recoverable
-- (approve the passkey, or lift the restriction) and does not justify refusing to boot.
--
-- TWO PEOPLE ARE NEEDED TO CLEAR THE QUEUE. Both SELECT_PENDING_SQL and DECIDE_PENDING_SQL exclude
-- the viewer's own account (frontend_account_id <> $n): a super administrator can neither see nor
-- decide their own passkey, deliberately, so that one cracked password cannot approve itself. If
-- every super administrator's passkey is in this sweep they must approve each other's; if only one
-- super administrator has a passkey here, that row can be approved by nobody until a second one
-- exists — or the owner re-enrols once the switch is on, which stores a fresh PENDING row that is
-- then someone else's to decide.
--
-- IT SWEEPS FRONT-OFFICE ACCOUNTS' ROWS TOO, AND THAT IS HARMLESS — checked, because the approval
-- queue lists only back-office accounts (SELECT_PENDING_SQL: a.backoffice = true), so a re-queued
-- row belonging to a front-office-only account is approvable by nobody. It costs that owner nothing:
-- the account fence in authenticateWithPasskey means only a back-office account's passkey signs in
-- anywhere at all (ModalityWebAuthnAuthenticationGateway.signsIn), so such a row could not open a
-- login before this ran either. If the account is ever promoted to back office, its row then surfaces
-- in the queue as PENDING needing a decision — which is the wanted behaviour, and better than
-- springing to life as nobody-approved APPROVED, the case WebAuthnCredentialStore's REVOKE_SQL
-- comment was widened for.
--
-- NO ANONYMISER CHANGE, re-read rather than assumed. No table or column is added, renamed or
-- dropped — this is data only — and webauthn_credential is classified
-- ('webauthn_credential','*','truncated'), so the staging refresh empties the table regardless. The
-- consequence worth knowing: staging holds no passkeys, so this touches 0 rows there and its effect
-- cannot be rehearsed on staging data.
--
-- REHEARSED 2026-10-15 on a throwaway local postgres 17.9 cluster against synthetic rows covering
-- every case this has to tell apart: nobody-approved, person-approved, already-pending, rejected,
-- two rows on one account, a closed password, a LIFTED password restriction, and an 'account'
-- restriction that is not a password one. Each predicate was then removed in turn to check the
-- fixture would notice: dropping the decided_by_person_id marker swept 7 rows instead of 6 and left
-- the person-approved row incoherent (PENDING, approver still recorded); dropping `lifted_at is
-- null` or `method = 'password'` reported 2 closed-password accounts instead of 1; counting rows
-- rather than distinct accounts reported 6 instead of 5. A second run re-queues 0.

do $$
declare
    swept      int;
    accounts   int;
    closed_pwd int;
begin
    select count(*), count(distinct frontend_account_id)
      into swept, accounts
      from webauthn_credential
     where status = 'APPROVED' and decided_by_person_id is null;

    select count(distinct c.frontend_account_id)
      into closed_pwd
      from webauthn_credential c
      join account_sign_in_restriction r on r.frontend_account_id = c.frontend_account_id
     where c.status = 'APPROVED' and c.decided_by_person_id is null
       and r.method = 'password' and r.lifted_at is null;

    update webauthn_credential
       set status = 'PENDING'
     where status = 'APPROVED' and decided_by_person_id is null;

    raise notice 'V0127: re-queued % passkey(s) nobody approved, across % account(s); % of those account(s) have closed their own password. No behaviour changes until WEBAUTHN_BACKOFFICE_APPROVAL is turned on — work the queue at /passkey-approvals first.',
        swept, accounts, closed_pwd;
end $$;

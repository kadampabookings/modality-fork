-- V0181: the schema half of signing a command-line client in by approving it in a browser —
-- docs/security/cli-device-login-design.md, reviewed by David 2026-11-25.
--
-- Two objects, and neither does anything until the gateway exists. Default-deny throughout, so landing this
-- ahead of the gateway changes nothing for anybody.
--
-- 1. frontend_account.device_login — an account may hold a device-minted session only if this is true.
--    The table already carries backoffice, admin, developer, translator, tester and security, so this is the
--    established pattern. Named for the FLOW and not for the tool: `cli` would read as "this account cannot
--    use the CLI", which is false while password and emailed-code sign-ins reach the ordinary authenticate
--    endpoint like any other client. The server cannot verify "I am a CLI" on those paths; it can here,
--    because it minted the session through this flow. A name implying an assurance it does not provide is
--    the worst kind for a security flag.
--
--    NO BACK-OFFICE EDITOR: the column is set by migration, deliberately, so git records who was enabled and
--    when. Revisit if the user count outgrows that.
--
-- 2. device_login_attempt — the durable audit the Sign-in security page's Activity tab reads back.
--    Deliberately a different object from the pending entry the gateway holds in memory: that one is
--    coordination (short TTL, capped, refusing when full, deleted on use), this one is a record
--    (append-only, never refused, never deleted on use). One row cannot be both without either keeping
--    coordination rows for ever or losing the audit.
--
-- WHY A TRIGGER ON THE FLAG, AND NOT A COMMENT ASKING NICELY. person.frontend_account_id has no unique
-- constraint — only two plain btree indexes, which are duplicates of each other — so one account can be
-- shared by many persons, and the flag would cover every one of them. Measured on staging (2026-11-25):
-- 43,977 accounts have one live person and 5,670 have between 2 and 29, so 11.4% are shared. That is a
-- guard, not a note. The trigger refuses enabling on a shared account; the gateway re-checks at approval
-- time, which is what catches a second person attached AFTER the flag was set. No trigger on person, on
-- purpose: person is a hot table and the approval path is not, so the recurring cost belongs where the
-- event is rare.
--
-- THE THREE CONTEXT COLUMNS ARE CREATED EMPTY AND STAY EMPTY FOR NOW. request_ip, request_location and
-- request_user_agent are what the approval page shows and what David's review asked to keep — but whether
-- this organisation may RETAIN where named members of staff were working is a question with the DPO, and
-- the answer is not here yet. So v1 SHOWS the address on the approval page (transient, in the response,
-- never written) and WRITES NULL to these three. The columns exist now so that the classification and the
-- erasure path are built and reviewed while no data exists, rather than under time pressure on the day the
-- answer arrives; loosening is then one function returning real values instead of nulls.
--
-- The precedent for writing nulls here is in the system already: FactorRequest.resolve stores no IP, place
-- or browser for a device approved at once, "only the sign-in method code, which is not personal (data
-- minimisation)". Same three fields, same reasoning.
--
-- Checked and not a problem: frontend_account_dissolved_backup mirrors frontend_account's columns, but the
-- erasure routines only DELETE from it and it is populated by maintenance scripts rather than by any
-- SELECT * — so a new column here does not desynchronise it.
--
-- Idempotent throughout. No BEGIN/COMMIT: the runner executes one statement at a time.

ALTER TABLE public.frontend_account ADD COLUMN IF NOT EXISTS device_login boolean DEFAULT false NOT NULL;

COMMENT ON COLUMN public.frontend_account.device_login IS
    'May this account hold a session minted by the browser-approved device login (V0181)? Default-deny, set '
    'by migration only. Gates that flow alone — password and emailed-code sign-ins are untouched and cannot '
    'be gated, because the server cannot verify a CLI on those paths. Single-person accounts only, enforced '
    'by frontend_account_device_login_guard.';

-- Refuses enabling the flag on an account more than one live person shares. See the header: 11.4% of
-- accounts on staging are shared, the largest by 29 people, so this is a live case and not a theoretical one.
CREATE OR REPLACE FUNCTION public.frontend_account_device_login_guard() RETURNS trigger AS $$
DECLARE
    live_persons integer;
BEGIN
    -- Only when the flag is being turned ON. Everything else — a password reset, a language change, the flag
    -- being turned off — leaves after two boolean comparisons and never reaches the count below.
    IF NEW.device_login IS TRUE
       AND (TG_OP = 'INSERT' OR OLD.device_login IS DISTINCT FROM NEW.device_login) THEN
        SELECT count(*) INTO live_persons
          FROM public.person p
         WHERE p.frontend_account_id = NEW.id
           AND p.removed IS NOT TRUE;
        IF live_persons > 1 THEN
            RAISE EXCEPTION 'device_login cannot be enabled on frontend_account %: % live persons share it. '
                            'A device login mints a session as ONE person, and the flag cannot say which.',
                            NEW.id, live_persons;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS frontend_account_device_login_guard ON public.frontend_account;
CREATE TRIGGER frontend_account_device_login_guard
    BEFORE INSERT OR UPDATE OF device_login ON public.frontend_account
    FOR EACH ROW EXECUTE FUNCTION public.frontend_account_device_login_guard();

CREATE TABLE IF NOT EXISTS public.device_login_attempt (
    id                      bigserial    PRIMARY KEY,
    requested_at            timestamp    NOT NULL DEFAULT now(),
    decided_at              timestamp,
    -- Null where no identity was ever established — a code typed by somebody who never signed in. Cascades,
    -- which is how anonymise_person (V0153) reaches these rows: it deletes the frontend_account, exactly as
    -- it reaches totp_credential and webauthn_credential.
    frontend_account_id     integer      REFERENCES public.frontend_account(id) ON DELETE CASCADE,
    outcome                 varchar(32)  NOT NULL,
    -- Which arm was minted, from the APPROVING session and never from the request. Null on a refusal.
    audience                varchar(2),
    -- The family of the session minted, so a super administrator can tie this row to a live session and
    -- revoke it. Opaque, and of no use once the account is gone.
    session_family_id       varchar(64),
    -- The approving session's own $amr (pwd, pk, totp, ml, code, ...), which is the point: the CLI
    -- authenticated not at all, so these codes say whether the person who approved had presented a factor.
    -- 64, matching auth_session.methods, which is where this value comes from. 32 was narrower than its
    -- own source: a long enough comma list would have failed the INSERT and lost the APPROVED row.
    approver_sign_in_method varchar(64),
    -- The three the DPO question covers. WRITTEN NULL IN V1 — see the header.
    request_ip              inet,
    request_location        varchar(128),
    request_user_agent      varchar(256),
    CONSTRAINT device_login_attempt_outcome_check CHECK (outcome IN (
        'APPROVED',
        'REFUSED_NOT_ENABLED',   -- the account's device_login flag is false
        'REFUSED_APPROVER',      -- a support-view, back-office-view or device session tried to approve, or
                                 -- the account holds a factor that was not proved in that tab recently
        'REFUSED_CODE',          -- the code did not match a pending entry
        'REFUSED_EXPIRED'        -- a code was typed after its pending entry had lapsed
    )),
    CONSTRAINT device_login_attempt_audience_check CHECK (audience IS NULL OR audience IN ('fo', 'bo'))
);

-- A request nobody ever acts on writes NO ROW. David's review: refusals are rows written for callers who
-- never signed in, so an unauthenticated caller must not be able to fill a table by asking. Those lapse in
-- memory and are COUNTED; only a decision somebody actually took lands here. REFUSED_EXPIRED is a decision —
-- a code was typed — and not the same thing as a request that simply ran out.

-- "What has this account done, most recently first?" — the Activity tab's question.
CREATE INDEX IF NOT EXISTS device_login_attempt_account_idx
    ON public.device_login_attempt (frontend_account_id, requested_at DESC);

-- The retention purge's question, which has no account in it. v1 keeps 30 days, tighter than the Activity
-- tab's 90-day filter: a window can be loosened later, whereas rows kept too long cannot be un-kept. The
-- purge itself is a scheduled job in the gateway plugin (ModalityDeviceLoginAuthenticationGateway.boot),
-- on ModalityAuthSessionStoreInitializer's pattern. V0181 adds no scheduler - a migration cannot - so the
-- retention this table promises holds only where that gateway is deployed. It is the same coupling
-- V0155's context clearing has, and worth knowing rather than assuming the database enforces it.
CREATE INDEX IF NOT EXISTS device_login_attempt_requested_at_idx
    ON public.device_login_attempt (requested_at);

COMMENT ON TABLE public.device_login_attempt IS
    'Decisions taken on browser-approved device logins (V0181): approvals and refusals, for the Sign-in '
    'security page Activity tab. Not the pending entries, which live in memory and are counted rather than '
    'stored. Purged after 30 days. request_ip/request_location/request_user_agent are written NULL pending '
    'a data-protection decision on retaining staff location — see the migration header.';

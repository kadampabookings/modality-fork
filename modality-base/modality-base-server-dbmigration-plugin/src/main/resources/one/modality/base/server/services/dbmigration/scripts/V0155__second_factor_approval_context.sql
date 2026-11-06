-- V0155: one approval rule for both second factors, with what an approver needs to judge a request —
-- docs/security/backoffice-second-factor.md, "setup first" (2026-11-06).
--
-- 1. totp_credential gets the approval audit webauthn_credential has had since V0089 (decided_by_person_id,
--    decided_at). The status column already exists (V0091, "kept for the future approval gate"): an account's
--    FIRST authenticator app is now stored PENDING while WEBAUTHN_BACKOFFICE_APPROVAL is on, exactly like a passkey.
--    No FK on decided_by_person_id, for V0089's reason: an audit pointer on a whole-table-truncated row must not
--    take a lock on person at boot nor cascade away when an administrator is erased.
--
-- 2. Both credential tables get the request context shown to the approver:
--      approval_kind          how the row became APPROVED — PERSON (a super administrator decided), PROVED (the
--                             enrolling tab proved an approved factor minutes before), GATE_OFF (the approval switch
--                             was off). NULL on passkey rows older than this script and on rows still PENDING/REJECTED;
--                             older confirmed TOTP rows get GATE_OFF (point 4).
--      request_ip             the caller's address as the bus bridge stamped it (last X-Forwarded-For entry; the
--                             client cannot choose it). Personal data.
--      request_location       "City, Country" from the OFFLINE IP database; nothing is sent anywhere to get it.
--      request_user_agent     what the browser said it was. Informational only, never trusted.
--      request_sign_in_method how the enrolling session signed in, from auth_session.methods ($amr): pwd, ml, code,
--                             pk, totp, or a comma list.
--    The three personal columns are cleared 90 days after the decision by the gateways themselves (no scheduler):
--    they serve the decision, not a history.
--
-- 3. second_factor_reset learns one more action, ENROLMENT_WINDOW: a super administrator reopening setup for 48 hours
--    for an account locked out by the `required` deadline. window_until records when it closes.
--
-- 4. Grandfathering, NOT a re-queue: confirmed authenticator apps nobody approved (TOTP had no gate before this
--    script) keep APPROVED and are marked approval_kind = GATE_OFF, which is what they are. Re-queueing them, as V0127
--    did for three passkeys, would stop asking those accounts for their code at the next login (a PENDING factor does
--    not count while the switch is on) — trading a factor that works for none. The count is in the deploy log, so the
--    super administrators can review those accounts by hand.
--
-- Idempotent throughout. No BEGIN/COMMIT: the runner executes one statement at a time.

ALTER TABLE public.totp_credential ADD COLUMN IF NOT EXISTS decided_by_person_id integer;
ALTER TABLE public.totp_credential ADD COLUMN IF NOT EXISTS decided_at timestamp;

ALTER TABLE public.totp_credential ADD COLUMN IF NOT EXISTS approval_kind varchar(16);
ALTER TABLE public.totp_credential ADD COLUMN IF NOT EXISTS request_ip inet;
ALTER TABLE public.totp_credential ADD COLUMN IF NOT EXISTS request_location varchar(128);
ALTER TABLE public.totp_credential ADD COLUMN IF NOT EXISTS request_user_agent varchar(256);
ALTER TABLE public.totp_credential ADD COLUMN IF NOT EXISTS request_sign_in_method varchar(32);

ALTER TABLE public.webauthn_credential ADD COLUMN IF NOT EXISTS approval_kind varchar(16);
ALTER TABLE public.webauthn_credential ADD COLUMN IF NOT EXISTS request_ip inet;
ALTER TABLE public.webauthn_credential ADD COLUMN IF NOT EXISTS request_location varchar(128);
ALTER TABLE public.webauthn_credential ADD COLUMN IF NOT EXISTS request_user_agent varchar(256);
ALTER TABLE public.webauthn_credential ADD COLUMN IF NOT EXISTS request_sign_in_method varchar(32);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'totp_credential_approval_kind_check') THEN
        ALTER TABLE public.totp_credential ADD CONSTRAINT totp_credential_approval_kind_check
            CHECK (approval_kind IS NULL OR approval_kind IN ('PERSON', 'PROVED', 'GATE_OFF'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'webauthn_credential_approval_kind_check') THEN
        ALTER TABLE public.webauthn_credential ADD CONSTRAINT webauthn_credential_approval_kind_check
            CHECK (approval_kind IS NULL OR approval_kind IN ('PERSON', 'PROVED', 'GATE_OFF'));
    END IF;
END
$$;

-- The approval queue now reads both tables: the TOTP half gets the partial index V0089 gave the passkey half.
CREATE INDEX IF NOT EXISTS totp_credential_pending_idx
    ON public.totp_credential (created_at, id) WHERE status = 'PENDING';

ALTER TABLE public.second_factor_reset ADD COLUMN IF NOT EXISTS window_until timestamptz;

DO $$
BEGIN
    ALTER TABLE public.second_factor_reset DROP CONSTRAINT IF EXISTS second_factor_reset_what_check;
    ALTER TABLE public.second_factor_reset ADD CONSTRAINT second_factor_reset_what_check
        CHECK (what IN ('TOTP', 'BACKUP_CODES', 'PASSKEY', 'PASSWORD', 'ACCOUNT', 'ENROLMENT_WINDOW'));
END
$$;

-- The window lookup is "the latest ENROLMENT_WINDOW row of this account".
CREATE INDEX IF NOT EXISTS second_factor_reset_window_idx
    ON public.second_factor_reset (frontend_account_id, window_until) WHERE what = 'ENROLMENT_WINDOW';

DO $$
DECLARE
    marked integer;
BEGIN
    UPDATE public.totp_credential SET approval_kind = 'GATE_OFF'
     WHERE status = 'APPROVED' AND confirmed_at IS NOT NULL AND decided_by_person_id IS NULL AND approval_kind IS NULL;
    GET DIAGNOSTICS marked = ROW_COUNT;
    RAISE NOTICE 'V0155: % authenticator app(s) enrolled before the approval gate kept as APPROVED (approval_kind GATE_OFF) — review them', marked;
END
$$;

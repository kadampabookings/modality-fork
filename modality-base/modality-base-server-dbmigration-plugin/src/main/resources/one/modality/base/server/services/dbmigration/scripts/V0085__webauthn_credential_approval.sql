-- V0085: the back-office approval gate for passkeys — docs/security/backoffice-second-factor.md,
-- decisions 2 and 4. Adds the credential's back-office trust and the decision audit to the
-- webauthn_credential table that V0084 created. Separate script on purpose: V0084 is checksummed
-- once applied, and a table that already exists cannot grow columns through CREATE TABLE.
--
-- status: PENDING | APPROVED | REJECTED. A passkey enrolled behind a weak password must not be a
-- free upgrade to a strong one: back-office sign-in requires APPROVED, front-office sign-in does
-- not (it grants nothing the password did not already), REJECTED signs in nowhere. The flag is
-- about the CREDENTIAL, not the account, so a member who is later granted back-office access
-- still has every earlier passkey waiting for approval — an approval given before the grant
-- would be no gate.
--
-- decided_by_person_id / decided_at: who decided and when. No FK to person on purpose: it would
-- take SHARE ROW EXCLUSIVE on the busiest table at boot for an audit pointer this
-- whole-table-truncated row (scripts/gdpr-anonymise manifest) does not need to keep consistent,
-- and a decision by a since-erased administrator should survive as "decided", not cascade away.
--
-- Idempotent throughout, so a database that received any of this by hand is left as it is.
-- No BEGIN/COMMIT: the runner executes one statement at a time.

ALTER TABLE public.webauthn_credential
    ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE public.webauthn_credential
    ADD COLUMN IF NOT EXISTS decided_by_person_id integer;

ALTER TABLE public.webauthn_credential
    ADD COLUMN IF NOT EXISTS decided_at timestamp;

-- The value set lives in the database, not only in the Java constants: a stray value would be
-- refused for the back office server-side, but would break the owner's whole passkey list
-- client-side, where the listing is validated against exactly these three.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'webauthn_credential_status_check') THEN
        ALTER TABLE public.webauthn_credential
            ADD CONSTRAINT webauthn_credential_status_check
            CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
    END IF;
END
$$;

-- The approval queue reads "every PENDING row, oldest first": a partial index keeps that an
-- index walk and prunes itself as rows are decided.
CREATE INDEX IF NOT EXISTS webauthn_credential_pending_idx
    ON public.webauthn_credential (created_at, id) WHERE status = 'PENDING';

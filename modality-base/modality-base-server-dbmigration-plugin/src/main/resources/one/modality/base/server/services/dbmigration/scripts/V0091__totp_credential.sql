-- V0091: the TOTP second factor for back-office logins —
-- docs/security/backoffice-second-factor-totp-design.md, "Data design". Three tables: the factor
-- itself, its backup codes, and the record of every super-administrator reset.
--
-- NUMBERING: the design names this V0090, but V0090 is taken — V0090__migration_created_table_ownership.sql
-- (commit 907bc4dc2), applied on staging on 2026-09-14 — so V0091 is the next free number. (V0088/V0089
-- were renumbered once before, after a merge collision: check the live db_migration table AND index.txt,
-- which the runner reads in file order and rejects unless the versions strictly ascend.)
--
-- KBS3-only tables, deliberately NOT in the domain model (like webauthn_credential/V0088,
-- bo_device/V0078 and person_account_move/V0063): generic client DQL cannot reach them, so ownership
-- is enforced solely by the TOTP gateway's store class — every statement that can change a row
-- carries the caller's account id in its WHERE clause — and no KBS2-side DomainModel snapshot
-- regeneration is needed.
--
-- PERSONAL DATA. A TOTP secret is not personal data in itself, but these rows say WHO holds a second
-- factor, when they last used it, and who had one reset for them. All three are whole-table
-- truncated by scripts/gdpr-anonymise (manifest rows beside webauthn_credential): a staging copy has
-- no use for production second factors, and staff re-enrol per environment as they do for passkeys.
--
-- ON DELETE CASCADE is load-bearing twice over: account merges hard-DELETE the emptied
-- frontend_account row today (a plain FK would break them), and any future account-erasure path must
-- take the second factor with it.
--
-- Idempotent throughout, so a database that received any of this by hand is left as it is.
-- No BEGIN/COMMIT: the runner executes one statement at a time.

-- ===== the factor ============================================================================
-- secret_enc      AES-256-GCM: base64url(nonce ‖ ciphertext ‖ tag), a 12-byte random nonce per row,
--                 AAD = the account id — so a ciphertext copied onto another account's row does not
--                 decrypt, and moving one is loud rather than silent.
-- key_id          which encryption key wrote the row (a short fingerprint, not key material), so a
--                 rotation can re-encrypt lazily at the next successful use and an operator can see
--                 what is left on the old key.
-- status          APPROVED on insert: enrolment is self-service in phase 2. The column is kept for
--                 the gate that phase 3 may want, with a CHECK like V0089's, so turning it on later
--                 is a plugin change rather than a migration.
-- label           the app or device name the owner typed — theirs, shown back to them, never logged.
-- confirmed_at    a factor is CONFIRMED only after its first correct code. Unconfirmed rows are never
--                 asked for at login: requiring a factor that was never proved would lock its owner
--                 out over a mistyped secret.
-- last_used_step  the replay guard. A code is accepted only through
--                   UPDATE totp_credential SET last_used_step = $1, last_used_at = now()
--                    WHERE id = $2 AND last_used_step < $1 returning id
--                 — the lowercase " returning id" is what makes the affected row observable, and the
--                 strict < is what closes the ±1 drift window against a code used twice.
CREATE TABLE IF NOT EXISTS public.totp_credential (
    id                  bigserial    PRIMARY KEY,
    frontend_account_id integer      NOT NULL REFERENCES public.frontend_account(id) ON DELETE CASCADE,
    secret_enc          text         NOT NULL,
    key_id              varchar(16)  NOT NULL,
    status              varchar(16)  NOT NULL DEFAULT 'APPROVED',
    label               varchar(64),
    created_at          timestamp    NOT NULL DEFAULT now(),
    confirmed_at        timestamp,
    last_used_at        timestamp,
    last_used_step      bigint       NOT NULL DEFAULT 0,
    -- One TOTP per account: a second enrolment replaces the first rather than sitting beside it, so
    -- "which phone is my factor?" has one answer.
    CONSTRAINT totp_credential_account UNIQUE (frontend_account_id),
    -- The value set lives in the database and not only in the Java constants: a stray value would be
    -- refused server-side but would break the owner's whole factor listing client-side.
    CONSTRAINT totp_credential_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

-- ===== the backup codes ======================================================================
-- code_hash/salt  SHA-256 over salt ‖ code, 16 random salt bytes per row, compared with
--                 MessageDigest.isEqual. The precedent, MateInviteTokenStore.hashToken, is unsalted
--                 because its tokens are 32 random bytes; a ~50-bit code drawn from a published
--                 alphabet is not, so one unsalted column would be precomputable across every row at
--                 once — each row therefore salts.
-- generation      regenerate = insert generation n+1 and delete generation n, in one call: two live
--                 sheets would mean a lost one still works. Shown once, never re-shown.
-- used_at         single-use, consumed by
--                   UPDATE … SET used_at = now() WHERE id = $1 AND used_at IS NULL returning id.
--                 A used code stays until its generation is replaced, so the owner's list can say
--                 "7 of 10 left".
CREATE TABLE IF NOT EXISTS public.totp_backup_code (
    id                  bigserial    PRIMARY KEY,
    frontend_account_id integer      NOT NULL REFERENCES public.frontend_account(id) ON DELETE CASCADE,
    code_hash           text         NOT NULL,
    salt                text         NOT NULL,
    generation          integer      NOT NULL DEFAULT 1,
    created_at          timestamp    NOT NULL DEFAULT now(),
    used_at             timestamp
);

-- Every read of this table is "this account's codes that are still unused" — at login, when one is
-- typed, and whenever the profile page counts them. A partial index keeps that an index walk and
-- prunes itself as codes are spent.
CREATE INDEX IF NOT EXISTS totp_backup_code_unused_idx
    ON public.totp_backup_code (frontend_account_id) WHERE used_at IS NULL;

-- ===== the reset record ======================================================================
-- what                 TOTP | BACKUP_CODES | PASSKEY. The TOTP gateway writes the first two; the
--                      passkey revocation writes the third, so the CHECK admits all three.
-- reset_by_person_id   the super administrator. No FK to person on purpose — V0089's reasoning for
--                      decided_by_person_id: it would take SHARE ROW EXCLUSIVE on the busiest table
--                      at boot for an audit pointer this whole-table-truncated row does not need to
--                      keep consistent, and a reset performed by a since-erased administrator should
--                      survive as "reset", not cascade away.
-- note                 what the out-of-band identity check was — a call back on a number the centre
--                      already holds, or in person. This column IS the record of that check, which
--                      is why a reset takes one.
-- sessions_revoked     how many live sessions the reset ended. A reset happens because somebody lost
--                      control of a factor, so a session opened by whoever caused it must not
--                      outlive it; this is the count of what was closed.
CREATE TABLE IF NOT EXISTS public.second_factor_reset (
    id                  bigserial    PRIMARY KEY,
    frontend_account_id integer      NOT NULL REFERENCES public.frontend_account(id) ON DELETE CASCADE,
    what                varchar(16)  NOT NULL,
    reset_by_person_id  integer,
    note                varchar(256),
    sessions_revoked    integer      NOT NULL DEFAULT 0,
    created_at          timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT second_factor_reset_what_check CHECK (what IN ('TOTP', 'BACKUP_CODES', 'PASSKEY'))
);

-- "What was reset on this account, and when?" — the question a support conversation asks.
CREATE INDEX IF NOT EXISTS second_factor_reset_account_idx
    ON public.second_factor_reset (frontend_account_id, created_at);

-- Ownership: a table created by the migration's connect role is unwritable by the other app roles
-- — the "permission denied" trap V0064 exists to repair. Hand each table AND its sequence to
-- whoever owns public.person, so every app role writes them like any other (V0078/V0088 pattern).
DO $$
DECLARE app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.totp_credential OWNER TO %I', app_owner);
        EXECUTE format('ALTER SEQUENCE public.totp_credential_id_seq OWNER TO %I', app_owner);
        EXECUTE format('ALTER TABLE public.totp_backup_code OWNER TO %I', app_owner);
        EXECUTE format('ALTER SEQUENCE public.totp_backup_code_id_seq OWNER TO %I', app_owner);
        EXECUTE format('ALTER TABLE public.second_factor_reset OWNER TO %I', app_owner);
        EXECUTE format('ALTER SEQUENCE public.second_factor_reset_id_seq OWNER TO %I', app_owner);
    END IF;
END $$;

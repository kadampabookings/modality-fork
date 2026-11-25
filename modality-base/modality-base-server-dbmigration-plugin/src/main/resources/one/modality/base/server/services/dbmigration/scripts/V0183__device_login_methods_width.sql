-- V0183: widen device_login_attempt.approver_sign_in_method to varchar(64).
--
-- WHY THIS IS A SEPARATE SCRIPT, which is the whole point of it. The widening was first made by editing
-- V0181 in place, on the belief that V0181 had been applied nowhere. It had: staging ran it at 15:05:20
-- UTC on 2026-11-25, and db_migration holds its checksum. Changing the file changed the checksum, the
-- runner hard-fails on a mismatch for a script it has already applied, and so the boot gate never
-- completed — staging's /health stayed at 503 "WAITING: db-migration" and the previous build went on
-- serving while CodeDeploy reported success. V0181 has since been restored byte for byte.
--
-- The rule in index.txt's header — never edit or remove a shipped script, ship a new version instead —
-- exists for exactly this, and "shipped" means applied ANYWHERE, not merged or released. A script that has
-- run on staging is shipped. The only safe check is the one the database can answer; a recollection of
-- where a script had got to half an hour earlier is not one, least of all in a repository other people
-- are pushing to.
--
-- WHAT IT DOES. approver_sign_in_method takes its value from auth_session.methods, which is varchar(64):
-- the comma-joined sign-in method codes of the session that APPROVED a device login (pwd, pk, totp, ml,
-- code, cart, sv, bov, dev). At 32 a long enough list would have failed the INSERT, and the gateway's
-- audit write deliberately never fails the caller — so the APPROVED row would have been lost silently,
-- which is the one case the audit exists for.
--
-- Increasing a varchar's length limit does not rewrite the table (PostgreSQL 9.2 and later); it takes a
-- brief ACCESS EXCLUSIVE lock, on a table that is empty everywhere this will run.
--
-- Nothing is needed in scripts/gdpr-anonymise/ or scripts/gdpr-erasure/: the column already exists and is
-- already classified — device_login_attempt is whole-table 'truncated' in anon.manifest, and the column
-- has its manifest row (n/a for finalise and archive, the sign-in method codes not being personal data).
-- A width is not a disposition.
--
-- ALSO RECORDED HERE, because the edit that broke staging carried it and the restore took it back out:
-- V0181's header described the 30-day purge as living in the gateway plugin. It does —
-- ModalityDeviceLoginAuthenticationGateway.boot() schedules it, on ModalityAuthSessionStoreInitializer's
-- pattern — and the consequence is worth stating where somebody will find it: the retention this table
-- promises holds only where that gateway is deployed. A migration cannot schedule anything. It is the
-- same coupling V0155's request-context clearing has with the factor gateways.
--
-- Idempotent. No BEGIN/COMMIT: the runner executes one statement at a time.

DO $$
BEGIN
    -- Guarded on the catalog rather than run unconditionally: this must be a no-op on a database that
    -- already has the wider column (one built from a later dump, or a rehearsal re-run), and it must not
    -- fail on one where V0181 has not created the table at all.
    IF EXISTS (
        SELECT 1
          FROM pg_attribute a
          JOIN pg_class c ON c.oid = a.attrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname = 'device_login_attempt'
           AND a.attname = 'approver_sign_in_method'
           AND NOT a.attisdropped
           AND a.atttypmod = 36  -- varchar(32): atttypmod is the declared length plus a 4-byte header
    ) THEN
        ALTER TABLE public.device_login_attempt
            ALTER COLUMN approver_sign_in_method TYPE varchar(64);
        RAISE NOTICE 'device_login_attempt.approver_sign_in_method widened to varchar(64)';
    END IF;
END $$;

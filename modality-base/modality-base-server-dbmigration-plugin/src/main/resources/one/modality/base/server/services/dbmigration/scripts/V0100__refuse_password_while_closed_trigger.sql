-- V0100: attaches the trigger that refuses a password written onto an account whose owner stopped their
-- password working — V0098's trigger_frontend_account_refuse_password_while_closed().
--
-- THIS IS A CREATE TRIGGER IN A BOOT MIGRATION, which the house rule forbids. The rule exists because a
-- migration that DIES on a lock takes the whole pending batch with it: every script runs in one transaction
-- with lock_timeout 5s, and V0070's ALTER TABLE on `letter` left a deploy half-applied three times over.
-- This one cannot die. The CREATE TRIGGER sits in a DO block that catches the two errors it can raise —
-- the lock timeout, and not owning the table — and turns them into a WARNING. A deploy therefore proceeds
-- whatever happens here, and the worst case is the state we were in before: no trigger, and KBS2 able to
-- write a password onto a closed account, which is then put right by hand with
-- scripts/refuse-password-while-closed-trigger.sql (same statement, retried in a window of its own).
--
-- It is here rather than only in that script because the script is a step somebody has to remember at every
-- release, on every environment, and forgetting it is silent: the control looks on, and the hole stays open.
-- A migration is remembered by the deploy.
--
-- WHAT IT COSTS WHEN IT WORKS. CREATE TRIGGER takes SHARE ROW EXCLUSIVE on frontend_account: it conflicts
-- with writes to that table, not with reads, and while the request queues the writers behind it wait too.
-- The wait is capped at 3s (the DO block lowers lock_timeout, and PL/pgSQL restores it on exit), and once
-- granted the lock is held to the end of the migration transaction. frontend_account is written on sign-up
-- and on password and email changes, not on every sign-in, so this is a brief pause for a few writers at a
-- quiet moment of a deploy — not V0070's ACCESS EXCLUSIVE, which blocked every reader of a hot table.
--
-- Idempotent: it checks for the trigger first, and creating it is the only thing it does.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_trigger
                WHERE tgrelid = 'public.frontend_account'::regclass
                  AND tgname = 'refuse_password_while_closed' AND NOT tgisinternal) THEN
        RAISE NOTICE 'V0100: refuse_password_while_closed is already installed';
        RETURN;
    END IF;
    -- Lower than the batch's 5s: this statement is the one that can wait, and it should wait less than the
    -- migration as a whole is prepared to. SET LOCAL inside a function is undone when the function exits.
    SET LOCAL lock_timeout = '3s';
    CREATE TRIGGER refuse_password_while_closed
        BEFORE UPDATE OF password ON public.frontend_account
        FOR EACH ROW WHEN (new.password IS DISTINCT FROM old.password)
        EXECUTE FUNCTION public.trigger_frontend_account_refuse_password_while_closed();
    RAISE NOTICE 'V0100: refuse_password_while_closed installed';
EXCEPTION
    -- The table was busy. Not a failure of the deploy: the control still refuses every KBS3 path, and the
    -- one KBS2 leaves open is closed by running the script below at a quieter moment.
    WHEN lock_not_available THEN
        RAISE WARNING 'V0100: frontend_account was busy, so refuse_password_while_closed was NOT installed. KBS2 can still write a password onto an account whose password is closed. Run scripts/refuse-password-while-closed-trigger.sql (it retries in a window of its own)';
    -- The migration role does not own frontend_account and has no TRIGGER privilege on it (the ownership
    -- drift V0064 and V0090 deal with). Same outcome: warn, and leave it to the script, run as the owner.
    WHEN insufficient_privilege THEN
        RAISE WARNING 'V0100: no TRIGGER privilege on frontend_account, so refuse_password_while_closed was NOT installed. Run scripts/refuse-password-while-closed-trigger.sql as the table owner';
END $$;

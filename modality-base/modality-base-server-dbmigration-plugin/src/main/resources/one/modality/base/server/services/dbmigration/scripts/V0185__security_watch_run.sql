-- V0185: security_watch_run -- one row per run of the server's security watch.
--
-- WHY. A server module (the security watch) checks, once a day and on demand, the third-party software the
-- deployment runs against public vulnerability and release sources, and assembles ONE report: what runs, which
-- versions, which known flaws apply, what to do about them. Platform super administrators read it on a back-office
-- page. This table is where a run keeps its state while it runs, and the report once it has finished; the page shows
-- the latest run and the report of the latest run that succeeded, so a failed run never blanks it.
--
-- WHAT A ROW HOLDS. How the run was started (the daily schedule, or a super administrator's "check now"), its status,
-- when it started and finished, who asked for a manual run (a person id, like every other actor column here: never a
-- name or an address), on failure a fixed error code (never a source's own text), and the report as JSON. The report
-- names software, versions and public advisory ids. It holds no personal data -- but it IS a list of what is
-- unpatched, which is why the table is fenced as below.
--
-- ONE RUN AT A TIME, ACROSS SERVERS. Several server tasks run at once, and each one ticks the daily schedule. The
-- partial unique index below admits at most one 'running' row in the whole table (V0146's trick), so a second start
-- fails on insert, whichever instance it reaches; the scheduled start also inserts only when no scheduled run started
-- in the last 20 hours, in the same statement, so several ticking tasks produce exactly one run. A run whose server
-- died is not left holding that slot: a start first marks failed (error 'timeout') any row running for more than 30
-- minutes. Rows older than 400 days are deleted by the runs themselves.
--
-- NO FOREIGN KEY TO PERSON, as V0146 explains for its own actor column: an audit row must outlive whatever happens
-- to the person.
--
-- NOT IN THE DOMAIN MODEL. No DQL can read or write it, and clients cannot send raw SQL (ClientQueryGuard,
-- ClientSubmitGuard), so the module's bus endpoints -- platform super administrator, re-checked on every call -- are
-- the only way in. PUBLIC holds nothing on it or its sequence.
--
-- ANONYMISER. Classified in the aggregate repo's scripts/gdpr-anonymise/10-anon-helpers.sql as truncated: it is
-- environment-specific operational data, and a copy of another environment's list of what is unpatched has no
-- business in a refreshed copy, which runs its own check.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

CREATE TABLE IF NOT EXISTS public.security_watch_run (
    id                     serial      PRIMARY KEY,
    -- schedule = the daily tick of a server task; manual = a super administrator's "check now".
    trigger                text        NOT NULL
        CONSTRAINT security_watch_run_trigger_chk CHECK (trigger IN ('schedule', 'manual')),
    status                 text        NOT NULL
        CONSTRAINT security_watch_run_status_chk CHECK (status IN ('running', 'succeeded', 'failed')),
    started_at             timestamptz NOT NULL DEFAULT now(),
    finished_at            timestamptz,
    -- The super administrator who asked for a manual run; NULL for a scheduled one. A plain integer, not a foreign
    -- key, like event_erasure_run.requested_by_person_id.
    requested_by_person_id integer,
    -- Why a run failed, as a fixed code; never a source's own text.
    error                  text,
    -- The report of a succeeded run (its own schemaVersion inside); NULL while running and on failure.
    report                 jsonb,
    -- A run is finished exactly when it is no longer running.
    CONSTRAINT security_watch_run_finished_chk CHECK ((status = 'running') = (finished_at IS NULL))
);

-- At most one running check in the whole table. The key is a constant, so any two running rows collide; the WHERE
-- keeps finished rows out of the index entirely.
CREATE UNIQUE INDEX IF NOT EXISTS security_watch_run_one_running_idx
    ON public.security_watch_run ((true)) WHERE status = 'running';

-- Every read is "the latest run" (of any status, of a status, of a trigger), and the prune is by age.
CREATE INDEX IF NOT EXISTS security_watch_run_started_idx
    ON public.security_watch_run (started_at DESC);

REVOKE ALL ON TABLE public.security_watch_run FROM PUBLIC;
REVOKE ALL ON SEQUENCE public.security_watch_run_id_seq FROM PUBLIC;

COMMENT ON TABLE public.security_watch_run IS
    'Runs of the security watch (V0185): one row per check of the third-party software this deployment runs against '
    'public vulnerability and release sources, with the report it produced. No personal data beyond a staff person id, '
    'but a list of what is unpatched: not in the domain model, the security watch endpoints (platform super '
    'administrators) are the only way in. At most one row is running at a time (security_watch_run_one_running_idx).';
COMMENT ON COLUMN public.security_watch_run.trigger IS
    'schedule = the daily tick of a server task; manual = a super administrator''s "check now".';
COMMENT ON COLUMN public.security_watch_run.status IS
    'running | succeeded | failed. A row running for more than 30 minutes is marked failed (error timeout) by the next start.';
COMMENT ON COLUMN public.security_watch_run.requested_by_person_id IS
    'The staff member (a platform super administrator) who asked for a manual run; NULL for a scheduled one. A person id, never a name or an address; no foreign key.';
COMMENT ON COLUMN public.security_watch_run.error IS
    'Why the run failed, as a fixed code (timeout, internal, ...): never a source''s own text.';
COMMENT ON COLUMN public.security_watch_run.report IS
    'The report of a succeeded run, as JSON with its own schemaVersion: software, versions, public advisory ids, actions. NULL while running and on failure.';

-- Ownership: a table created by the migration's connect role is unreachable by the other app roles, the
-- "permission denied" trap V0064 repaired and V0090 made standing. Hand it (and, with it, its serial
-- sequence) to whoever owns public.person, as V0102 does -- warning rather than failing, as V0090 and
-- V0109 do, because a tidy-up must never abort the boot chain.
DO $$
DECLARE
    app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        BEGIN
            EXECUTE format('ALTER TABLE public.security_watch_run OWNER TO %I', app_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0185: could not realign the owner of public.security_watch_run to % (%)', app_owner, SQLERRM;
        END;
    END IF;
END $$;

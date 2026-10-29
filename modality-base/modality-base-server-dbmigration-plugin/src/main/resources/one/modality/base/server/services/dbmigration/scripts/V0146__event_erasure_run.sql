-- V0146: event_erasure_run -- one row per personal-data removal run started from the back office.
--
-- WHY. erase_past_event() (V0077) has been run by hand in DBeaver, and nothing anywhere recorded that it
-- ran: not who ran it, not on which event, not what it found or changed. The Event Lifecycle screen now
-- starts it (modality-event-server-lifecycle-plugin, EventErasureRunner), as a server background job on a
-- dedicated connection, and the screen polls for the outcome. This table is where that job keeps its
-- state while it runs, and the record of what it did once it has finished.
--
-- WHAT A ROW HOLDS. The run's shape (event, mode, status, the dry run a commit was checked against), who
-- started it (a person id, like every other actor column here: never a name or an address), when, the
-- grid erase_past_event() returned, and on failure a key and a short message. The grid is the function's
-- step/metric/value rows in order: where it ran, the event's own facts (id, name, dates, organization,
-- booking count), and COUNTS -- how many values were due to be replaced, how many were replaced, how many
-- kept. It never holds a replaced value, or any other guest data: the function reports how much, never
-- what. The error text is SQLSTATE plus the database's message, whose V0077 wording carries ids and
-- counts only.
--
-- ONE RUN AT A TIME, ACROSS SERVERS. A removal holds a REPEATABLE READ transaction over every booking of
-- an event for 1.5 to 10 minutes; two at once would only compete for the same locks. The partial unique
-- index below admits at most one 'running' row in the whole table, so the second start fails on insert,
-- whichever instance it reaches. A run whose server died is not left holding that slot: the connection
-- a run works on is named after it (application_name = app_name), and a start first marks 'aborted'
-- every running row whose connection no longer exists in pg_stat_activity.
--
-- NO FOREIGN KEY TO EVENT, on purpose. The record must outlive the event: an event deleted after its
-- guests' data was removed still has to show that the removal happened, and when.
--
-- NOT IN THE DOMAIN MODEL. No DQL can read or write it, and clients cannot send raw SQL
-- (ClientQueryGuard, ClientSubmitGuard), so the three erasure endpoints -- super-administrator only,
-- re-checked on every call -- are the only way in. PUBLIC holds nothing on it or its sequence.
--
-- ANONYMISER. Classified in the aggregate repo's scripts/gdpr-anonymise/10-anon-helpers.sql as kept:
-- counts, event facts and a staff person id, the record of what was removed.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

CREATE TABLE IF NOT EXISTS public.event_erasure_run (
    id                     serial      PRIMARY KEY,
    -- The event the run acted on. No foreign key: see above.
    event_id               integer     NOT NULL,
    -- preview = the function's own dry run (always rolled back); commit = the real removal.
    mode                   text        NOT NULL
        CONSTRAINT event_erasure_run_mode_chk CHECK (mode IN ('preview', 'commit')),
    -- refused = a commit the rules stopped after the function ran (queued KBS2 payloads); failed = an
    -- error, or a dry-run baseline that no longer matched; aborted = its server went away mid-run.
    status                 text        NOT NULL
        CONSTRAINT event_erasure_run_status_chk CHECK (status IN ('running', 'succeeded', 'failed', 'aborted', 'refused')),
    -- For a commit: the succeeded dry run of the same event it was checked against.
    preview_run_id         integer
        CONSTRAINT event_erasure_run_preview_run_id_fkey REFERENCES public.event_erasure_run (id),
    -- The super administrator who started it. A plain integer, not a foreign key, like
    -- security_alarm.raised_by_person_id: an audit row must outlive whatever happens to the person.
    requested_by_person_id integer,
    started_at             timestamptz NOT NULL DEFAULT now(),
    finished_at            timestamptz,
    grid                   jsonb,
    error_key              text,
    error                  text,
    state_before           text,
    state_after            text,
    app_name               text,
    -- A commit always names the dry run it was checked against (the two-pass rule).
    CONSTRAINT event_erasure_run_commit_needs_preview_chk CHECK (mode <> 'commit' OR preview_run_id IS NOT NULL),
    -- A run is finished exactly when it is no longer running.
    CONSTRAINT event_erasure_run_finished_chk CHECK ((status = 'running') = (finished_at IS NULL))
);

-- At most one running removal in the whole table. The key is a constant, so any two running rows collide;
-- the WHERE keeps finished rows out of the index entirely.
CREATE UNIQUE INDEX IF NOT EXISTS event_erasure_run_one_running_idx
    ON public.event_erasure_run ((true)) WHERE status = 'running';

-- The lifecycle page's read: the latest run per event, and a commit's lookup of its dry run's successors.
CREATE INDEX IF NOT EXISTS event_erasure_run_event_started_idx
    ON public.event_erasure_run (event_id, started_at DESC);

REVOKE ALL ON TABLE public.event_erasure_run FROM PUBLIC;
REVOKE ALL ON SEQUENCE public.event_erasure_run_id_seq FROM PUBLIC;

COMMENT ON TABLE public.event_erasure_run IS
    'Personal-data removal runs started from the back office (V0146): one row per erase_past_event() run, '
    'preview or commit. Bookkeeping only - counts and event facts, never guest data. At most one row is '
    'running at a time (event_erasure_run_one_running_idx). Not in the domain model: the erasure endpoints '
    'of modality-event-server-lifecycle-plugin are the only way in.';
COMMENT ON COLUMN public.event_erasure_run.event_id IS
    'The event the run acted on. Deliberately no foreign key: the record outlives the event.';
COMMENT ON COLUMN public.event_erasure_run.mode IS
    'preview = dry run, always rolled back; commit = the real removal, which also inserts one history marker per booking and seals a RECONCILED event FINALISED.';
COMMENT ON COLUMN public.event_erasure_run.status IS
    'running | succeeded | failed | aborted (its server went away) | refused (a commit stopped by queued KBS2 payloads).';
COMMENT ON COLUMN public.event_erasure_run.preview_run_id IS
    'For a commit: the succeeded preview of the same event, finished less than 24 hours before, whose grid the commit had to match.';
COMMENT ON COLUMN public.event_erasure_run.requested_by_person_id IS
    'The staff member (a super administrator) who started the run. A person id, never a name or an address; no foreign key.';
COMMENT ON COLUMN public.event_erasure_run.grid IS
    'The step/metric/value rows erase_past_event() returned, in order: counts and event facts, never guest data. NULL while running, and when the run failed before the function returned.';
COMMENT ON COLUMN public.event_erasure_run.error_key IS
    'Why the run did not succeed, as the i18n key the back office shows (EventErasureSqlError, EventErasureDriftError, EventErasureQueuedPayloadsError, EventErasureAbortedError).';
COMMENT ON COLUMN public.event_erasure_run.error IS
    'Short server text: SQLSTATE and message, or the metrics that drifted (names and counts). Ids and counts only, never guest data.';
COMMENT ON COLUMN public.event_erasure_run.state_before IS
    'event.state when the run started (NULL = the legacy KBS2 lifecycle).';
COMMENT ON COLUMN public.event_erasure_run.state_after IS
    'event.state once a commit finished: FINALISED when the event was RECONCILED, otherwise unchanged.';
COMMENT ON COLUMN public.event_erasure_run.app_name IS
    'The application_name of the connection the run works on (kbs-erasure-<id>). A running row whose connection is gone from pg_stat_activity is marked aborted by the next start.';

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
            EXECUTE format('ALTER TABLE public.event_erasure_run OWNER TO %I', app_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0146: could not realign the owner of public.event_erasure_run to % (%)', app_owner, SQLERRM;
        END;
    END IF;
END $$;

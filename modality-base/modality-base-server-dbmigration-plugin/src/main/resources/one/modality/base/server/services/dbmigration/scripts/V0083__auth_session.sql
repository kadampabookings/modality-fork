-- One row per live login session, so that a renewed identity token can retire the one it replaces
-- and a retired token turning up again can be recognised for what it is. See
-- docs/security/session-lifetime-policy.md.
--
-- WHAT IS AND IS NOT HERE. No token is stored, ever. The signed payload carries (id, generation);
-- renewal increments the counter, and on presentation generation = current is fine, generation <
-- current means a copy of a retired token is in somebody's hands, and generation > current cannot
-- happen without forging the MAC. Keeping issued or retired tokens instead would grow without bound
-- and would put credential-shaped material at rest for no gain. Nothing in this table is a secret:
-- an id and a counter authenticate nobody on their own.
--
-- WHY POSTGRES. It is the only state the blue/green instances share — no Redis, no clustered event
-- bus (verified 2026-09-06) — which is the same fact that made the token self-contained in the first
-- place. An in-memory store would lose every session on each deploy.
--
-- absolute_expiry is held here as well as inside the signed token deliberately: a cap that exists
-- only inside a credential is a cap nothing can reach afterwards. Here it can be SHORTENED, which is
-- what makes "sign out everywhere" and immediate termination on a disabled account possible later.
--
-- PERSONAL DATA. person_id plus timestamps is a record of when each member was online, so this table
-- is classified 'truncated' in scripts/gdpr-anonymise/10-anon-helpers.sql — staging has no use for
-- production's live sessions. Retention is deletion rather than accumulation: rows past their
-- absolute expiry are removed by the server rather than kept as session history. There is
-- deliberately NO ip or user_agent column; they would sharpen the record considerably in exchange
-- for a convenience nothing has asked for. ON DELETE CASCADE means erasing a person takes their
-- session rows with them rather than leaving orphans the erasure tooling would have to know about.

-- ON THE FOREIGN KEY AND THE BOOT MIGRATION. Adding it takes SHARE ROW EXCLUSIVE on public.person,
-- which conflicts with writes to the busiest table in the system, and this runs at boot behind the
-- /health gate. That is bounded rather than hoped for: DbMigrationRunner opens the batch with
-- SET LOCAL lock_timeout = '5s' and retries a lock timeout three times, so a busy database delays the
-- deploy visibly instead of stalling every writer. Do not remove the constraint to avoid the lock —
-- it is what makes erasing a person take their session rows with them.

CREATE TABLE IF NOT EXISTS public.auth_session (
    id              varchar(64)  PRIMARY KEY,     -- family id, carried in the signed payload
    person_id       integer      REFERENCES public.person(id) ON DELETE CASCADE,
    account_id      integer,                      -- frontend_account; null for a guest principal
    tier            varchar(8)   NOT NULL,        -- 'fo' | 'bo' | 'sv' — which lifetime policy applies
    generation      integer      NOT NULL DEFAULT 0,
    issued          timestamptz  NOT NULL DEFAULT now(),
    last_renewed    timestamptz  NOT NULL DEFAULT now(),
    absolute_expiry timestamptz  NOT NULL,
    revoked         timestamptz,
    revoked_reason  varchar(64)                   -- 'reuse-detected' | 'user' | 'account-disabled'
);

-- Live sessions for one person: what "sign out everywhere" and an offboarding sweep both ask for.
CREATE INDEX IF NOT EXISTS auth_session_person_live
    ON public.auth_session (person_id) WHERE revoked IS NULL;

-- The retention sweep's index. Deleting expired rows is the only bulk read this table ever gets.
CREATE INDEX IF NOT EXISTS auth_session_absolute_expiry
    ON public.auth_session (absolute_expiry);

-- Ownership: a table created by the migration's connect role is unwritable by the other app roles —
-- the "permission denied" trap V0064 exists to repair. Hand it to whoever owns public.person, so
-- every app role writes it like any other table.
DO $$
DECLARE app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.auth_session OWNER TO %I', app_owner);
    END IF;
END $$;

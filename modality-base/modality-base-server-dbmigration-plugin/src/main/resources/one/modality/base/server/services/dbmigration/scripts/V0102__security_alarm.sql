-- V0102: alarm mode — "a credential is loose and we do not yet know whose".
--
-- Control 4 of docs/security/session-revocation-spec.md, in its first form: while an alarm is in force, the
-- access window shortens to a couple of minutes, so every session in the system has to exchange its token —
-- and therefore re-check itself against auth_session — many times more often than is worth paying for
-- normally. Nothing else changes yet; the other postures that design calls for (step-up on sensitive
-- operations, suspending magic-link login, louder identity logging) are separate refusals and are not here.
--
-- WHY A TABLE AND NOT A MESSAGE. Instances do not hear each other: a message from a super administrator's
-- browser lands on whichever one holds that socket, so a pushed toggle would raise the alarm for that
-- instance's share of the clients and no others, with no way to tell which half is which. Every instance
-- already polls this database for revocations; the alarm rides the same tick.
--
-- WHY AN EXPIRY AND NOT A FLAG. A flag has to be turned off by somebody, at the end of an incident, when
-- nobody is thinking about the load it is still imposing — and a flag left on cannot be told from an alarm
-- nobody has raised since. A row that says when it lapses ends by itself, and is extended by raising it
-- again. That is the behaviour that survives being forgotten.
--
-- ONE ROW PER RAISE, kept. What is in force is the latest expiry that is still in the future, so history
-- costs nothing to keep and answers "who raised it, and when" — which, for a posture that costs the whole
-- system something, is exactly what somebody asks afterwards.
CREATE TABLE IF NOT EXISTS public.security_alarm (
    id                 serial       PRIMARY KEY,
    -- When it lapses. The only column the server reads on its poll.
    expires_at         timestamptz  NOT NULL,
    -- Who raised it and when. A person id like every other actor column here, never a name or an address:
    -- this table is not in erasure's path and must not need to be.
    raised_by_person_id integer,
    raised_at          timestamptz  NOT NULL DEFAULT now()
);

-- The poll reads "the furthest expiry still ahead of now", every twenty seconds, on every instance. Partial
-- on nothing and tiny either way — a raise is rare — but the index keeps that read off a sequential scan as
-- the history grows, and it is the only read this table has.
CREATE INDEX IF NOT EXISTS security_alarm_expires_at_idx ON public.security_alarm (expires_at DESC);

COMMENT ON TABLE public.security_alarm IS
    'Alarm mode (V0102): each row is one raise, and what is in force is the latest expires_at still in the future. Read by every instance on its revocation poll; shortens the session access window while raised.';

-- Ownership: a table created by the migration's connect role is unreachable by the other app roles — the
-- "permission denied" trap V0064 repaired and V0090 made standing. Hand it and its sequence to whoever owns
-- public.person, as V0096 and V0088 do.
DO $$
DECLARE app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.security_alarm OWNER TO %I', app_owner);
        EXECUTE format('ALTER SEQUENCE public.security_alarm_id_seq OWNER TO %I', app_owner);
    END IF;
END $$;

-- V0023: viewer presence — add a role flag to chat_presence.
--
-- The frontoffice livestream page now writes VIEWER heartbeats into
-- chat_presence (same 30s cadence as the backoffice agent beats) so the
-- backoffice inbox can show whether a viewer is still watching. The two
-- roles must not pollute each other's reads — the frontoffice "team
-- online" pill derives from agent rows only, the backoffice viewer dot
-- from viewer rows only — so each row carries an `agent` boolean.
--
-- The flag also joins the unique key: the same person can legitimately
-- hold BOTH rows for one event (an agent watching the stream while
-- working the inbox), and each surface re-touches its own row.

ALTER TABLE public.chat_presence
    ADD COLUMN IF NOT EXISTS agent boolean NOT NULL DEFAULT false;

-- Every pre-existing row was written by the backoffice Support-chats
-- page — they are all agent heartbeats. Future inserts default to the
-- viewer role; both clients set the flag explicitly anyway.
UPDATE public.chat_presence SET agent = true;

-- One heartbeat row per person+event+role (previously person+event).
-- Still split into two partial uniques because a plain UNIQUE treats
-- NULL event_ids as distinct rows.
DROP INDEX IF EXISTS chat_presence_person_event_idx;
DROP INDEX IF EXISTS chat_presence_person_global_idx;

CREATE UNIQUE INDEX IF NOT EXISTS chat_presence_person_event_agent_idx
    ON public.chat_presence (person_id, event_id, agent)
    WHERE event_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS chat_presence_person_global_agent_idx
    ON public.chat_presence (person_id, agent)
    WHERE event_id IS NULL;

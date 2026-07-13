-- V0019: integrated live chat — conversations, messages, participants, presence.
--
-- Backs the in-app support chat that replaces the external tawk.to link on
-- the frontoffice livestream page, and is designed to also carry the future
-- internal staff chat (one shared model, discriminated by conversation.type).
--
--   conversation             — one thread. type='support' rows are the
--                              viewer↔team chats (event/session-scoped, with
--                              queue/assignee lifecycle); type='staff' is
--                              reserved for the future internal chat.
--   conversation_participant — membership + per-person read state. The
--                              viewer row is inserted at creation, an agent
--                              row on assignment/first reply. Also the
--                              anchor for the future frontoffice read-scoping
--                              interceptor ("person may read a conversation
--                              iff they participate in it").
--   chat_message             — ordered messages. person_id NULL = system line
--                              (context attached / assigned / resolved ...).
--   chat_presence            — liveness heartbeat, upserted by the backoffice
--                              Support-chats page while an agent has it open.
--                              The frontoffice widget derives "team online"
--                              from fresh rows; no row is ever deleted, only
--                              re-touched (one row per person+event).
--
-- No server-maintained aggregates (unread counts, last-message-at): both
-- surfaces derive them client-side from push-mode subscriptions, same as the
-- support wizard does over support_report.

-- ── Conversations ───────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.conversation (
    id                serial PRIMARY KEY,
    -- Discriminator: 'support' (viewer ↔ event team) | 'staff' (future
    -- internal chat). Lifecycle columns below only apply to 'support'.
    type              character varying(32) NOT NULL,
    -- Scoping. Support chats set organization+event (+scheduled_item when
    -- opened during a specific session); staff chats will scope to the
    -- organization only.
    organization_id   integer REFERENCES public.organization(id),
    event_id          integer REFERENCES public.event(id),
    scheduled_item_id integer REFERENCES public.scheduled_item(id),
    -- The wizard issue the viewer escalated from, when there is one.
    issue_id          integer REFERENCES public.issue(id),
    -- Denormalised owner of a support conversation (also present as a
    -- participant row). Gives the two hot paths — "my conversation" on the
    -- frontoffice and the inbox viewer column on the backoffice — a single
    -- column lookup. NULL for staff conversations.
    viewer_person_id  integer REFERENCES public.person(id),
    -- 'queued' (no assignee) → 'open' (assigned) → 'closed'; 'message' is
    -- the leave-a-message fallback when no agent is online.
    status            character varying(16) NOT NULL DEFAULT 'queued',
    assignee_id       integer REFERENCES public.person(id),
    -- Channel/topic name for future staff conversations; NULL for support.
    title             character varying(255),
    -- Compact "Chrome · Windows" device summary captured at creation so the
    -- backoffice context panel never has to ask.
    viewer_device     character varying(64),
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    closed_at         timestamp with time zone
);

-- Backoffice inbox: all support conversations of the event being monitored.
CREATE INDEX IF NOT EXISTS conversation_event_idx
    ON public.conversation (event_id);

-- Frontoffice widget: "my conversation for this event".
CREATE INDEX IF NOT EXISTS conversation_viewer_person_idx
    ON public.conversation (viewer_person_id);

-- ── Participants ────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.conversation_participant (
    id              serial PRIMARY KEY,
    conversation_id integer NOT NULL REFERENCES public.conversation(id),
    person_id       integer NOT NULL REFERENCES public.person(id),
    -- 'viewer' | 'agent' for support chats; 'member' for future staff chats.
    role            character varying(16) NOT NULL,
    joined_at       timestamp with time zone NOT NULL DEFAULT now(),
    -- Read cursor: messages created after this instant are unread for this
    -- person. Updated by the owning client on open/focus.
    last_read_at    timestamp with time zone,
    UNIQUE (conversation_id, person_id)
);

-- ── Messages ────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.chat_message (
    id              serial PRIMARY KEY,
    conversation_id integer NOT NULL REFERENCES public.conversation(id),
    -- Author; NULL = system line (context attached / assigned / resolved).
    person_id       integer REFERENCES public.person(id),
    -- 'text' | 'system'. System lines render centered in both threads.
    kind            character varying(16) NOT NULL DEFAULT 'text',
    content         text NOT NULL,
    created_at      timestamp with time zone NOT NULL DEFAULT now()
);

-- Thread reads are always "messages of one conversation, in order".
CREATE INDEX IF NOT EXISTS chat_message_conversation_created_idx
    ON public.chat_message (conversation_id, created_at);

-- ── Presence ────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.chat_presence (
    id           serial PRIMARY KEY,
    person_id    integer NOT NULL REFERENCES public.person(id),
    -- Event scope for support-team presence; NULL reserved for future
    -- org-wide (staff chat) presence.
    event_id     integer REFERENCES public.event(id),
    last_seen_at timestamp with time zone NOT NULL DEFAULT now()
);

-- One heartbeat row per person+event. Split into two partial uniques
-- because a plain UNIQUE treats NULL event_ids as distinct rows.
CREATE UNIQUE INDEX IF NOT EXISTS chat_presence_person_event_idx
    ON public.chat_presence (person_id, event_id)
    WHERE event_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS chat_presence_person_global_idx
    ON public.chat_presence (person_id)
    WHERE event_id IS NULL;

-- Frontoffice "team online" subscription filters by event.
CREATE INDEX IF NOT EXISTS chat_presence_event_idx
    ON public.chat_presence (event_id);

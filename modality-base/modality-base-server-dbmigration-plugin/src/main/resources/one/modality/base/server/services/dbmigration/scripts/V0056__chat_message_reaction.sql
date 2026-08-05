-- V0056: emoji reactions on chat messages.
--
-- One row per (message, person, emoji): reacting inserts, un-reacting
-- deletes, and the unique constraint makes a double-tap idempotent rather
-- than a duplicate. Deliberately NOT a column on chat_message: two people
-- reacting at the same moment would lose one of the two updates, and
-- chat_message is append-mostly (its only in-place write is an agent
-- correcting their own text).
--
-- Kept out of chat_message as rows, too: reactions must never enter the
-- message stream, which drives unread counts, inbox previews and the
-- offline-notification emails — and clients already deployed would render
-- an unknown message kind as a stray text bubble.
--
-- No server-maintained aggregate (counts per emoji): both surfaces derive
-- them client-side from the push subscription, as they already do for
-- unread counts and last-message-at.

CREATE TABLE IF NOT EXISTS public.chat_message_reaction (
    id         serial PRIMARY KEY,
    message_id integer NOT NULL REFERENCES public.chat_message(id),
    -- Who reacted. NOT NULL: unlike a message, a reaction is never a
    -- system line, and "who reacted" is shown in the tooltip.
    person_id  integer NOT NULL REFERENCES public.person(id),
    -- The emoji itself, not a code: the palette lives in the clients and
    -- may grow, and storing the character keeps old rows readable whatever
    -- the palette becomes. Short varchar — an emoji with modifiers (skin
    -- tone, ZWJ sequence) still fits comfortably.
    emoji      character varying(32) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

-- One reaction per person per emoji per message. The toggle relies on it:
-- a repeated tap must not stack duplicates.
CREATE UNIQUE INDEX IF NOT EXISTS chat_message_reaction_unique_idx
    ON public.chat_message_reaction (message_id, person_id, emoji);

-- Reads are always "the reactions of the messages in this thread", which
-- the clients issue as `message.conversation=$1` — the join column needs
-- its own index, the unique one above only helps a message-id equality.
CREATE INDEX IF NOT EXISTS chat_message_reaction_message_idx
    ON public.chat_message_reaction (message_id);

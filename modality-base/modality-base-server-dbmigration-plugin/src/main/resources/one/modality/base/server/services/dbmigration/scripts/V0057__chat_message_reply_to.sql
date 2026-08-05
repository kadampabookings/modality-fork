-- V0057: quote-replies — a message may answer another message.
--
-- Schema only: shipped ahead of the UI so the reply feature costs no
-- further server deploy when the clients are ready. A client that does
-- not know the column simply ignores it, and a message that answers
-- nothing leaves it NULL — which is every message written so far.
--
-- A self-referencing FK on the message, NOT a side table: "which message
-- does this answer" is a property of the answering message, one per
-- message, and it lives and dies with it. (Reactions are the opposite —
-- many per message, deleted independently — hence their own table in
-- V0056.)
--
-- Column name follows the domain-model mapping: the model field is
-- `replyTo`, which maps to `reply_to_id` (FK fields take the _id suffix,
-- as conversation_id and person_id already do on this table).
--
-- Deliberately NOT indexed: the only read is "resolve the quoted message
-- of a message being rendered", which the thread answers from the
-- messages it already holds, or by primary key. An index here would only
-- pay off for a "show all replies to X" view, which does not exist —
-- add it with that feature, not before.
--
-- No ON DELETE clause: chat messages are never deleted by the chat
-- surfaces, so the default (restrict) correctly refuses a delete that
-- would orphan a quote, rather than silently rewriting history.

ALTER TABLE public.chat_message
    ADD COLUMN IF NOT EXISTS reply_to_id integer REFERENCES public.chat_message(id);

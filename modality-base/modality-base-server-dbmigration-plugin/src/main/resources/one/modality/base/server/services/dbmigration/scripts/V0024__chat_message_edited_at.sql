-- V0024: chat message editing — edited_at marker.
--
-- Back-office agents can correct their own replies (typos). The content
-- is updated in place (both threads re-render via their push
-- subscriptions), and edited_at makes the correction honest: both UIs
-- show an "(edited)" marker instead of silently swapping the text the
-- viewer may already have read.
--
-- Display-only timestamp (stamped by the editing client): it is never
-- compared against created_at or the read cursors, so client clock skew
-- is harmless here.

ALTER TABLE public.chat_message
    ADD COLUMN IF NOT EXISTS edited_at timestamp with time zone;

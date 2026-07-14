-- V0021: two more auto-attached context anchors on conversation.
--
--   viewer_lang — the frontoffice UI language at the moment the viewer
--                 created the conversation (i18next tag, e.g. 'fr',
--                 'pt-BR'). Snapshot semantics like viewer_device: it
--                 records the language the chat actually happened in.
--                 The booking-time document.person_lang stays available
--                 as a display fallback for pre-column conversations.
--
--   document_id — the booking the conversation is about. For livestream
--                 chats the widget stamps the viewer's booking for the
--                 event (making the previous read-time person+event
--                 lookup explicit and stable); future surfaces that
--                 start a chat FROM a booking page stamp it directly
--                 and derive event from document.event. NULL for staff
--                 conversations and legacy rows.

ALTER TABLE public.conversation
    ADD COLUMN IF NOT EXISTS viewer_lang character varying(8);

ALTER TABLE public.conversation
    ADD COLUMN IF NOT EXISTS document_id integer REFERENCES public.document(id);

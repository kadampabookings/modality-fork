-- V0137: where an event's audio recordings are stored on Bunny, so the back office can upload them itself.
--
-- WHY. Staff uploaded every recording to the Bunny storage zone by hand and pasted its CDN address into the
-- back office. The audio screen now uploads the file itself (modality-event-server-rest-bunny-stream-vertx-
-- plugin, package bunnyaudio), so the server has to know which folder of the zone an event's files belong
-- in. The folders follow the event's TYPE, not its organisation -- in the shared NKT zone they are, for
-- example:
--     international-festivals/2026/summer/eng/...    (one folder per language)
--     ittp/2026/por/...
--     mkmc-events/2025/2025-11 - Vajrasattva Empowerment - .../   (one language, no language folder)
--     sttp/new-eight-steps-to-happiness/2025-2026-part-2-gen-la-dekyong/   (a course over several months)
-- so there are two columns:
--
--   event_type.audio_bunny_folder_pattern -- what a NEW event of this type starts from, with tokens
--       {year} {month} (the event's start date), {event} (its name) and {lang} (the recording's language
--       folder: eng, fra, cmn, yue ...). "international-festivals/{year}/summer/{lang}" serves every Summer
--       Festival.
--   event.audio_bunny_folder -- this event's own folder, {lang} still unresolved because it differs per file.
--       Written the first time a file is uploaded (from the pattern) or when staff choose it, and kept from
--       then on, so editing the type's pattern later never moves an event that already has files. An STTP
--       course is one event for months and keeps one folder for all of them.
--
-- WRITTEN ONLY BY THE SERVER. Neither column is in the domain model: the back office reads and writes them
-- through the audio-storage bus endpoints, which check the caller may reach /medias for that event's
-- organisation and validate the path (relative, no "..", safe characters). So no client can name a path
-- through a plain submit, and the validation has exactly one place to live.
--
-- NULL means "not set", which every existing row stays: nothing changes until someone uploads.
--
-- Anonymiser: both tables are classified whole-table `not-pii 'event config'` in
-- scripts/gdpr-anonymise/10-anon-helpers.sql; a folder path is configuration, not member data, so the
-- disposition holds and the two rows now name these columns.
--
-- LOCKING. ADD COLUMN with no default is metadata only. The timeout is kept for the reason V0116 gives: a
-- lock wait is a better failure than a queue of them.

DO $do$
BEGIN
    SET LOCAL lock_timeout = '3s';

    ALTER TABLE public.event_type
        ADD COLUMN IF NOT EXISTS audio_bunny_folder_pattern varchar(512);

    COMMENT ON COLUMN public.event_type.audio_bunny_folder_pattern IS
        'Folder of the organisation''s Bunny audio storage zone that new events of this type upload their '
        'recordings to, with tokens {year} {month} {event} {lang}. Written only by the audio-storage '
        'endpoints. NULL: no pattern yet; the back office then suggests one from earlier events.';

    ALTER TABLE public.event
        ADD COLUMN IF NOT EXISTS audio_bunny_folder varchar(512);

    COMMENT ON COLUMN public.event.audio_bunny_folder IS
        'This event''s folder in its organisation''s Bunny audio storage zone, {lang} unresolved (one '
        'subfolder per language when present). Set on first upload from event_type.audio_bunny_folder_pattern '
        'or when staff choose it, then kept. Written only by the audio-storage endpoints.';
END
$do$;

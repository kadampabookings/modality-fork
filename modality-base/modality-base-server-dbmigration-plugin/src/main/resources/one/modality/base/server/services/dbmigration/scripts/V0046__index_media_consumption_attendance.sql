-- Lets account-scoped media-consumption queries run account-first: the front-office video
-- "attended" query (use-video-sessions.ts query C) walks person -> document -> document_line ->
-- attendance -> media_consumption, and without an index on media_consumption.attendance_id the
-- last hop cannot be driven — the planner fell back to an event-first walk over EVERY viewer's
-- consumptions (63k rows / 806 ms warm on the biggest staging event, growing with event size).
-- With this index the account-first union runs bounded by the account's own history instead
-- (staging EXPLAIN: a few hundred rows, ~tens of ms). Complements V0044 (scheduled_item_id),
-- which serves the event-side lookups.
--
-- Idempotent via IF NOT EXISTS — a no-op on staging where the same-named index was already
-- created manually (CONCURRENTLY) for live validation, and applied elsewhere at the next deploy.
-- Plain CREATE INDEX (CONCURRENTLY cannot run inside the migration transaction); the build takes
-- a few seconds (~293k rows), during which concurrent writes to media_consumption block (reads
-- are unaffected).

CREATE INDEX IF NOT EXISTS media_consumption_attendance_idx ON public.media_consumption (attendance_id);

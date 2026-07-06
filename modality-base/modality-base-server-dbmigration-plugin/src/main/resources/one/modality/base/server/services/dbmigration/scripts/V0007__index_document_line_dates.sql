-- Adds the missing date index on document_line for date-window (Gantt) queries,
-- e.g. the back-office /registration-rooms room-allocations query
-- (start_date <= $windowEnd AND end_date >= $windowStart).
--
-- Without it, document_line (~1.4M rows) has only FK indexes, so the planner drove
-- that query through document_line_item_id_idx: 410 'acco' items x ~340 scattered
-- heap rows each ~ 50k random page reads ~ 24s on staging RDS for 460 returned rows
-- (see scripts/explain-room-allocations-staging.sql for the reproduction).
--
-- A composite btree on (end_date, start_date) serves the overlap predicate directly:
-- end_date >= $windowStart bounds the scan range (only current/future stays), and
-- start_date <= $windowEnd is checked in-index before any heap fetch, excluding
-- far-future bookings.
--
-- Idempotent via IF NOT EXISTS — also a no-op where the index was already created
-- manually (staging validation). Plain CREATE INDEX (not CONCURRENTLY, which cannot
-- run inside the migration transaction); the build takes a few seconds on ~1.4M rows,
-- during which concurrent writes to document_line block (reads are unaffected).

CREATE INDEX IF NOT EXISTS document_line_end_date_start_date_idx
    ON public.document_line (end_date, start_date);

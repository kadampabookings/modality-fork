-- Speeds up the date-window (Gantt) queries on document_line, e.g. the back-office
-- /registration-rooms room-allocations query (24s on staging for 460 rows).
--
-- The planner drives that query item_family('acco') -> 410 items -> per-item scans of
-- document_line_item_id_idx, fetching ~340 scattered heap rows per item (~50k random page
-- reads, ~24s of I/O) and discarding 99% on the date filter afterwards. It sticks to that
-- path structurally: the item-count estimate for a join uses rows/distinct-families
-- (1180/23 = 51, vs 410 actual 'acco' items) and MCV stats can't apply across a join, so a
-- plain (end_date, start_date) index would be costed above the item path and never chosen
-- (verified against fresh statistics on staging, 2026-07-06).
--
-- This composite instead accelerates the plan the planner already picks: with
-- (item_id, end_date, start_date) the per-item lookup carries the whole window predicate in
-- the index condition (equality on item_id, range on end_date, in-index check on start_date),
-- so only the ~3 truly matching rows per item are fetched from the heap (~1.4k rows total
-- instead of ~141k). It also strictly subsumes document_line_item_id_idx (same leading
-- column), which can be dropped later once this one is proven in prod.
--
-- Idempotent via IF NOT EXISTS — also a no-op where the index was already created manually
-- (staging validation via scripts/create-document-line-date-index-staging.sql, CONCURRENTLY).
-- Plain CREATE INDEX here (CONCURRENTLY cannot run inside the migration transaction); the
-- build takes a few seconds on ~1.4M rows, during which concurrent writes to document_line
-- block (reads are unaffected).

CREATE INDEX IF NOT EXISTS document_line_item_id_end_date_start_date_idx
    ON public.document_line (item_id, end_date, start_date);

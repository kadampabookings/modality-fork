-- Speeds up the back-office /household DocumentLine query (5552ms on staging for
-- 190 rows), and the Household Occupation (Gantt) tab which runs the same query.
--
-- That query has the same date-window shape as /registration-rooms but selects ALL
-- resource-configured lines with NO item_family('acco') filter, so it never supplies
-- an item_id equality — neither document_line_item_id_idx nor V0007's
-- document_line_item_id_end_date_start_date_idx can seed it, and the planner falls
-- back to a sequential scan of document_line (~1.4M rows).
--
-- Its document_line predicates are:
--   resource_configuration_id IS NOT NULL AND NOT cancelled
--   AND start_date <= rangeEnd AND end_date >= rangeStart
-- This PARTIAL index bakes the two non-date predicates into the index (so it holds
-- only the room-assigned, non-cancelled lines) and carries the window predicate:
-- seek end_date >= rangeStart, then check start_date <= rangeEnd in-index (2nd
-- column, no heap fetch), so only the overlapping rows are read from the heap.
-- Column order matches V0007: lead with end_date (future-bounded, smaller) not
-- start_date (start_date <= rangeEnd would match almost the whole table). Validated
-- on staging (scripts/create-household-doclines-index-staging.sql, CONCURRENTLY):
-- query dropped from 5.5s to well under 1s.
--
-- Idempotent via IF NOT EXISTS — a no-op on staging where the same-named index was
-- already created manually, and applies on prod at the next deploy. Plain CREATE
-- INDEX (CONCURRENTLY cannot run inside the migration transaction); the build takes
-- a few seconds, during which concurrent writes to document_line block (reads are
-- unaffected).

CREATE INDEX IF NOT EXISTS document_line_active_end_date_start_date_idx
    ON public.document_line (end_date, start_date)
    WHERE resource_configuration_id IS NOT NULL AND NOT cancelled;

-- Speeds up the front-office video/streaming query (the ScheduledItem list for an event's
-- 'video' items), 3.17s on prod for event 1857 / 51 rows.
--
-- That query carries a correlated `attended` sub-select per output row:
--   exists(select MediaConsumption where scheduledItem=si and accountCanAccessPersonMedias(...))
-- which compiles to `... from media_consumption tt1 ... where tt1.scheduled_item_id = si.id ...`.
-- media_consumption has no index on scheduled_item_id, so the planner runs a FULL sequential
-- scan of the table (~293k rows, ~2.5k pages) for every outer row — 51 loops here, ~1.4s and
-- ~127k buffer hits, dwarfing the ~10ms the rest of the query takes (EXPLAIN ANALYZE captured
-- via the /monitor Analyze tool):
--   Seq Scan on media_consumption tt1  (actual time=26.98..26.98 rows=0 loops=51)
--       Filter: (scheduled_item_id = si.id)  Rows Removed by Filter: 293540
--
-- This index turns each of those 51 full scans into a scheduled_item_id index seek, so the
-- `attended` sub-select drops from ~27ms/row to sub-millisecond. Single-column, matching the
-- existing FK-index convention (attendance_scheduled_item_idx, scheduled_item_event_idx).
--
-- Idempotent via IF NOT EXISTS — a no-op where the same-named index was already created
-- manually (live validation via scripts/create-media-consumption-scheduled-item-index.sql,
-- CONCURRENTLY), and applies elsewhere at the next deploy. Plain CREATE INDEX (CONCURRENTLY
-- cannot run inside the migration transaction); the build takes a second or two, during which
-- concurrent writes to media_consumption block (reads are unaffected).

CREATE INDEX IF NOT EXISTS media_consumption_scheduled_item_idx
    ON public.media_consumption (scheduled_item_id);

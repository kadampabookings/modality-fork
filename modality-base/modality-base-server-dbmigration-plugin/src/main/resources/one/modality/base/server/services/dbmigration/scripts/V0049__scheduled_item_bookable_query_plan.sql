-- Speeds up the front-office bookable-scheduled-items query (ServerPolicyServiceProvider's
-- SCHEDULED_ITEMS_DQL_BASE — the ScheduledItem list the booking form loads per event, with
-- the per-item male/female availabilities aggregate): ~310ms / ~200k buffer hits for
-- event 1898, all cache hits, so CPU-bound and far worse cold.
--
-- The query's driving predicate is `bookable_scheduled_item_id = id` (self-bookable rows),
-- a column = column clause the planner cannot estimate (default 0.5% selectivity → ~122
-- rows where ~22.4k of ~24k actually match; extended/expression statistics were tried on
-- staging and do NOT apply to a bare col=col clause). The fix is therefore two-sided:
--
--  * The DQL now reads the event CTE `e` via scalar sub-selects instead of joining it in
--    FROM (see SCHEDULED_ITEMS_DQL_BASE), turning the event-match OR into a restriction
--    clause with InitPlan params instead of a join clause the planner can only apply after
--    all the display joins.
--  * This partial index then serves BOTH arms of that OR as a single BitmapOr:
--      (event_id = $event)  or  (event_id IS NULL and site_id = $venue [+ date range]),
--    restricted to self-bookable rows like the existing scheduled_item_self_bookable_item_idx.
--
-- Staging-verified with the compiler-generated SQL for event 1898: 301ms / 200k buffers
-- → 80ms / 95k buffers (together with the lateral `offset 0` fix in the DQL→SQL compiler,
-- which stops the avail sub-select being re-evaluated once per sum() reference).
--
-- The index is useful from the moment it exists and harmless before the new server code
-- runs; the old query shape simply ignores it. Plain CREATE INDEX (CONCURRENTLY cannot run
-- inside the migration transaction): sub-second build on this ~24k-row table, during which
-- concurrent writes to scheduled_item block (reads are unaffected).
-- Idempotent via IF NOT EXISTS in case the index was already created manually.

CREATE INDEX IF NOT EXISTS scheduled_item_self_bookable_event_site_date_idx
    ON public.scheduled_item (event_id, site_id, date)
    WHERE bookable_scheduled_item_id = id;

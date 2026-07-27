-- Round 2 of the bookable-scheduled-items query optimisation (see V0049): three indexes
-- that shrink the per-row availability aggregate (maleFemaleAvailabilities in
-- SCHEDULED_ITEMS_DQL_BASE), which after V0049 accounted for ~109ms of the remaining
-- ~131ms on prod (100k of 110k buffer hits).
--
--  * attendance (scheduled_item_id, document_line_id) WHERE present — the two per-rc
--    booking sums probe attendance by scheduled_item and only need document_line_id:
--    this makes that side an Index Only Scan with 0 heap fetches (was ~15.6k buffers
--    per sum on prod, ~2.2k after). The partial predicate bakes in the `present` filter
--    (99.9% of rows are present, so the partial saves no space — it exists so the
--    filter needs no heap visit).
--  * document_line (resource_configuration_id) INCLUDE (id, quantity, frontend_released,
--    reserved) — covers everything the sums read from document_line, making that side
--    index-only too. `id` MUST be in the INCLUDE list: it is the join key against
--    attendance.document_line_id (btree secondary indexes store heap TIDs, not the PK).
--    Partial on rc IS NOT NULL (326k of 1.36M rows); the probes always have a non-null rc.
--  * resource_configuration (resource_id, item_id) — the applicable-config lookup probes
--    rc per resource of the site (16.5k probes/query); pushing item_id into the index
--    condition skips the heap fetch for non-matching items (51k → 36k buffers). Also
--    picked up by the event-override anti-join.
--
-- Staging-verified on the deployed (V0049) query shape, warm: 119ms / 95k buffers
-- → 61ms / 58k buffers for event 1898.
--
-- ⚠ Rollout: run the aggregate repo's scripts/create-availability-subplan-indexes.sql
-- (CREATE INDEX CONCURRENTLY) on the environment BEFORE deploying, so these IF NOT EXISTS
-- statements are no-ops. If the migration does build them itself (CONCURRENTLY cannot run
-- inside the migration transaction), the attendance one scans 6.5M rows (~10-20s) while
-- holding a lock that blocks concurrent writes to attendance — tolerable at boot but
-- avoidable via the manual script.

CREATE INDEX IF NOT EXISTS attendance_scheduled_item_document_line_present_idx
    ON public.attendance (scheduled_item_id, document_line_id)
    WHERE present;

CREATE INDEX IF NOT EXISTS document_line_rc_covering_idx
    ON public.document_line (resource_configuration_id)
    INCLUDE (id, quantity, frontend_released, reserved)
    WHERE resource_configuration_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS resource_configuration_resource_item_idx
    ON public.resource_configuration (resource_id, item_id);

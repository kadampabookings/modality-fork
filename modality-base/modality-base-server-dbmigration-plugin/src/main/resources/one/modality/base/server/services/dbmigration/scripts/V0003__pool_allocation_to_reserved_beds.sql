-- BOOT-MIGRATION VERSION of the aggregate repo's scripts/migrate-pool-to-resource-configuration.sql
-- (staging-rehearsed 2026-07-04): BEGIN/COMMIT stripped (the migration runner owns the single
-- transaction) and the post-migration report dropped (read-only — run it manually from scripts/).
-- Verification after deploy: scripts/verify-pool-migration.sql (all sections must be empty).
--
-- Migration: replace PoolAllocation with resource_configuration.max_reserved + pool_id.
-- Plan: docs/pool-allocation-removal-plan.md (workstream A, steps A1–A5).
-- Census: scripts/diagnose-pool-allocation-census.sql (run 2026-07-03: 558 rows, tiny footprint).
--
-- DOMAIN RULE
-- -----------
-- A room's beds are partitioned into PUBLIC beds (max - max_reserved, bookable online iff rc.online)
-- and RESERVED beds (max_reserved, never publicly bookable; pool_id = the informative reason —
-- Residents, Staff, Volunteers…). document_line.reserved (new boolean) is the partition marker:
-- false = the line consumed a public bed, true = a reserved bed. document_line.pool_id is purely
-- informative (the reason, mirroring rc.pool_id — may be NULL even on reserved lines, since rc.pool
-- is optional). Pool-targeted bookings (volunteer events) match rc.pool_id via the requested pool
-- (document_line.pool or event.default_pool_id) and come out reserved = true.
--
-- Mapping from pool_allocation — VERIFIED against the deployed engines (see plan, A3/A4):
--
-- GLOBAL scope (event IS NULL): the availability engine ignores global allocations (event-only since
-- git 4c2081727); rc.online + rc.max are operative and global PUBLIC allocations are vestigial
-- (enabled flag uncorrelated with rc.online, quantity = max in 231/237). Global RESERVED allocations
-- are ROOM CATEGORIZATION (residents room list, volunteer room identification), NOT capacity holds:
-- production sells those beds (found on the staging rehearsal: baking Residents quantities into
-- max_reserved emptied ITTP's Ensuite-single availability). The global pass is therefore fully
-- BEHAVIOR-PRESERVING — reserved-bed protection is an OPT-IN staff enable per room going forward:
--   online       : NEVER written (operative today, must survive untouched)
--   pool_id      = the non-public pool with the largest quantity (categorization/reason)
--   max_reserved = ONLINE room  -> NULL (no capacity hold; availability unchanged;
--                                  volunteer flows book via backend, which bypasses capacity and
--                                  counts correctly through reserved markers + the physical cap)
--                  OFFLINE room -> least(reserved_qty, max) (no public impact — already 0 public;
--                                  preserves pool-targeted capacity for held-back rooms)
--   public-pool allocations: ignored entirely
--
-- EVENT scope (open events): event allocations ARE operative for availability. So:
--   public_qty   = SUM(quantity) over allows_public pools (enabled or not — the bed split)
--   online       = at least one public allocation with public_booking_enabled (the release gate)
--   max_reserved = has public alloc ? clamp(max - public_qty, 0, max) : least(reserved_qty, max)
--   pool_id      = the non-public pool with the largest quantity
--
-- WHAT THIS SCRIPT DOES NOT DO
-- ----------------------------
-- pool_allocation is NOT modified or dropped — KBS2's back-office still reads/writes it (drop is
-- Phase 2, gated on KBS2 retirement). This script only stops KBS3 from needing it.
--
-- ORDERING CONSTRAINT: the column rename breaks the two PL/pgSQL functions referencing max_private
-- at their next execution — they are re-created here inside the same transaction.
--
-- Idempotency / re-run: safe to re-run BEFORE go-live (the reset pass recomputes everything from
-- pool_allocation). ONE-SHOT after go-live: re-running would wipe staff edits made in the new UI.
--
-- Deploy together with (same window): the rewritten deferred_allocate_document_line() trigger, the
-- new ServerPolicyServiceProvider, the KBS3 builds, and the KBS2 redeploy (metadata rename).


-- ============================================================================
-- A0. Transaction context & document_line trigger suspension
--     • set_transaction_parameters(true): the allocation machinery reads the
--       session temp table transaction_parameter (backend flag, ON COMMIT
--       DROP) — create it so any trigger work in this transaction would run
--       as a backend request instead of erroring.
--     • DISABLE TRIGGER USER on document_line: the data passes below update
--       pool_id / reserved / resource_configuration_id on hundreds of lines;
--       the trigger machinery must not react — defer_allocate would queue a
--       DEFERRED re-allocation of every touched line at COMMIT (rewriting
--       their config assignments), on_not_system_allocated would mislabel the
--       A4c re-pointed lines as manually allocated, and the mates-cascade
--       would churn share-mate fields. This migration is authoritative about
--       markers and pointers. (FK constraint triggers stay active; sys_log
--       notifications are muted — fine in the maintenance window, the deploy
--       follows immediately.) Triggers are re-enabled in A7; any failure
--       before COMMIT rolls everything back, including these ALTERs.
--     • DROP TRIGGER defer_allocate: recreated in A7 with `reserved` added to
--       its column list (a partition change must re-raise allocation).
--     Requires a role that owns document_line (same requirement as A1's
--     ALTER TABLE).
-- ============================================================================

DO $$ BEGIN PERFORM set_transaction_parameters(true); END $$;  -- DO/PERFORM: side-effect call, no result set through the submit pipeline

ALTER TABLE public.document_line DISABLE TRIGGER USER;

DROP TRIGGER defer_allocate ON public.document_line;

-- ============================================================================
-- A1. Schema
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'resource_configuration'
                  AND column_name = 'max_private') THEN
        ALTER TABLE public.resource_configuration RENAME COLUMN max_private TO max_reserved;
    END IF;
END $$;

ALTER TABLE public.resource_configuration
    ADD COLUMN IF NOT EXISTS pool_id integer REFERENCES public.pool(id);

ALTER TABLE public.document_line
    ADD COLUMN IF NOT EXISTS reserved boolean DEFAULT false NOT NULL;

COMMENT ON COLUMN public.resource_configuration.max_reserved IS
    'Reserved beds: withheld from public booking (public beds = max - max_reserved, bookable iff online). NULL/0 = none; = max ⇒ whole room reserved. Bookings consuming reserved beds carry document_line.reserved = true.';
COMMENT ON COLUMN public.resource_configuration.pool_id IS
    'Room categorization / reason beds are held (informative; also drives pool-targeted allocation for volunteer-style events via event.default_pool_id / document_line.pool, and the residents room list). May be set with max_reserved NULL/0 — categorization without a capacity hold.';
COMMENT ON COLUMN public.document_line.reserved IS
    'Partition marker: true = this line consumes a RESERVED bed (rc.max_reserved partition) of its resource_configuration; false = a public bed. NOT the booking-status sense of "reserved". pool_id is the informative reason (may be NULL on reserved lines — rc.pool is optional).';

-- ============================================================================
-- A2. Re-create the two functions that referenced max_private (rename fallout)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.record_changes_for_notification_resource_configuration() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN

IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.resource_id IS DISTINCT FROM NEW.resource_id) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'resource_id'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.item_id IS DISTINCT FROM NEW.item_id) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'item_id'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.start_date IS DISTINCT FROM NEW.start_date) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'start_date'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.end_date IS DISTINCT FROM NEW.end_date) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'end_date'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.max IS DISTINCT FROM NEW.max) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'max'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.max_paper IS DISTINCT FROM NEW.max_paper) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'max_paper'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.max_reserved IS DISTINCT FROM NEW.max_reserved) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'max_reserved'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.pool_id IS DISTINCT FROM NEW.pool_id) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'pool_id'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.comment IS DISTINCT FROM NEW.comment) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'comment'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.online IS DISTINCT FROM NEW.online) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'online'); END IF;
IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE' AND OLD.name IS DISTINCT FROM NEW.name) THEN INSERT into sys_log (table_name, update, oid, column_name) values ('resource_configuration', true, NEW.id, 'name'); END IF;
RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.copy_resource_configuration(src_resource_id integer, src_resource_configuration_id integer, dst_resource_id integer) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
	new_info record;new_id int := -1;BEGIN
    for new_info in

with src as (
	select * from resource_configuration where (src_resource_configuration_id is not null and id = src_resource_configuration_id) or (src_resource_configuration_id is null and resource_id = src_resource_id)
),
row_src as (select row_number() over (order by id),* from src order by id),
new as (
	insert into resource_configuration (resource_id, name, item_id, start_date, end_date, online, max, max_paper, max_reserved, pool_id, comment)
              					 select dst_resource_id, name, item_id, start_date, end_date, online, max, max_paper, max_reserved, pool_id, comment from row_src
   returning *
),
row_new as (select row_number() over (order by id),* from new)
select rn.id as new_id, rs.id as src_id from row_new rn join row_src rs on rs.row_number=rn.row_number

	 loop
		if (new_id = -1) then
			new_id := new_info.new_id;end if;end loop;return new_id;END;$$;

-- ============================================================================
-- A3-pre. Reset pass — clean slate (45 prod rows carry stale legacy max_private
-- values that would otherwise wrongly shrink public capacity; also makes the
-- data passes below deterministic on re-run).
-- ============================================================================

UPDATE public.resource_configuration
   SET max_reserved = NULL, pool_id = NULL
 WHERE max_reserved IS NOT NULL OR pool_id IS NOT NULL;

-- ============================================================================
-- A3. Global (event IS NULL) RESERVED allocations → current & future global
--     RC slices. Public-pool globals are vestigial → ignored. online untouched.
--     BEHAVIOR-PRESERVING: pool = categorization on every allocated room;
--     max_reserved only on OFFLINE rooms (0 public impact); ONLINE rooms keep
--     their full public capacity — protection is opt-in via the new UI.
-- ============================================================================

WITH g AS (
    SELECT pa.resource_id,
           SUM(pa.quantity)                                                         AS reserved_qty,
           (ARRAY_AGG(pa.pool_id ORDER BY pa.quantity DESC NULLS LAST))[1]          AS reason_pool_id
      FROM public.pool_allocation pa
      JOIN public.pool p ON p.id = pa.pool_id
     WHERE pa.event_id IS NULL AND pa.resource_id IS NOT NULL AND NOT p.allows_public
     GROUP BY pa.resource_id
)
UPDATE public.resource_configuration rc
   SET max_reserved = CASE WHEN rc.online THEN NULL
                           ELSE LEAST(COALESCE(g.reserved_qty, 0), COALESCE(rc.max, 0)) END,
       pool_id      = g.reason_pool_id
  FROM g
 WHERE rc.resource_id = g.resource_id
   AND rc.event_id IS NULL
   AND (rc.end_date IS NULL OR rc.end_date >= current_date);

-- ============================================================================
-- A4. Event-scoped allocations → RC overrides of OPEN events only
--     (past events keep their pool_allocation rows untouched until Phase 2)
-- ============================================================================

-- A4a. Update existing event overrides
WITH ea AS (
    SELECT pa.event_id, pa.resource_id,
           SUM(pa.quantity) FILTER (WHERE p.allows_public)                          AS public_qty,
           SUM(pa.quantity) FILTER (WHERE NOT p.allows_public)                      AS reserved_qty,
           BOOL_OR(p.allows_public)                                                 AS has_public,
           BOOL_OR(p.allows_public AND pa.public_booking_enabled)                   AS has_enabled_public,
           (ARRAY_AGG(pa.pool_id ORDER BY pa.quantity DESC NULLS LAST)
                FILTER (WHERE NOT p.allows_public))[1]                              AS reason_pool_id
      FROM public.pool_allocation pa
      JOIN public.pool p ON p.id = pa.pool_id
      JOIN public.event e ON e.id = pa.event_id
     WHERE pa.resource_id IS NOT NULL AND e.end_date >= current_date
     GROUP BY pa.event_id, pa.resource_id
)
UPDATE public.resource_configuration rc
   SET max_reserved = CASE WHEN ea.has_public
                           THEN LEAST(GREATEST(COALESCE(rc.max, 0) - COALESCE(ea.public_qty, 0), 0), COALESCE(rc.max, 0))
                           ELSE LEAST(COALESCE(ea.reserved_qty, 0), COALESCE(rc.max, 0)) END,
       pool_id      = CASE WHEN (CASE WHEN ea.has_public
                                      THEN LEAST(GREATEST(COALESCE(rc.max, 0) - COALESCE(ea.public_qty, 0), 0), COALESCE(rc.max, 0))
                                      ELSE LEAST(COALESCE(ea.reserved_qty, 0), COALESCE(rc.max, 0)) END) > 0
                           THEN ea.reason_pool_id END,
       online       = ea.has_enabled_public
  FROM ea
 WHERE rc.event_id = ea.event_id AND rc.resource_id = ea.resource_id;

-- A4b. Insert missing event overrides (census: 16 rooms across 4 open events),
--      copying the global config that overlaps the event, with max raised to fit
--      the allocated quantities (volunteer rooms may exceed the physical max —
--      today pa.quantity overrides rc.max, so this preserves capacity).
WITH ea AS (
    SELECT pa.event_id, pa.resource_id,
           SUM(pa.quantity)                                                         AS total_qty,
           SUM(pa.quantity) FILTER (WHERE p.allows_public)                          AS public_qty,
           SUM(pa.quantity) FILTER (WHERE NOT p.allows_public)                      AS reserved_qty,
           BOOL_OR(p.allows_public)                                                 AS has_public,
           BOOL_OR(p.allows_public AND pa.public_booking_enabled)                   AS has_enabled_public,
           (ARRAY_AGG(pa.pool_id ORDER BY pa.quantity DESC NULLS LAST)
                FILTER (WHERE NOT p.allows_public))[1]                              AS reason_pool_id
      FROM public.pool_allocation pa
      JOIN public.pool p ON p.id = pa.pool_id
      JOIN public.event e ON e.id = pa.event_id
     WHERE pa.resource_id IS NOT NULL AND e.end_date >= current_date
     GROUP BY pa.event_id, pa.resource_id
)
INSERT INTO public.resource_configuration
       (resource_id, item_id, event_id, start_date, end_date, name, comment,
        max, max_paper, online,
        allows_male, allows_female, allows_guest, allows_special_guest,
        allows_volunteer, allows_resident, allows_resident_family, allows_lay, allows_ordained,
        max_reserved, pool_id)
SELECT ea.resource_id, src.item_id, ea.event_id, NULL, NULL, src.name, src.comment,
       GREATEST(COALESCE(src.max, 0), COALESCE(ea.total_qty, 0)) AS new_max,
       src.max_paper,
       ea.has_enabled_public,
       src.allows_male, src.allows_female, src.allows_guest, src.allows_special_guest,
       src.allows_volunteer, src.allows_resident, src.allows_resident_family, src.allows_lay, src.allows_ordained,
       CASE WHEN ea.has_public
            THEN LEAST(GREATEST(GREATEST(COALESCE(src.max, 0), COALESCE(ea.total_qty, 0)) - COALESCE(ea.public_qty, 0), 0),
                       GREATEST(COALESCE(src.max, 0), COALESCE(ea.total_qty, 0)))
            ELSE COALESCE(ea.reserved_qty, 0) END,
       CASE WHEN COALESCE(ea.reserved_qty, 0) > 0 OR NOT ea.has_public THEN ea.reason_pool_id END
  FROM ea
  JOIN public.event e ON e.id = ea.event_id
  JOIN LATERAL (
        SELECT rc0.* FROM public.resource_configuration rc0
         WHERE rc0.resource_id = ea.resource_id AND rc0.event_id IS NULL
         ORDER BY CASE WHEN kbs_overlaps(e.start_date, e.end_date, rc0.start_date, rc0.end_date) THEN 0 ELSE 1 END,
                  rc0.start_date DESC NULLS LAST
         LIMIT 1
       ) src ON TRUE
 WHERE NOT EXISTS (SELECT 1 FROM public.resource_configuration rc2
                    WHERE rc2.event_id = ea.event_id AND rc2.resource_id = ea.resource_id);

-- ============================================================================
-- A4c. Re-point existing lines of OPEN events to their event override (critical).
--      Lines booked before an override existed reference the room's GLOBAL
--      config; availability counts lines via documentLine.resourceConfiguration
--      = rc, and post-migration the applicable rc for the event IS the
--      override — without this pass those bookings become invisible and the
--      room over-reports (found on the staging rehearsal: Fall Festival Camping
--      showed 350 available instead of 350 − 181 booked). No-op for lines
--      already pointing at the override; open events only (past events need no
--      availability). Requires A0's trigger suspension (on_not_system_allocated
--      would otherwise flag these lines as manually allocated).
-- ============================================================================

UPDATE public.document_line dl
   SET resource_configuration_id = rc_override.id
  FROM public.document d,
       public.event e,
       public.resource_configuration rc_global,
       public.resource_configuration rc_override
 WHERE d.id = dl.document_id
   AND e.id = d.event_id AND e.end_date >= current_date
   AND rc_global.id = dl.resource_configuration_id
   AND rc_global.event_id IS NULL
   AND rc_override.event_id = d.event_id
   AND rc_override.resource_id = rc_global.resource_id;

-- ============================================================================
-- A5. document_line partition markers.
--     Public-pool ids become NULL (the trigger used to stamp the PUBLIC pool —
--     e.g. "General public", "Attendees" — on ordinary online bookings; those
--     lines are public-partition consumers: reserved stays false, pool cleared;
--     census: 422 rows in prod). Non-public markers — Volunteers, Staff… —
--     mark reserved-bed consumers: reserved = true, pool kept as the reason.
--     Post-migration invariant: reserved = (pool_id IS NOT NULL); it diverges
--     later only when staff reserve beds without a reason pool.
-- ============================================================================

UPDATE public.document_line dl
   SET pool_id = NULL
  FROM public.pool p
 WHERE p.id = dl.pool_id AND p.allows_public;

UPDATE public.document_line dl
   SET reserved = true
  FROM public.pool p
 WHERE p.id = dl.pool_id AND NOT p.allows_public AND NOT dl.reserved;

-- ============================================================================
-- A7. Recreate the defer_allocate trigger, with `reserved` added to its column
--     list and to the function's real-change condition: a partition change
--     alone (back-office drop between partitions of the same room, workstream
--     F) must re-raise allocation exactly like a pool or config change.
-- ============================================================================

CREATE OR REPLACE FUNCTION public.trigger_document_line_defer_allocate() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    share bool;
BEGIN
    -- RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    -- first, checking that there was really a change (an update trigger is triggered whenever new values are identical or not to old ones)
    IF (TG_OP = 'INSERT' or NEW.site_id is distinct from OLD.site_id or NEW.item_id is distinct from OLD.item_id or NEW.dates is distinct from OLD.dates or NEW.pool_id is distinct from OLD.pool_id or NEW.reserved is distinct from OLD.reserved or NEW.resource_configuration_id is distinct from OLD.resource_configuration_id) THEN
        -- then, checking that it's not a share_mate item (because mates are managed by on_share_linked_copy_info trigger that automatically allocates to the same resource as the room booker when the link is made)
        select into share share_mate from item where id=NEW.item_id;
        IF share = false THEN
            -- Now all OK to trigger a normal allocation
            update document_line dlu set trigger_defer_allocate=true from document_line dl join item i on i.id=dl.item_id where dl.id=dlu.id and dl.trigger_defer_allocate=false and dl.id=NEW.id and (i.code is null or i.code not in('pdej', 'din')); -- for French Festivals: excluding Petit Déjeuner and Dîner (keeping only Déjeuner)
        END IF;
    END IF;
    return NEW;
END $$;

CREATE TRIGGER defer_allocate
    AFTER INSERT OR UPDATE OF site_id, item_id, resource_configuration_id, pool_id, reserved, private, dates
    ON public.document_line
    FOR EACH ROW
    WHEN ((new.share_mate_owner_document_line_id IS NULL))
    EXECUTE FUNCTION public.trigger_document_line_defer_allocate();

-- Re-enable the document_line trigger machinery suspended in A0 (the freshly
-- created defer_allocate above is enabled by default; this restores the rest).
ALTER TABLE public.document_line ENABLE TRIGGER USER;

-- ============================================================================
-- A8. Integrity (added last: the data passes above clamp everything into range)
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'resource_configuration_max_reserved_check') THEN
        ALTER TABLE public.resource_configuration
            ADD CONSTRAINT resource_configuration_max_reserved_check
            CHECK (max_reserved IS NULL OR (max_reserved >= 0 AND (max IS NULL OR max_reserved <= max)));
    END IF;
END $$;

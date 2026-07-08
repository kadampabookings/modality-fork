-- V0009: drop the pool_allocation table — the reserved-bed model (V0003/V0004) fully
-- replaced it, and Phase 2 has retired its last readers/writers:
--   • the availability engine has been pool-agnostic for KBS2 events since V0004
--     (ServerPolicyServiceProvider references it in comments only);
--   • the Java room-setup plugins that managed it are deleted (modality-fork);
--   • KBS2's ResourcesGraphicActivity no longer reads it (kbs2 f3fa308f);
--   • the React /rooms delete-gate that cleaned up its rows is removed in the same
--     coordinated change as this migration.
--
-- MUST deploy together with: the React build without the /rooms PoolAllocation delete-gate,
-- the domain model with class 141 removed (system.script + DomainModel regen), and the
-- entity deletion (PoolAllocation.java/Impl + EntityFactoryProvider). Server before/with React.
--
-- KEEP pool.allows_public — V0008's allocation-trigger guard reads it.
--
-- DROP TABLE removes the table with its primary key, the partial index
-- pool_allocation_resource_id_idx and its four outbound foreign keys (event/person/pool/
-- resource); no inbound FKs, triggers or views depend on it (verified 2026-07-08). The id
-- sequence is column-owned and drops with the table; the explicit DROP SEQUENCE IF EXISTS is
-- a no-op safety net for a standalone sequence. Idempotent via IF EXISTS.

DROP TABLE IF EXISTS public.pool_allocation;
DROP SEQUENCE IF EXISTS public.pool_allocation_id_seq;

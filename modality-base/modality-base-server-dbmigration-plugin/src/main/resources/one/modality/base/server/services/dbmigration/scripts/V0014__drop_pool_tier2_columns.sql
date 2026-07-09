-- V0014: drop the vestigial Pool columns graphic, event_type_id and bookable.
--
-- Background
--   In the reserved-beds model a Pool is a "reason" for reserving beds (name +
--   web_color) plus the allows_public public-marker; the rich attributes from
--   the old bookable-category model are no longer used. graphic, event_type_id
--   and bookable have NO functional consumer: nothing in the SQL (allocation
--   trigger / price function), the KBS3 React app, or the modality-fork Java
--   reads or writes them. The KBS3 pool editor and the Pool entity/DomainModel
--   dropped them alongside this migration.
--
-- KBS2 safety
--   KBS2 is still live on this shared DB. It builds its domain model purely from
--   HSQL metadata (system.script) with no DB-schema validation, no reflection
--   and no SELECT *, resolving columns lazily per query — and no KBS2 query
--   references these three fields. So dropping the columns cannot break KBS2 at
--   boot or runtime. The matching KBS2 system.script FIELD rows (1669 graphic,
--   1670 eventType, 1682 bookable) are removed in the same change for metadata
--   hygiene.
--
-- Reversible? No (drops columns). The data is unused, so there is nothing to
-- preserve; a re-add would recreate empty columns.

ALTER TABLE pool
    DROP COLUMN IF EXISTS graphic,
    DROP COLUMN IF EXISTS event_type_id,
    DROP COLUMN IF EXISTS bookable;

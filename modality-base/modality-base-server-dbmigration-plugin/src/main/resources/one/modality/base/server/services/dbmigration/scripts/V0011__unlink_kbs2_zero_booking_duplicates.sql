-- V0011: unlink the ZERO-BOOKING duplicate KBS2 resources that share a KBS3
-- global card, in drift groups that have exactly ONE booked keeper.
--
-- Background
--   resource.kbs2_to_kbs3_global_resource_id was populated by a one-off
--   NAME-MATCH query (any event-scoped resource linked to the global resource
--   of the same organization with the same normalised name). Long-running or
--   multi-typed rooms have SEVERAL same-named KBS2 resources per event (e.g.
--   three "204"s: Ensuite single / twin / Standard single), so they ALL got
--   linked to the one KBS3 global card — an N:1 "drift". The registration grid
--   then can't tell which config is the room's real one, and a drop can reprice
--   to whatever config happens to be newest instead of the one in use.
--
-- What this repairs (SAFE, conservative)
--   For every drift group (same event + same KBS3 global, >1 linked resource)
--   that has EXACTLY ONE resource carrying bookings, it clears the link on the
--   OTHER, zero-booking resources. The booked keeper stays linked, and no
--   booking (document_line) references any unlinked resource's config — so
--   nothing an existing booking points at changes. On staging this is 834
--   resources across 714 groups (0 orphans), validated by
--   scripts/kbs2-unlink-zero-booking.sql; each environment repairs its own
--   drift. Groups with NO booked resource, or with >1 booked resource, are
--   deliberately left for a later, keeper-rule pass.
--
-- Safety
--   Runs inside the migration's single transaction (per index.txt). Two guards
--   abort the whole migration (deploy halts, /health stays red) if anything is
--   off: an implausibly large set, or any resource whose removal would orphan
--   its room (leave the group with no linked keeper).
--
-- Reversibility
--   The cleared links are snapshotted into v0011_kbs2_link_backup (retained).
--   To undo:
--     update resource ru set kbs2_to_kbs3_global_resource_id = b.old_global_id
--     from v0011_kbs2_link_backup b where b.resource_id = ru.id;
--   The table can be dropped once the change has bedded in.
--
-- Note: joins document_line (~1.4M rows) once; expect a few seconds at boot.

-- 1. Snapshot the safe set (the links about to be cleared).
CREATE TABLE v0011_kbs2_link_backup AS
WITH grp AS (
    SELECT s.event_id AS ev, r.kbs2_to_kbs3_global_resource_id AS gid, r.id AS res
    FROM resource r JOIN site s ON s.id = r.site_id
    WHERE r.kbs2_to_kbs3_global_resource_id IS NOT NULL AND s.event_id IS NOT NULL
),
dup AS (SELECT ev, gid FROM grp GROUP BY ev, gid HAVING count(*) > 1),
dupres AS (SELECT g.ev, g.gid, g.res FROM grp g JOIN dup d ON d.ev = g.ev AND d.gid = g.gid),
bk AS (
    SELECT rc.resource_id AS res, count(dl.id) AS n_bk
    FROM resource_configuration rc
    LEFT JOIN document_line dl ON dl.resource_configuration_id = rc.id
    WHERE rc.resource_id IN (SELECT res FROM dupres)
    GROUP BY rc.resource_id
),
grp_booked AS (
    SELECT dr.ev, dr.gid, sum((coalesce(bk.n_bk, 0) > 0)::int) AS booked
    FROM dupres dr LEFT JOIN bk ON bk.res = dr.res
    GROUP BY dr.ev, dr.gid
)
SELECT r.id                                  AS resource_id,
       r.kbs2_to_kbs3_global_resource_id     AS old_global_id,
       s.event_id                            AS event_id
FROM resource r
JOIN site s        ON s.id = r.site_id
JOIN dupres dr     ON dr.res = r.id
JOIN grp_booked gb ON gb.ev = dr.ev AND gb.gid = dr.gid AND gb.booked = 1
LEFT JOIN bk       ON bk.res = r.id
WHERE coalesce(bk.n_bk, 0) = 0;

-- 2. Guards: abort the migration on anything unexpected.
DO $$
DECLARE
    n       integer;
    orphans integer;
BEGIN
    SELECT count(*) INTO n FROM v0011_kbs2_link_backup;
    IF n > 5000 THEN
        RAISE EXCEPTION 'V0011 aborting: safe-unlink set unexpectedly large (%). Investigate before applying.', n;
    END IF;
    -- No group may be left without another linked (the booked keeper) resource.
    SELECT count(*) INTO orphans
    FROM v0011_kbs2_link_backup b
    WHERE NOT EXISTS (
        SELECT 1 FROM resource r2 JOIN site s2 ON s2.id = r2.site_id
        WHERE r2.kbs2_to_kbs3_global_resource_id = b.old_global_id
          AND s2.event_id = b.event_id
          AND r2.id <> b.resource_id
          AND r2.id NOT IN (SELECT resource_id FROM v0011_kbs2_link_backup)
    );
    IF orphans > 0 THEN
        RAISE EXCEPTION 'V0011 aborting: % resource(s) would orphan their room (no linked keeper left).', orphans;
    END IF;
    RAISE NOTICE 'V0011: unlinking % zero-booking duplicate KBS2 resources (0 orphans).', n;
END $$;

-- 3. Clear the links.
UPDATE resource
SET kbs2_to_kbs3_global_resource_id = NULL
WHERE id IN (SELECT resource_id FROM v0011_kbs2_link_backup);

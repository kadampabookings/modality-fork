-- V0012: KBS2 drift cleanup, pass 2 — the ALL-ZERO-BOOKING groups.
--
-- Follows V0011 (which handled drift groups with exactly one booked keeper).
-- This handles drift groups (same event + same KBS3 global, >1 linked
-- resource) in which NO resource carries any bookings — the room was never
-- booked for that event under any of its duplicate configs. It keeps ONE
-- resource linked (the one with the NEWEST config = highest resource_configuration
-- id, which is also the config the registration grid displays) and clears the
-- link on the rest.
--
-- Zero booking impact: nothing is booked in these groups, so no document_line
-- points at any unlinked resource. It only decides which single link
-- represents the room for the event going forward (display + future
-- allocation). On staging: 766 groups, 1031 resources unlinked, 0 orphans.
--
-- Safety / reversibility: as V0011 — one transaction, guards abort on an
-- implausible set or any orphaned room, cleared links snapshotted into
-- v0012_kbs2_link_backup (owned by the migration role). Undo:
--   update resource ru set kbs2_to_kbs3_global_resource_id = b.old_global_id
--   from v0012_kbs2_link_backup b where b.resource_id = ru.id;

-- 1. Snapshot the resources to unlink (everything but each group's newest-config keeper).
CREATE TABLE v0012_kbs2_link_backup AS
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
grp_stat AS (
    SELECT dr.ev, dr.gid, sum((coalesce(bk.n_bk, 0) > 0)::int) AS booked
    FROM dupres dr LEFT JOIN bk ON bk.res = dr.res
    GROUP BY dr.ev, dr.gid
),
allzero AS (SELECT ev, gid FROM grp_stat WHERE booked = 0),
res_cfg AS (   -- each resource's newest config id (configless -> 0, sorts last)
    SELECT dr.ev, dr.gid, dr.res, coalesce(max(rc.id), 0) AS max_cfg
    FROM dupres dr
    JOIN allzero az ON az.ev = dr.ev AND az.gid = dr.gid
    LEFT JOIN resource_configuration rc ON rc.resource_id = dr.res
    GROUP BY dr.ev, dr.gid, dr.res
),
ranked AS (
    SELECT ev, gid, res,
           row_number() OVER (PARTITION BY ev, gid ORDER BY max_cfg DESC, res DESC) AS rn
    FROM res_cfg
)
SELECT r.id                              AS resource_id,
       r.kbs2_to_kbs3_global_resource_id AS old_global_id,
       s.event_id                        AS event_id
FROM ranked rk
JOIN resource r ON r.id = rk.res
JOIN site s     ON s.id = r.site_id
WHERE rk.rn > 1;   -- keep rn = 1 (newest config), unlink the rest

-- 2. Guards.
DO $$
DECLARE
    n       integer;
    orphans integer;
BEGIN
    SELECT count(*) INTO n FROM v0012_kbs2_link_backup;
    IF n > 5000 THEN
        RAISE EXCEPTION 'V0012 aborting: unlink set unexpectedly large (%). Investigate before applying.', n;
    END IF;
    SELECT count(*) INTO orphans
    FROM v0012_kbs2_link_backup b
    WHERE NOT EXISTS (
        SELECT 1 FROM resource r2 JOIN site s2 ON s2.id = r2.site_id
        WHERE r2.kbs2_to_kbs3_global_resource_id = b.old_global_id
          AND s2.event_id = b.event_id
          AND r2.id <> b.resource_id
          AND r2.id NOT IN (SELECT resource_id FROM v0012_kbs2_link_backup)
    );
    IF orphans > 0 THEN
        RAISE EXCEPTION 'V0012 aborting: % resource(s) would orphan their room (no linked keeper left).', orphans;
    END IF;
    RAISE NOTICE 'V0012: unlinking % all-zero-booking duplicate KBS2 resources (0 orphans).', n;
END $$;

-- 3. Clear the links.
UPDATE resource
SET kbs2_to_kbs3_global_resource_id = NULL
WHERE id IN (SELECT resource_id FROM v0012_kbs2_link_backup);

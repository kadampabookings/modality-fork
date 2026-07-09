-- V0013: KBS2 drift cleanup, pass 3 — the AMBIGUOUS groups with a clear
-- dominant resource.
--
-- Follows V0011 (exactly-one-booked keeper) and V0012 (all-zero groups). This
-- handles drift groups (same event + same KBS3 global, >1 linked resource)
-- where MORE THAN ONE resource has bookings BUT one clearly dominates: every
-- non-top resource carries at most 2 bookings. It keeps the MOST-BOOKED
-- resource linked and clears the link on the rest.
--
-- Why most-booked (not newest, as in V0012): here the newest config is often a
-- stray one-off (e.g. "204"/Stays = Ensuite single 39 bookings + a 1-booking
-- Ensuite twin that happens to be newest). The registration grid shows the
-- newest config, so it mislabels the room; keeping the MOST-BOOKED resource
-- makes the card show the room's real type.
--
-- Side effect (accepted): unlinking a minor-booked resource (<= 2 bookings)
-- moves those bookings onto their own separate "(KBS2)" card — an honest split.
-- Genuinely dual-used groups (a non-top resource with > 2 bookings; 72 groups,
-- up to 98 bookings) are deliberately LEFT for manual review.
--
-- On staging: 235 groups. Safety / reversibility as V0011/V0012 — one
-- transaction, guards, snapshot in v0013_kbs2_link_backup. Undo:
--   update resource ru set kbs2_to_kbs3_global_resource_id = b.old_global_id
--   from v0013_kbs2_link_backup b where b.resource_id = ru.id;

-- 1. Snapshot the resources to unlink (everything but each group's most-booked keeper).
CREATE TABLE v0013_kbs2_link_backup AS
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
gb AS (SELECT dr.ev, dr.gid, dr.res, coalesce(bk.n_bk, 0) AS n_bk
       FROM dupres dr LEFT JOIN bk ON bk.res = dr.res),
grp_stat AS (   -- booked count and 2nd-highest booking per group
    SELECT ev, gid,
           sum((n_bk > 0)::int) AS booked,
           (array_agg(n_bk ORDER BY n_bk DESC))[2] AS second_bk
    FROM gb GROUP BY ev, gid
),
dominant AS (   -- >1 booked, and the largest non-top has <= 2 bookings
    SELECT ev, gid FROM grp_stat WHERE booked > 1 AND coalesce(second_bk, 0) <= 2
),
ranked AS (
    SELECT g.ev, g.gid, g.res,
           row_number() OVER (PARTITION BY g.ev, g.gid ORDER BY g.n_bk DESC, g.res DESC) AS rn
    FROM gb g JOIN dominant dm ON dm.ev = g.ev AND dm.gid = g.gid
)
SELECT r.id                              AS resource_id,
       r.kbs2_to_kbs3_global_resource_id AS old_global_id,
       s.event_id                        AS event_id
FROM ranked rk
JOIN resource r ON r.id = rk.res
JOIN site s     ON s.id = r.site_id
WHERE rk.rn > 1;   -- keep rn = 1 (most-booked), unlink the rest

-- 2. Guards.
DO $$
DECLARE
    n       integer;
    orphans integer;
BEGIN
    SELECT count(*) INTO n FROM v0013_kbs2_link_backup;
    IF n > 3000 THEN
        RAISE EXCEPTION 'V0013 aborting: unlink set unexpectedly large (%). Investigate before applying.', n;
    END IF;
    SELECT count(*) INTO orphans
    FROM v0013_kbs2_link_backup b
    WHERE NOT EXISTS (
        SELECT 1 FROM resource r2 JOIN site s2 ON s2.id = r2.site_id
        WHERE r2.kbs2_to_kbs3_global_resource_id = b.old_global_id
          AND s2.event_id = b.event_id
          AND r2.id <> b.resource_id
          AND r2.id NOT IN (SELECT resource_id FROM v0013_kbs2_link_backup)
    );
    IF orphans > 0 THEN
        RAISE EXCEPTION 'V0013 aborting: % resource(s) would orphan their room (no linked keeper left).', orphans;
    END IF;
    RAISE NOTICE 'V0013: unlinking % ambiguous-dominant duplicate KBS2 resources (0 orphans).', n;
END $$;

-- 3. Clear the links.
UPDATE resource
SET kbs2_to_kbs3_global_resource_id = NULL
WHERE id IN (SELECT resource_id FROM v0013_kbs2_link_backup);

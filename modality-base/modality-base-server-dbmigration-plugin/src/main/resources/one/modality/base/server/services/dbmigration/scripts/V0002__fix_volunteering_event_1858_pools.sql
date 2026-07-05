-- Pre-migration data fix (docs/pool-allocation-removal-plan.md, open item 6) — must run BEFORE
-- V0003 (same boot transaction, ascending order guarantees it).
--
-- Event 1858 ("Volunteering - kbs3", perpetual volunteer-housing event) has its room allocations
-- in the PUBLIC "Attendees" pool although they are volunteer housing (its bookings carry Volunteers
-- markers). Consequence on the old engine: it counts NO bookings against those rooms (room G9
-- reported 18 available with 13 beds occupied). Re-pooling them to Volunteers makes the V0003
-- migration emit correct reserved volunteer rooms (max_reserved = quantity, online = false).
--
-- Idempotent by construction: the UPDATE matches only public-pool rows of event 1858.
-- Diagnostic reports around this fix: aggregate repo scripts/fix-volunteering-event-1858-pools.sql.

UPDATE pool_allocation pa
   SET pool_id = (SELECT id FROM pool WHERE name = 'Volunteers')
  FROM pool p
 WHERE p.id = pa.pool_id
   AND pa.event_id = 1858
   AND p.allows_public
   AND (SELECT count(*) FROM pool WHERE name = 'Volunteers') = 1;  -- sanity: exactly one Volunteers pool

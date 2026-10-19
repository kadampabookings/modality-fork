-- Gives the back office's generic booking writes operations of their own, and grants each to every role that
-- opens a page making that write today, so that nobody loses anything when the rule starts enforcing.
--
-- WHY: the per-entity write rule now covers a booking's own tables (Document, DocumentLine, Attendance), judged
-- in the booking's organization and event. Each field the back office writes through the generic API is
-- granted to the act that writes it:
--
--   EditBookingDetails -- Document.person_* (the booking's personal details), request, comment, specialNeeds,
--                         and payer (also allowed to RunEducationProgramRegistration, which already exists)
--                         -- the registration screens
--   AllocateRooms      -- DocumentLine.resourceConfiguration -- rooming, rooms setup, event room setup
--   EditMediaAccess    -- Attendance.expirationDate          -- the registration video-expiry card
--
-- Every OTHER field of those tables needs ManageBookings, which is deliberately NOT seeded: without a row it is
-- held only through a super administrator's operation:*, like ManageMoneyAccounts. The back office confirms,
-- cancels and reprices through the document service, never through these tables generically, so nothing it
-- does today needs it. A future screen that does gets a code of its own then.
--
-- NO grant_route: these gate controls, not pages (V0106's precedent). read_only false: none is a read.
--
-- WHO, matched as RouteGrantMembership matches a route (equal, '*', or a trailing-'*' prefix), through either
-- grant shape (the operation, or an operation group it belongs to):
--   EditBookingDetails, EditMediaAccess -> roles reaching /registration
--   AllocateRooms                       -> roles reaching /registration, /rooms-setup or /event-room-setup
-- On staging (refreshed from production) that is "Registration" for all three, and "Room configurator" for
-- AllocateRooms.
--
-- The rule only OBSERVES these tables until they are added to ENFORCED_ENTITIES after a clean census.
--
-- Idempotent on the codes and on each grant. No anonymiser change: rows only, in tables classified not-pii.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT v.code, v.name, true, false, false, false, false
  FROM (VALUES
        ('EditBookingDetails', 'Edit a booking''s details and comments'),
        ('AllocateRooms',      'Allocate rooms to bookings'),
        ('EditMediaAccess',    'Change how long a booking''s recordings stay available')
       ) AS v(code, name)
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = v.code);

WITH role_routes AS (
    SELECT DISTINCT ro.role_id, o.grant_route
      FROM authorization_role_operation ro
      JOIN operation o
        ON o.id = ro.operation_id
        OR (ro.operation_group_id IS NOT NULL AND o.group_id = ro.operation_group_id)
     WHERE o.grant_route IS NOT NULL
),
reaches AS (
    SELECT DISTINCT rr.role_id, p.path
      FROM role_routes rr
      JOIN (VALUES ('/registration'), ('/rooms-setup'), ('/event-room-setup')) AS p(path)
        ON rr.grant_route = p.path
        OR rr.grant_route = '*'
        OR (right(rr.grant_route, 1) = '*'
            AND left(p.path, length(rr.grant_route) - 1) = left(rr.grant_route, length(rr.grant_route) - 1))
),
wanted (path, code) AS (
    VALUES ('/registration',     'EditBookingDetails'),
           ('/registration',     'EditMediaAccess'),
           ('/registration',     'AllocateRooms'),
           ('/rooms-setup',      'AllocateRooms'),
           ('/event-room-setup', 'AllocateRooms')
)
INSERT INTO authorization_role_operation (role_id, operation_id)
SELECT DISTINCT r.role_id, target.id
  FROM reaches r
  JOIN wanted w ON w.path = r.path
  JOIN operation target ON target.operation_code = w.code
 WHERE NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = r.role_id
                      AND existing.operation_id = target.id);

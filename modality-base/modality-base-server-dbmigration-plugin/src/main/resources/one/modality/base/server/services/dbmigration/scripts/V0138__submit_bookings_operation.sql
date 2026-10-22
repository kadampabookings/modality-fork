-- Gives creating, changing and cancelling bookings from the back office an operation of its own, SubmitBookings,
-- and grants it to every role that opens a back-office page submitting bookings today, so that nobody loses
-- anything when the check starts enforcing.
--
-- WHY: until now ANY account able to sign in to the back office (frontend_account.backoffice) could submit any
-- booking change, at any organization's event, through the document service. Its submit check fenced only the
-- front office; the back-office screens hid what a role did not grant, which is the browser deciding. The
-- document service now asks, for a verified back-office submit, whether the caller holds SubmitBookings in the
-- organization (and event) of every booking it names -- read from the database, never from the request.
-- It OBSERVES first (BackofficeSubmitScope.ENFORCING = false) and enforces after a clean census.
--
-- WHO, matched as RouteGrantMembership matches a route (equal, '*', or a trailing-'*' prefix), through either
-- grant shape (the operation, or an operation group it belongs to) -- the pages whose code submits bookings:
--   /registration        -- new booking, booking actions (confirm, cancel...), room moves, mate links
--   /education-programs  -- the registration run (creates and cancels students' bookings)
--   /volunteering        -- arrivals, room changes, KBS2 import / KBS3 export
-- A role whose route grant is scoped to an organization or an event keeps that scope: the grant is on the
-- role, and the role's access rows decide where it applies.
--
-- NO grant_route: this gates an action, not a page (V0106's precedent). read_only false: a read-only access row
-- must not carry it.
--
-- Idempotent on the code and on each grant. No anonymiser change: rows only, in tables classified not-pii.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT 'SubmitBookings', 'Create, change and cancel bookings from the back office', true, false, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'SubmitBookings');

WITH role_routes AS (
    SELECT DISTINCT ro.role_id, o.grant_route
      FROM authorization_role_operation ro
      JOIN operation o
        ON o.id = ro.operation_id
        OR (ro.operation_group_id IS NOT NULL AND o.group_id = ro.operation_group_id)
     WHERE o.grant_route IS NOT NULL
),
reaches AS (
    SELECT DISTINCT rr.role_id
      FROM role_routes rr
      JOIN (VALUES ('/registration'), ('/education-programs'), ('/volunteering')) AS p(path)
        ON rr.grant_route = p.path
        OR rr.grant_route = '*'
        OR (right(rr.grant_route, 1) = '*'
            AND left(p.path, length(rr.grant_route) - 1) = left(rr.grant_route, length(rr.grant_route) - 1))
)
INSERT INTO authorization_role_operation (role_id, operation_id)
SELECT DISTINCT r.role_id, target.id
  FROM reaches r
  JOIN operation target ON target.operation_code = 'SubmitBookings'
 WHERE NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = r.role_id
                      AND existing.operation_id = target.id);

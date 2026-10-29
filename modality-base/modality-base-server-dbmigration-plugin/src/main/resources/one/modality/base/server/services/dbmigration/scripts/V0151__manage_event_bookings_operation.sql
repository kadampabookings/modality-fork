-- V0151: ManageEventBookings -- putting an event's bookings on hold, closing them and reopening them from the back
-- office's Registration › Event status page -- and its grant to every role that reaches /registration.
--
-- WHY. Those three moves write event.state, which decides whether guests can book and pay (OPEN switches payments to
-- live; ON_HOLD also blocks online balance payments). Until now the only fence on that column was the client: event
-- writes travel the generic ChangeSet path, which checks no operation for Event (finding R5,
-- docs/design/event-states-review.md in the aggregate repo). The Registration page now makes them through a server
-- endpoint instead -- modality/service/event/setBookingState (SetEventBookingStateEndpoint,
-- modality-event-server-lifecycle-plugin) -- which asks the database whether the caller holds THIS operation in the
-- event's own organisation and for the event itself (RouteAccessGuard.whenCallerHoldsOperation), both read from the
-- event row, then makes one compare-and-set UPDATE. V0147's trigger still judges the move below it.
--
-- R5 IS NARROWED, NOT CLOSED. The generic path stays open to every other writer of event.state -- the event form's
-- Published toggle, Recurring Events, the Event lifecycle page, Finance › Reconcile events, the legacy Java back
-- office -- still fenced by their route grants only. Closing it needs Event.state in the protected writes (R5's own
-- recommendation), which is a separate change.
--
-- WHO, matched as RouteGrantMembership matches a route (equal, '*', or a trailing-'*' prefix), through either grant
-- shape (the operation, or an operation group it belongs to) -- V0138's role_routes / reaches query, for one path:
--   /registration   -- the page lives at /registration-event-status, under Registration's guard
--                      (requireRoute('/registration')); its bookings buttons need this operation.
-- So nobody who opens Registration today loses the buttons the page gives them. A role whose route grant is scoped to
-- an organisation, an event or an event type keeps that scope: the grant is on the role, and the role's access rows
-- decide where it applies -- and the endpoint asks for it on the event being moved.
--
-- NO grant_route: it gates three buttons, not a page (V0106's precedent). read_only = false: a read-only access row
-- must not carry it (RoleOperationMembership's read-only fence), so a read-only Registration user sees the page and no
-- buttons. Super administrators need no row: they pass every RouteAccessGuard check.
--
-- Idempotent on the code and on each grant: re-running adds nothing.
--
-- ANONYMISER: no change. This adds rows only, to `operation` and `authorization_role_operation`, both classified
-- whole-table not-pii ('reference') in scripts/gdpr-anonymise/10-anon-helpers.sql (re-read 2026-10-29, not assumed);
-- no table or column is added, renamed or dropped, so the '*' dispositions still hold for what is there.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT 'ManageEventBookings', 'Put on hold, close and reopen an event''s bookings', true, false, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'ManageEventBookings');

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
      JOIN (VALUES ('/registration')) AS p(path)
        ON rr.grant_route = p.path
        OR rr.grant_route = '*'
        OR (right(rr.grant_route, 1) = '*'
            AND left(p.path, length(rr.grant_route) - 1) = left(rr.grant_route, length(rr.grant_route) - 1))
)
INSERT INTO authorization_role_operation (role_id, operation_id)
SELECT DISTINCT r.role_id, target.id
  FROM reaches r
  JOIN operation target ON target.operation_code = 'ManageEventBookings'
 WHERE NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = r.role_id
                      AND existing.operation_id = target.id);

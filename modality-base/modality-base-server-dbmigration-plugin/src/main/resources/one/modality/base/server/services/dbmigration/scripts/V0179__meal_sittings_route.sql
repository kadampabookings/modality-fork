-- V0179: RouteToMealSittings — opens the back office's Event Setup › Meal sittings page (/meal-sittings).
--
-- The page declares an event's meal sittings: a resource per sitting at the venue and an event
-- resource_configuration per sitting (V0178 keeps those to the event's own bookings). It writes, so the
-- operation is not read_only. Its own operation rather than an existing one (V0108's reasoning for
-- RouteToBranches): reusing RouteToEventSetup or RouteToRooms would hand the page to every role holding
-- those today. Granted to nobody by this script; until a role ticks it in the role editor, only super
-- administrators (route:*) open the page.
--
-- No table or column is added: scripts/gdpr-anonymise and scripts/gdpr-erasure need no change.
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

INSERT INTO operation (operation_code, name, grant_route, read_only, backend, frontend, public, guest)
SELECT 'RouteToMealSittings', 'Meal sittings', '/meal-sittings', false, true, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'RouteToMealSittings');

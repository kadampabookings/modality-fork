-- Give the Branches page an operation of its own.
--
-- Its tile named RouteToEventSetup, which grants '/event-setup', while the router guards
-- '/branches'. Declared intent and enforcement disagreed, and enforcement won: only a route:*
-- super-administrator could open the page, and everyone holding RouteToEventSetup saw a tile that
-- refused them.
--
-- There were two ways to settle that, and they are not equivalent. Widening the guard to accept
-- '/event-setup' would have honoured the tile -- and handed Branches to "Room configurator", the
-- role that holds RouteToEventSetup, as a side effect of a report tidy-up. Access is not something
-- to grant by implication, so the page gets its own operation instead: nobody holds it, nobody
-- gains anything today, and delegating it later is a deliberate tick in the role editor rather
-- than a consequence of holding something broader.
--
-- grant_route is the exact path the router checks, with no trailing wildcard -- a grant should
-- open the page it names and nothing else. public and guest are false: the authorization service
-- hands those to every caller, anonymous ones included, so a public route operation here would
-- publish the page.
INSERT INTO operation (operation_code, name, grant_route, backend, frontend, public, guest)
SELECT 'RouteToBranches', 'Branches', '/branches', true, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'RouteToBranches');

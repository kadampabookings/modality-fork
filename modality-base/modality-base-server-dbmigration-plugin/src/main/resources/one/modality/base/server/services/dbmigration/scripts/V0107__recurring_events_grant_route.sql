-- Give RouteToRecurringEvents the route it is supposed to grant.
--
-- The row exists, is back-office, and appears in the role editor like any other -- but its
-- grant_route is empty, so granting it hands out nothing. The authorization service builds its
-- push from that column:
--
--     grant operation:RouteToRecurringEvents      <- emitted
--     grant route:...                             <- skipped, Strings.isEmpty(grantRoute)
--
-- A role given "Recurring Events" therefore gets the dashboard tile (tile visibility is an
-- operation check) and a locked door behind it (the router guard is a route check). The
-- administrator sees the grant they made, the user sees the tile, and nobody sees why the page
-- refuses to open.
--
-- Nobody holds the operation today, so this widens nothing: it makes a grant that has always been
-- inert do what its name says. Found by the back office's own catalogue health report, which
-- compares each page's requireRoute() path against the grant_route of the operations that are
-- supposed to open it -- this was one of four pages it flagged, and the only one whose cause was
-- an empty column rather than a mismatched path.
--
-- Guarded on the current value so it cannot overwrite a route somebody has since set by hand.
UPDATE operation
   SET grant_route = '/recurring-events'
 WHERE operation_code = 'RouteToRecurringEvents'
   AND coalesce(grant_route, '') = '';

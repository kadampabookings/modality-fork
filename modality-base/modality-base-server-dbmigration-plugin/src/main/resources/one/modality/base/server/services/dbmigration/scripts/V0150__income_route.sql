-- Give RouteToIncome the route of the React Finance › Income page.
--
-- RouteToIncome (V0014, id 16) still grants KBS2's '/income/event/*' (the Java Income activity, one
-- event at a time). The React page is organisation-wide at '/income', so a role given "Income" would
-- get the dashboard tile (an operation check) and a locked door behind it (the router guard is a
-- route check) -- the gap the back office's catalogue health report flags once the page is built.
-- '/income*' covers the new page and the old path alike.
--
-- read_only stays true (it already is on staging, 2026-10-29): the page only reads -- aggregates of
-- money_transfer, document and document_line -- and writes nothing, V0131's rule for a read-only route.
--
-- Widens nothing today: no role holds the operation, directly or through an operation group (staging,
-- 2026-10-29). Guarded on the current value so it cannot overwrite a route somebody has since set by hand.
--
-- ANONYMISER: no table or column is added, renamed or dropped -- scripts/gdpr-anonymise is unchanged.
UPDATE operation
   SET grant_route = '/income*',
       read_only = true
 WHERE operation_code = 'RouteToIncome'
   AND coalesce(grant_route, '') IN ('', '/income/event/*');

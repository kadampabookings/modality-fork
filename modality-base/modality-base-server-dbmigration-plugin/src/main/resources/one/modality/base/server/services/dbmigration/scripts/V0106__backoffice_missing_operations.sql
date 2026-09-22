-- Give nine of the ten operations the React back office already checks a row to be granted from.
--
-- The back office checks these codes; the table has never had them. An operation with no row can
-- be held by nobody, so each of these features has only ever worked for super-administrators,
-- whose `grant operation:*` and `grant route:*` cover codes that do not exist. That fails closed,
-- which is the safe direction, but it fails SILENTLY: nothing anywhere said these pages were
-- undelegable, and three of them were worked around in the client instead --
--
--     const canEditContent = hasPermission('EditLetterContent') || true;
--
-- -- which turns the gate off for everyone rather than leaving it shut. Those `|| true` fallbacks
-- go in the same change as this script, which is why the grants below matter.
--
-- ## Adding a row grants nothing
--
-- A grant comes from `authorization_role_operation`, not from the existence of a row, so inserting
-- these is not a widening: /policies, /labels, /residents and /tester stay exactly as reachable as
-- they are today (super-administrators only) until somebody deliberately ticks them in a role.
-- `public` and `guest` are false on every row for the same reason -- the authorization service
-- grants those automatically to every caller, including anonymous ones, and a `public` route
-- operation here would hand the page to the internet.
--
-- ## The grants below preserve today's behaviour, they do not extend it
--
-- The letter and pass-template edits are different from the route operations, because the
-- `|| true` fallbacks mean that TODAY anyone who can open the page can edit what is on it.
-- Removing the fallback without seeding would quietly take that away from real users -- the
-- Registration role among them. So each role that can already open the page gets the matching
-- action:
--
--   * a role holding RouteToLetters or RouteToEventLetters gets the two letter edits;
--   * a role holding RouteToRegistration gets EditPassTemplate (the pass designer lives at
--     /registration-passes/templates, behind the /registration guard).
--
-- That reproduces the current effective permissions exactly. It is a starting point, not a
-- judgement: an administrator can now narrow any of it, which was not previously possible.
--
-- ## RouteToVolunteeringSchedule is deliberately NOT created
--
-- It is the tenth code the back office checks with no row, and the obvious thing to do is add it
-- with grant_route '/volunteering', since it opens the same page. It is left out because the
-- narrowing it promises is not real yet: VolunteeringPage derives `isScheduleOnly` from it and
-- then filters only the visible TABS, while useApplications() runs unconditionally. A holder
-- would see one tab and still receive every volunteer application -- names and contact details --
-- in their browser.
--
-- Nobody can hold it today precisely because the row is missing, so the gap is currently
-- theoretical. Creating the row would make it real the first time an administrator granted what
-- reads like a schedule-only role. The right order is to gate the page's queries first, then add
-- the row. Until then the capability catalogue reports the code as "not grantable", which is both
-- true and a standing reminder.
--
-- ViewAsCustomer and ViewAsBackOfficeUser are deliberately NOT seeded to anyone. They let the
-- holder see the booking system as another person, which is a genuine privilege escalation and
-- exactly the kind of thing that should be granted deliberately and individually. Today only
-- super-administrators hold them (via the operation wildcard), and this script leaves that alone.

-- 1. The three in-page actions. No grant_route: they gate a control, not a page.
INSERT INTO operation (operation_code, name, backend, frontend, public, guest)
SELECT v.code, v.name, true, false, false, false
  FROM (VALUES
        ('EditLetterContent',    'Edit letter content'),
        ('EditLetterProperties', 'Edit letter properties'),
        ('EditPassTemplate',     'Edit pass template')
       ) AS v(code, name)
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = v.code);

-- 2. The two impersonation operations. Also no grant_route -- they unlock a control on the
--    Customers page, which RouteToCustomers already opens.
INSERT INTO operation (operation_code, name, backend, frontend, public, guest)
SELECT v.code, v.name, true, false, false, false
  FROM (VALUES
        ('ViewAsCustomer',       'View the site as a customer'),
        ('ViewAsBackOfficeUser', 'View the back office as another user')
       ) AS v(code, name)
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = v.code);

-- 3. The four route operations. Each grant_route is the exact path the router's requireRoute()
--    guard checks, with no trailing wildcard: a grant should open the page it names and nothing
--    else. /policies covers both policy pages -- the Global Setup one and the Event Setup one are
--    both guarded by requireRoute('/policies').
INSERT INTO operation (operation_code, name, grant_route, backend, frontend, public, guest)
SELECT v.code, v.name, v.route, true, false, false, false
  FROM (VALUES
        ('RouteToPolicies',    'Policies',     '/policies'),
        ('RouteToLabelEditor', 'Label editor', '/labels'),
        ('RouteToResidents',   'Residents',    '/residents'),
        ('RouteToTester',      'Tester',       '/tester')
       ) AS v(code, name, route)
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = v.code);

-- 4. Keep today's letter editing working: every role that can already open a letters page.
--
--    `role_holds` follows both shapes a grant can take -- the operation directly, or an
--    operation group the operation belongs to. Missing the group shape would have left a
--    role like "Catering Manager", which holds the "Kitchen" group rather than
--    RouteToKitchen itself, unseeded and silently stripped of editing the moment the
--    `|| true` fallbacks came out. Written as a CTE rather than a temporary table so the
--    script needs no TEMP privilege and makes no assumption about transaction scope.
WITH role_holds AS (
    SELECT DISTINCT ro.role_id, o.operation_code
      FROM authorization_role_operation ro
      JOIN operation o
        ON o.id = ro.operation_id
        OR (ro.operation_group_id IS NOT NULL AND o.group_id = ro.operation_group_id)
)
INSERT INTO authorization_role_operation (role_id, operation_id)
SELECT DISTINCT holder.role_id, target.id
  FROM role_holds holder
  JOIN operation target ON target.operation_code IN ('EditLetterContent', 'EditLetterProperties')
 WHERE holder.operation_code IN ('RouteToLetters', 'RouteToEventLetters')
   AND NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = holder.role_id
                      AND existing.operation_id = target.id);

-- 5. Same for the pass designer, which lives behind the /registration guard.
WITH role_holds AS (
    SELECT DISTINCT ro.role_id, o.operation_code
      FROM authorization_role_operation ro
      JOIN operation o
        ON o.id = ro.operation_id
        OR (ro.operation_group_id IS NOT NULL AND o.group_id = ro.operation_group_id)
)
INSERT INTO authorization_role_operation (role_id, operation_id)
SELECT DISTINCT holder.role_id, target.id
  FROM role_holds holder
  JOIN operation target ON target.operation_code = 'EditPassTemplate'
 WHERE holder.operation_code = 'RouteToRegistration'
   AND NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = holder.role_id
                      AND existing.operation_id = target.id);

-- Seeds the route operation for a per-organisation page, so that /organizations -- a directory of
-- every centre, with a pen on each row -- can go back to being a super administrator's page.
--
-- WHY A SECOND PAGE: V0130 split writing an organisation into three rights so a centre's own people
-- could be given one without being made super administrators. It left them nowhere to use it:
-- /organizations is the only page that writes an organisation, and it lists all nine, editable. Its
-- route (RouteToOrganizations) is granted to the Techi role, so a centre's technical person reads
-- every other centre's row -- name, type, country, closed, and each one's email and phone -- to set
-- their own centre's video credentials. /my-organization edits the one selected in the top bar, and
-- is what the three rights are granted alongside. /organizations keeps its rows and its pens for
-- super administrators, since editing ANOTHER centre stays their job.
--
-- WHY THIS ONE CARRIES A grant_route AND V0130'S THREE DO NOT: those gate controls inside a page
-- (which fields may be saved); this gates a page. Same distinction as V0106 and V0128.
--
-- WHY `/my-organization` AND NOT `/organization`: route grants are matched by PREFIX
-- (`matchesRoutePattern`: `pattern.endsWith('*')` -> `path.startsWith(pattern minus the star)`), so
-- a grant route of `/organization*` would also match `/organizations` and hand the holder the whole
-- directory -- the very thing this page exists to stop, and invisible in testing, because they would
-- see a page that works. `/my-organization` shares no prefix with `/organizations`, so the hazard is
-- gone rather than guarded against. The route is exact, with no wildcard, for the same reason.
--
-- READ-ONLY, deliberately, and this is the first seed to say so rather than leaving it to be marked
-- by hand through /operations. The rule we settled on 2026-10-18: a route may be marked read_only
-- only when every write on the page it opens has an operation of its own, since a read-only access
-- receives read-only operations and nothing else. On this page both saves do -- Details is
-- EditOrganizationDetails, Media is EditOrganizationMedia -- and the residents event is not written
-- here at all, only displayed with a link to /residents. So opening the page grants nothing but
-- looking, and a read-only access may be given it safely. (Contrast RouteToEducationPrograms, left
-- read_only = false by decision the same day: that page imports people and books them onto events
-- through writes with no operation of their own.)
--
-- GRANTED TO NOBODY, as in V0130, and for one more reason here: the page does not exist yet. A grant
-- of a route nothing serves grants nothing, so there is nothing to gain by guessing a holder now.
-- Seeding first means the operation is there to be granted the moment the page lands, and that the
-- grant can be prepared and reviewed before it does.
--
-- SCOPED: requireRoute passes the selected organisation into the route check, so a grant made
-- through an access row for one centre opens the page for that centre and no other. A route grant
-- asked without a context would match only rules declaring none -- which is what made the
-- organisation write rule useless before the context fix -- so this is worth having checked rather
-- than assumed. It is checked: kbs3-react/backoffice/src/routes/router.tsx, requireRoute.
--
-- Idempotent on the code, like every operation seed since V0106.
--
-- No anonymiser change: `operation` is classified ('operation','*','not-pii','reference') and this
-- adds no column -- re-read rather than assumed, since a '*' row silently admits new ones.

INSERT INTO operation (operation_code, name, grant_route, read_only, backend, frontend, public, guest)
SELECT v.code, v.name, v.route, true, true, false, false, false
  FROM (VALUES
        ('RouteToMyOrganization', 'Open the organisation''s own page', '/my-organization')
       ) AS v(code, name, route)
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = v.code);

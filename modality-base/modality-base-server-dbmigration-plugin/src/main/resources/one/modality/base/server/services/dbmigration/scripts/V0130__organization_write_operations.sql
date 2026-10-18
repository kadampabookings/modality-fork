-- Seeds the three operations that split writing an organisation, so a centre's own people can be
-- given one of them without being made super administrators.
--
-- WHY THREE AND NOT ONE: `ManageOrganizations` covers the whole entity in the write rule and has
-- never had a row, so it is held only through a super administrator's `operation:*`. That is the
-- problem rather than an oversight waiting to be fixed: a centre's technical person who sets its
-- video credentials would have to be made a super administrator, which hands them every other
-- organisation's data as well. The fields are written by three different kinds of person, so they
-- become three operations:
--
--   EditOrganizationDetails        name, type, country, closed, email, phone, street, cityName,
--                                 postCode -- the centre's own description
--   EditOrganizationMedia          bunnyApiKey, audioBunnyPullZoneId, audioBunnyStorageZoneId --
--                                 the media hosting credentials
--   EditOrganizationResidentsEvent residentsEvent -- the event carrying the centre's residents
--
-- They are enforced per FIELD (ProtectedEntityWritesJob.REQUIRED_OPERATIONS_BY_FIELD), the way
-- `Letter` already splits content from properties, and the entity rule stays as the any-of backstop
-- for a statement whose assignments cannot be read field by field.
--
-- WHY THE SPLIT WORKS WITHOUT A CLIENT CHANGE TO MATCH: the organisations dialog already sends the
-- general fields and the Bunny fields as two separate statements (the latter through the untyped
-- path, the typed interface not having been regenerated), and the residents pointer is written from
-- another page entirely. So each group is judged on its own.
--
-- SCOPED, which is the whole point: since the context fix an Organization write is judged in its own
-- organisation's context, so a grant made through an access row for one centre authorises writes to
-- THAT centre's row and no other. Before that fix this seeding would have been pointless -- the
-- holder would have been refused everywhere, their grant being organisation-scoped and the check
-- blind to it.
--
-- NO grant_route, following V0106's in-page actions and V0128: these gate controls, not pages.
-- /organizations is opened by RouteToOrganizations (a SUPERADMIN_TILES entry) and /residents by
-- RouteToResidents; neither is granted by any role today, so opening the pages remains its own
-- question.
--
-- GRANTED TO NOBODY by this script, and unlike V0106 and V0129 there is nothing to derive it from.
-- Those copied a new code onto the roles already holding the route that opens the page, reproducing
-- what their holders could already do. Here no role holds either route, so a derived grant would
-- grant nothing, and a chosen one would hand somebody a capability that is today reserved. Seeding
-- makes delegation possible; who gets it is decided when somebody needs it.
--
-- `ManageOrganizations` is NOT seeded: it is retired from the write rule in the same change rather
-- than given a row, since these three replace it. `ManageMoneyAccounts` is not seeded either --
-- `money_account` is denied to clients outright (ClientWriteSecrets), so the code would grant the
-- ability to attempt something no client can.
--
-- BUNNY CREDENTIALS, said plainly: `bunny_api_key` is a secret a browser can write, and it is on no
-- deny list. EditOrganizationMedia makes writing it a named, grantable act rather than a side effect
-- of managing a centre -- an improvement, not a closure. Closing it means moving those three fields
-- behind a server endpoint and denying the column, which is a separate change.
--
-- Idempotent on the code, like every operation seed since V0106.
--
-- No anonymiser change: `operation` is classified ('operation','*','not-pii','reference') and this
-- adds no column -- re-read rather than assumed, since a '*' row silently admits new ones.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest)
SELECT v.code, v.name, true, false, false, false
  FROM (VALUES
        ('EditOrganizationDetails',        'Edit organisation details'),
        ('EditOrganizationMedia',          'Edit organisation media credentials'),
        ('EditOrganizationResidentsEvent', 'Edit the organisation''s residents event')
       ) AS v(code, name)
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = v.code);

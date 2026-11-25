-- Grants SendDocumentLetter (V0128) to every role that can open the registration pages, so the React
-- /registration-bookings page can send a booking's letters -- the "Send a letter" action and the
-- confirmation letter offered when confirming, ported from KBS2's Bookings screen.
--
-- WHY: the page sends letters through SendDocumentLetterEndpoint, which asks for this operation scoped
-- to the booking's organization (and event, where the access row has one). V0129 granted it only to
-- the roles reaching /education-programs, so without this only super-administrators -- who pass every
-- operation through the `operation:*` wildcard -- could send letters from the registration page.
--
-- WHO: every role holding BOTH RouteToRegistration (grant_route '/registration*'), the operation that
-- opens /registration-bookings, AND SubmitBookings (V0138), the right to act on those bookings. It
-- reproduces what registration staff can do in KBS2 today, where "Send a letter..." sits in the
-- Bookings screen's context menu. On staging (refreshed from production) that is 1 role, "Registration".
--
-- Why both, and not the route alone as V0129 did: the route is also held by a read-only role (on staging,
-- "Data Protection Officer", which can open the page but not change a booking). Sending a member a letter
-- is an act towards them, not a read; a role trusted only to look must not gain it as a side effect.
-- The script matches by grant rather than by id so it is right on whatever production holds.
--
-- WHAT THE SCOPE IS: a role's grant reaches only as far as the access row that assigns the role -- its
-- organization, and its event if it names one. A holder therefore sends letters for that organization's
-- bookings only, the way they can only open that organization's registrations.
--
-- `role_holds` follows both shapes a grant can take -- the operation directly, or an operation group it
-- belongs to -- for the reason V0106 records: missing the group shape strips the roles that hold a group
-- rather than the operation itself.
--
-- Only role grants are followed, as in V0129: a route operation assigned to a person directly through
-- authorization_assignment.operation_id would need the action granted by hand.
--
-- Idempotent: a role that already holds SendDocumentLetter is skipped. If V0128 has not run, the join to
-- the operation finds nothing and this inserts nothing.
--
-- No anonymiser or erasure-manifest change: authorization_role_operation is classified ('*', 'not-pii',
-- 'reference') and this adds rows, not columns.

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
  JOIN role_holds submitter ON submitter.role_id = holder.role_id AND submitter.operation_code = 'SubmitBookings'
  JOIN operation target ON target.operation_code = 'SendDocumentLetter'
 WHERE holder.operation_code = 'RouteToRegistration'
   AND NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = holder.role_id
                      AND existing.operation_id = target.id);

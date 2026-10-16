-- Grants SendDocumentLetter (V0128) to every role that can already open the page that sends letters, so
-- moving the send onto its own endpoint takes the ability away from nobody.
--
-- WHY NOW: the client half shipped to production on 2026-10-16. Since then the education-programs
-- screens send a booking's letter through SendDocumentLetterEndpoint instead of writing
-- document.trigger_send_letter_id, and that endpoint asks for this operation, scoped to the booking's
-- organization (and event, where the access row has one). V0128 granted it to nobody, so until this
-- runs only super-administrators -- who pass every operation through the `operation:*` wildcard --
-- can send those letters. Everybody else gets a refusal where the payment-request letter used to go.
--
-- WHO: /education-programs is the only page whose client calls the endpoint, and RouteToEducationPrograms
-- (grant_route '/education-programs') is what opens it. So each role holding that route operation
-- gets the action, exactly as V0106 seeded EditLetterContent from the letters routes: it reproduces
-- what those users could already do, it does not extend it. On staging (refreshed from production)
-- that is one role, "Education Programs", held by one person in one organization; the script matches
-- by grant rather than by id so it is right on whatever production holds.
--
-- WHAT THE SCOPE IS: a role's grant reaches only as far as the access row that assigns the role --
-- its organization, and its event if it names one. A holder therefore sends letters for that
-- organization's bookings only, the way they could only open that organization's programs.
--
-- `role_holds` follows both shapes a grant can take -- the operation directly, or an operation group
-- it belongs to -- for the reason V0106 records: missing the group shape strips the roles that hold
-- a group rather than the operation itself.
--
-- Only role grants are followed. A route operation can also be assigned to a person directly through
-- authorization_assignment.operation_id; on staging none is assigned to RouteToEducationPrograms, and
-- such a holder would keep needing the action granted by hand.
--
-- Idempotent: a role that already holds SendDocumentLetter is skipped. If V0128 has not run, the join
-- to the operation finds nothing and this inserts nothing.
--
-- No anonymiser change: authorization_role_operation is classified ('*', 'not-pii', 'reference') and
-- this adds rows, not columns.

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
  JOIN operation target ON target.operation_code = 'SendDocumentLetter'
 WHERE holder.operation_code = 'RouteToEducationPrograms'
   AND NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = holder.role_id
                      AND existing.operation_id = target.id);

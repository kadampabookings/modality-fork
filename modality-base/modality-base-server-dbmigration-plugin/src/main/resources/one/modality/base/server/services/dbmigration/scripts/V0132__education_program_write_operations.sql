-- Gives the Education Programs page's write actions operations of their own, and marks its route
-- read-only now that they have them.
--
-- WHY NOW: the read-only grant filter went to production with RouteToEducationPrograms deliberately
-- left read_only = false, because the page does far more than open: it creates programmes, adds and
-- imports registrants, converts them into bookings, and cancels the live bookings of people who are
-- not registrants. Marking the route would have handed every one of those to a read-only access.
-- The cost of not marking it was paid by one access (role 'Education Programs' at NKT-IKBU), which
-- lost the page entirely when the filter deployed: a read-only access cannot hold a route that is
-- not read-only. This script is how it gets the page back — read-only, as intended.
--
-- THREE OPERATIONS, split by what a person is trusted to do rather than by which table is touched:
--
--   EditEducationProgram              create, rename, re-point at another event, delete a programme
--   ManageEducationProgramRegistrants add, import and remove registrants; their comments; the payer
--   RunEducationProgramRegistration   the run: books the registrants onto the event, and cancels the
--                                     live bookings of people who are not registrants
--
-- The third is separate from the second because it is a different kind of trust: maintaining a list
-- of names is reversible bookkeeping, while a run creates and cancels other people's bookings. A
-- centre may well want someone to keep the list and somebody else to press the button.
--
-- Letters already have theirs: SendDocumentLetter (V0128/V0129) covers the payment requests this page
-- sends, including the ones a run sends as it goes.
--
-- WHAT THIS DOES NOT DO, said plainly: it does not make the server refuse these writes. The per-entity
-- rule cannot express them. They land on `List`, `ListItem` and `Document` -- and `List`/`ListItem`
-- carry the customers and registration features' saved lists as well, while `Document` is every
-- booking in the system, so protecting those entities would demand an education-programs right for
-- writes that have nothing to do with education programmes. The row-level discriminator exists
-- (`List.education_program`, V0109, with a CHECK keeping the list kinds apart) but the rule engine
-- judges a statement by its entity and its assigned fields, not by the other columns of the row it
-- lands on. So these three are client gates until the client declares the operation it is performing
-- and the server verifies that claim against the statement -- which is exactly the case the declared
-- intent half of the write model was designed for. Until then the honest description is: the page's
-- actions are named, grantable and withheld in the UI, and an operator who bypasses the UI is not
-- stopped by this.
--
-- GRANTED TO WHOEVER ALREADY OPENS THE PAGE, following V0106 and V0129: each new code goes to the
-- roles that already hold RouteToEducationPrograms, which reproduces exactly what their holders can
-- do today and so costs nobody anything. Read-only accesses of those same roles do NOT receive them,
-- because the three are not marked read_only and the filter fences exactly that -- which is the
-- mechanism that gives the read-only access a readable page instead of none.
--
-- THE ROUTE'S MARK is flipped here rather than by hand through /operations, because it is only safe
-- once the three codes above exist and the client gates ship with them. The same deploy carries both.
--
-- Idempotent throughout: seeds skip an existing code, grants skip an existing pair, and the mark is
-- a plain UPDATE.
--
-- No anonymiser change: `operation` is classified ('operation','*','not-pii','reference') and
-- `authorization_role_operation` ('*','not-pii','reference'); this adds rows, not columns. Re-read
-- rather than assumed, since a '*' row silently admits new ones.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest)
SELECT v.code, v.name, true, false, false, false
  FROM (VALUES
        ('EditEducationProgram',              'Edit an education programme'),
        ('ManageEducationProgramRegistrants', 'Manage education programme registrants'),
        ('RunEducationProgramRegistration',   'Run an education programme registration')
       ) AS v(code, name)
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = v.code);

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
  JOIN operation target
    ON target.operation_code IN ('EditEducationProgram',
                                 'ManageEducationProgramRegistrants',
                                 'RunEducationProgramRegistration')
 WHERE holder.operation_code = 'RouteToEducationPrograms'
   AND NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = holder.role_id
                      AND existing.operation_id = target.id);

UPDATE operation
   SET read_only = true
 WHERE operation_code = 'RouteToEducationPrograms'
   AND NOT read_only;

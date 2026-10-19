-- Gives the customers screen's three writes operations of their own, and grants each to every role that can
-- open that screen today, so that nobody loses anything on the day.
--
-- WHY: until now the right to change people WAS the right to open /customers. The endpoints behind the screen
-- (UpdateCustomer, MergeIntoAccount, MergeDuplicatePersons, RemoveCustomers) checked the /customers route grant
-- on the server — that was itself an improvement on the client writes they replaced — but it meant that
-- whoever could look people up could also edit them, merge them and remove them. Merging and removing are
-- destructive and not undoable, and an organization may reasonably want somebody to search the list without
-- being able to delete anyone. Each act now has its code, as the impersonation control on the same screen
-- already did (ViewAsCustomer, V0106):
--
--   EditCustomers    -- UpdateCustomerEndpoint            (asked anywhere: a person has no owning organization)
--   MergeCustomers   -- MergeIntoAccountEndpoint (anywhere)
--                       MergeDuplicatePersonsEndpoint     (scoped to each affected booking's organization/event)
--   RemoveCustomers  -- RemoveCustomersEndpoint           (anywhere)
--
-- NO grant_route: they gate controls, not a page (V0106's precedent). RouteToCustomers still opens the screen.
-- read_only false: none of them is a read, so a read-only access row does not carry them.
--
-- WHO: every role that reaches /customers, matched exactly as RouteGrantMembership matches it — the operation's
-- grant_route equal to the path, '*', or a trailing-'*' prefix of it — and through either grant shape, the
-- operation directly or an operation group it belongs to (V0106's role_holds). That reproduces today's write
-- rights exactly; narrowing them is now possible in the role editor, which it was not. On staging (refreshed
-- from production) that is one role, "Customers list manager", held by one person.
--
-- Idempotent on the codes and on each grant, like every seed since V0106.
--
-- No anonymiser change: operation and authorization_role_operation are classified ('*', 'not-pii') and this
-- adds rows, not columns.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT v.code, v.name, true, false, false, false, false
  FROM (VALUES
        ('EditCustomers',   'Edit a customer''s details'),
        ('MergeCustomers',  'Merge customers or accounts'),
        ('RemoveCustomers', 'Remove customers')
       ) AS v(code, name)
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = v.code);

WITH reaches_customers AS (
    SELECT DISTINCT ro.role_id
      FROM authorization_role_operation ro
      JOIN operation o
        ON o.id = ro.operation_id
        OR (ro.operation_group_id IS NOT NULL AND o.group_id = ro.operation_group_id)
     WHERE o.grant_route IS NOT NULL
       AND (o.grant_route = '/customers'
            OR o.grant_route = '*'
            OR (right(o.grant_route, 1) = '*'
                AND left('/customers', length(o.grant_route) - 1) = left(o.grant_route, length(o.grant_route) - 1)))
)
INSERT INTO authorization_role_operation (role_id, operation_id)
SELECT DISTINCT holder.role_id, target.id
  FROM reaches_customers holder
  JOIN operation target ON target.operation_code IN ('EditCustomers', 'MergeCustomers', 'RemoveCustomers')
 WHERE NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = holder.role_id
                      AND existing.operation_id = target.id);

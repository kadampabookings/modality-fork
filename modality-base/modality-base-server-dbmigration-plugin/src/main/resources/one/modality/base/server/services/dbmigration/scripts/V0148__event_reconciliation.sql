-- V0148: what Finance › Reconcile events needs from the database -- its two operations, the global
-- "Unreconciled booking" item that brings a booking's balance to zero, and a split payment's verified tick
-- passed to its children without re-writing them.
--
-- WHY. Reconciling an event means every booking's balance at zero and every payment matched to the payment
-- provider's or the bank's statement (Bruno and David, 2026-10-28). The accountant zeroes what will never be
-- collected or refunded with a line of an "Unreconciled" item carrying a custom price -- negative writes off what
-- a booking owes, positive keeps what it overpaid -- so the accounts land in accounting account 11 "Unreconciled"
-- (pertes et profits). Payments are verified elsewhere -- KBS2's Payments and Statements screens today, KBS3's
-- Finance pages next, under an operation of their own (Bruno and David, 2026-10-28) -- and the page checks that
-- every one is `verified` before the event can be marked reconciled.
--
-- 1. OPERATIONS, granted to nobody (V0131 and V0138's templates; idempotent on the code).
--    - RouteToEventReconciliation opens /event-reconciliation and /event-reconciliation/<event>. Its grant route
--      `/event-reconciliation*` shares its prefix with no other route (the prefix hazard V0131 describes).
--      read_only = true: every write the pages make has an operation of its own (ReconcileEvents, SubmitBookings),
--      so opening them grants nothing but looking -- V0131's rule of 2026-10-18.
--    - ReconcileEvents gates the page's actions: closing bookings, zeroing balances, marking the event reconciled.
--      Verifying payments is not one of them. No grant route (an action, not a page); read_only = false.
--
-- 2. ITEMS. Family 18 (`unrec`, "Unreconciled", accounting account 11) holds three items: organisation 1's
--    "Unreconciled booking" (105) and "Exchange discrepency" (496, code EXCHD), and organisation 287's own
--    "Unreconciled booking" (1095), never booked. David, 2026-10-28: keep the first two and make them GLOBAL
--    (organization_id NULL), the code using "Unreconciled booking"; delete the duplicate.
--    - 105 gets the code `unreconciled` (KnownItem.UNRECONCILED_BOOKING), which the page looks it up by.
--    - 496 keeps EXCHD; its name's typo is fixed.
--    - 1095 is referenced only by two KBS2 booking-form options of US Festival 2024 (events 1409 and 1410, over
--      since 2024; staging, 2026-10-28): they are repointed to 105 -- the same item, now global -- and 1095 is
--      deleted. Every foreign key to item is NO ACTION, so a reference production has and staging did not would make
--      the DELETE fail, never cascade: it is caught, and 1095 is marked deprecated instead, with a NOTICE.
--    Each statement is guarded on the row still being what this script expects, so it runs once and changes
--    nothing on a database that differs.
--
-- 3. SUMMARIES. Family 18 is hidden from booking summaries (summary_hidden, as Rounding is): a guest's "My bookings"
--    and the Statistics overview leave the reconciliation line out; the back-office booking detail shows it.
--
-- 4. VERIFIED TICKS ON SPLIT PAYMENTS. `cascade_children` copies a spread parent's date, method, payment, refund,
--    pending, successful, read AND verified onto its children whenever any of them is updated. Ticking a parent as
--    verified therefore re-wrote `successful` on each child, which fires `defer_compute_document_deposit` and arms a
--    deposit recompute on every child's booking -- about 1,400 bookings for one festival's 593 split payments -- for
--    a flag that moves no money. Split in two:
--    - `cascade_children` no longer fires on `verified` (it still copies it, with the rest, when another column
--      changes);
--    - `cascade_children_verified` fires only when a spread parent's `verified` or `verifier` actually changes, and
--      sets just those two columns on the children, where they differ. The children now also get the verifier,
--      which they never did.
--    Whoever ticks a split payment -- KBS2's Statements screen today -- still ticks its children, and nothing else
--    is re-written.
--
-- No anonymiser change: rows in tables classified whole-table not-pii (`operation`, `item`, `item_family`, `option`
-- re-read rather than assumed, since a '*' row admits new columns -- none is added), and a trigger. The anonymiser
-- disables user triggers on money_transfer while it rewrites `verifier` (20-anonymise.sql, section 4), so the new
-- trigger never runs during a refresh.

-- 1. Operations ---------------------------------------------------------------------------------------------------

INSERT INTO operation (operation_code, name, grant_route, read_only, backend, frontend, public, guest)
SELECT 'RouteToEventReconciliation', 'Open Reconcile events', '/event-reconciliation*', true, true, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'RouteToEventReconciliation');

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT 'ReconcileEvents', 'Close, settle and reconcile an event''s accounts', true, false, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'ReconcileEvents');

-- 2. Items ---------------------------------------------------------------------------------------------------------

UPDATE item SET organization_id = NULL, code = 'unreconciled'
 WHERE id = 105 AND family_id = 18 AND name = 'Unreconciled booking'
   AND (organization_id IS NOT NULL OR code IS DISTINCT FROM 'unreconciled')
   AND NOT EXISTS (SELECT 1 FROM item other WHERE other.code = 'unreconciled' AND other.id <> 105);

UPDATE item SET organization_id = NULL, name = 'Exchange discrepancy'
 WHERE id = 496 AND family_id = 18 AND code = 'EXCHD'
   AND (organization_id IS NOT NULL OR name IS DISTINCT FROM 'Exchange discrepancy');

UPDATE option SET item_id = 105
 WHERE item_id = 1095
   AND EXISTS (SELECT 1 FROM item i WHERE i.id = 105 AND i.code = 'unreconciled' AND i.organization_id IS NULL);

DO $$
BEGIN
    DELETE FROM item WHERE id = 1095 AND family_id = 18 AND name = 'Unreconciled booking';
EXCEPTION WHEN foreign_key_violation THEN
    UPDATE item SET deprecated = true WHERE id = 1095;
    RAISE NOTICE 'V0148: item 1095 is still referenced, so it was marked deprecated instead of deleted';
END $$;

-- 3. Summaries -----------------------------------------------------------------------------------------------------

UPDATE item_family SET summary_hidden = true WHERE id = 18 AND code = 'unrec' AND NOT summary_hidden;

-- 4. Verified ticks on split payments ------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION trigger_money_transfer_cascade_children_verified() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE money_transfer SET verified = NEW.verified, verifier = NEW.verifier
     WHERE parent_id = NEW.id
       AND (verified IS DISTINCT FROM NEW.verified OR verifier IS DISTINCT FROM NEW.verifier);
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS cascade_children ON money_transfer;
CREATE TRIGGER cascade_children
    AFTER UPDATE OF date, method_id, payment, refund, pending, successful, read ON money_transfer
    FOR EACH ROW WHEN (new.spread) EXECUTE FUNCTION trigger_money_transfer_cascade_children();

DROP TRIGGER IF EXISTS cascade_children_verified ON money_transfer;
CREATE TRIGGER cascade_children_verified
    AFTER UPDATE OF verified, verifier ON money_transfer
    FOR EACH ROW WHEN (new.spread AND (old.verified IS DISTINCT FROM new.verified OR old.verifier IS DISTINCT FROM new.verifier))
    EXECUTE FUNCTION trigger_money_transfer_cascade_children_verified();

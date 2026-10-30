-- V0152: EditBookingPrices -- setting a booking's prices from the back office -- and its grant to every role that
-- holds SubmitBookings today.
--
-- WHY. A back-office submit that sets a line's price (PriceDocumentLineEvent: a custom price, a discount, a minimum
-- deposit) or moves a deposit from cancelled bookings (GetBackCancelledMultipleBookingsDepositEvent) needed only
-- SubmitBookings (V0138), so anyone able to change a booking could also make it free or give it any price. The
-- agreed model (2026-10-23) keeps SubmitBookings as the everyday right and gives money a right of its own. The
-- document service now asks for this operation as well, in the same places -- the organization and event of every
-- booking the submit names, read from the database (BackofficeSubmitScope.priceRefusal). It OBSERVES first
-- (BackofficeSubmitScope.PRICES_ENFORCING = false) and enforces after a clean census.
--
-- The front office never had it: DocumentSubmitAuthorization refuses both events from a front-office submit
-- (enforced since 2026-10-28). ApplyFacilityFeeEvent is not a price change here -- it applies the event's own rate,
-- and members choose it themselves.
--
-- WHO. Every role holding SubmitBookings, directly or through an operation group (Bruno, 2026-10-30): nobody loses
-- what they can do today, and the two rights can be told apart from now on. Production's history over 180 days
-- (2026-10-30) found price changes by a super administrator and one Registration user only, so narrowing the grant
-- later is a decision about roles, not a repair.
--
-- NO grant_route: it gates an action, not a page (V0106's precedent). read_only = false: a read-only access row must
-- not carry it. Super administrators need no row: they pass every RouteAccessGuard check.
--
-- Idempotent on the code and on each grant: re-running adds nothing.
--
-- ANONYMISER: no change. This adds rows only, to `operation` and `authorization_role_operation`, both classified
-- whole-table not-pii ('reference') in scripts/gdpr-anonymise/10-anon-helpers.sql (re-read 2026-10-30); no table or
-- column is added, renamed or dropped.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT 'EditBookingPrices', 'Set a booking''s prices and discounts', true, false, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'EditBookingPrices');

WITH holders AS (
    SELECT DISTINCT ro.role_id
      FROM authorization_role_operation ro
      JOIN operation holder
        ON holder.id = ro.operation_id
        OR (ro.operation_group_id IS NOT NULL AND holder.group_id = ro.operation_group_id)
     WHERE holder.operation_code = 'SubmitBookings'
)
INSERT INTO authorization_role_operation (role_id, operation_id)
SELECT DISTINCT h.role_id, target.id
  FROM holders h
  JOIN operation target ON target.operation_code = 'EditBookingPrices'
 WHERE NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = h.role_id
                      AND existing.operation_id = target.id);

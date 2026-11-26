-- Seeds RecordPayments and DeletePayments, the two rights the back office's payment endpoints ask for
-- (DocumentService recordPayment / transferPayment / editPayment / deletePayment, served by BookingPayments
-- in modality-ecommerce-document-service-server), and grants RecordPayments to the registration desk.
--
-- WHY: no client may write money_transfer (ClientWriteSecrets.denyTable, enforcing since 2026-10-19), so the
-- payments the desk enters in KBS2 today -- about 1,300 a year on KBS3 events (staging, 2026-11-26) -- can
-- only come to KBS3 through server endpoints, and the booking operations model (agreed 2026-10-23) gives
-- money its own right rather than folding it into SubmitBookings. Design:
-- docs/design/registration-bookings-payments.md (aggregate repo).
--
-- RecordPayments: recording a payment or a refund made elsewhere (terminal, cash, bank, a gateway refund),
-- moving money from one booking to another, and correcting a payment entered by hand. Granted, like V0180,
-- to every role holding BOTH RouteToRegistration and SubmitBookings -- KBS2's "Add a payment" and "Add a
-- transfer" are in the Bookings screen of every registration role. The read-only role that can open the
-- page (on staging, "Data Protection Officer") gets nothing.
--
-- DeletePayments: deleting one. Granted to NOBODY: KBS2 offered it only to the super, festacc1, festacc2
-- and festorg roles, and those have no KBS3 counterpart to match by. An administrator ticks it onto the
-- role that does a centre's accounts. Super administrators hold both through `operation:*`.
--
-- Both have no grant_route (they gate controls, not pages: V0106's precedent), backend only, and are
-- neither public nor guest -- either would grant them to every caller, anonymous included.
--
-- A role's grant reaches only as far as the access row that assigns it (organisation, and event when it
-- names one); the server asks for the operation in the organisation and event of the booking itself.
--
-- Idempotent: an existing operation is not inserted again, and a role already holding RecordPayments is
-- skipped. `role_holds` follows both shapes a grant can take -- the operation itself or a group it is in --
-- for the reason V0106 records.
--
-- No anonymiser or erasure-manifest change: operation and authorization_role_operation are classified
-- ('*', 'not-pii', 'reference'), and this adds rows, not columns.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT 'RecordPayments', 'Record payments, refunds and transfers on bookings', true, false, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'RecordPayments');

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT 'DeletePayments', 'Delete payments entered on bookings', true, false, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'DeletePayments');

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
  JOIN operation target ON target.operation_code = 'RecordPayments'
 WHERE holder.operation_code = 'RouteToRegistration'
   AND NOT EXISTS (SELECT 1
                     FROM authorization_role_operation existing
                    WHERE existing.role_id = holder.role_id
                      AND existing.operation_id = target.id);

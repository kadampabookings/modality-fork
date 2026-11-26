package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.session.state.StateAccessor;
import one.modality.ecommerce.document.service.spi.impl.server.BookingPayments.Booking;
import one.modality.ecommerce.document.service.spi.impl.server.BookingPayments.Payment;

/**
 * Check for {@link BookingPayments} — the payments the back office enters on a booking.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero on failure,
 * following MoneyTransferVerificationCheck. The database half (the writes, the triggers they fire, the cascade that
 * deletes a transfer) is rehearsed on a disposable postgres:17 — see docs/design/registration-bookings-payments.md.
 */
public class BookingPaymentsCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("BookingPaymentsCheck");

        // ── Refused before anything is read, for every act ──
        Object claimedOnly = StateAccessor.setBackoffice(StateAccessor.createEmptyState(), true);
        Object empty = StateAccessor.createEmptyState();
        Object verifiedNobody = StateAccessor.setVerifiedBackoffice(StateAccessor.createEmptyState(), true);
        for (Object state : new Object[] { claimedOnly, empty, null, verifiedNobody }) {
            String who = state == claimedOnly ? "a client-claimed back office" : state == empty ? "an empty state"
                : state == null ? "no state" : "a verified audience with no principal";
            check(who + " cannot record", refused(BookingPayments.record(state, 1, 1, 100, false, null, null, false)));
            check(who + " cannot transfer", refused(BookingPayments.transfer(state, 1, 2, 100, null)));
            check(who + " cannot edit", refused(BookingPayments.edit(state, 1, 1, 1, 100, false, null, null)));
            check(who + " cannot delete", refused(BookingPayments.delete(state, 1, 1, null)));
        }

        // ── Amounts: refused, never defaulted ──
        check("a positive amount is read", Integer.valueOf(1250).equals(BookingPayments.signedAmount(1250)));
        check("a negative amount (a refund) is read", Integer.valueOf(-700).equals(BookingPayments.signedAmount(-700L)));
        check("a whole Double is read", Integer.valueOf(30).equals(BookingPayments.signedAmount(30.0)));
        check("zero is refused", BookingPayments.signedAmount(0) == null);
        check("a fraction of a minor unit is refused", BookingPayments.signedAmount(12.5) == null);
        check("NaN is refused", BookingPayments.signedAmount(Double.NaN) == null);
        check("infinity is refused", BookingPayments.signedAmount(Double.POSITIVE_INFINITY) == null);
        check("above the ceiling is refused", BookingPayments.signedAmount(BookingPayments.MAX_AMOUNT + 1) == null);
        check("below minus the ceiling is refused", BookingPayments.signedAmount(-BookingPayments.MAX_AMOUNT - 1) == null);
        check("the ceiling itself is read", BookingPayments.signedAmount(BookingPayments.MAX_AMOUNT) != null);
        check("a digit string is refused", BookingPayments.signedAmount("100") == null);
        check("null is refused", BookingPayments.signedAmount(null) == null);

        // ── Text ──
        check("null text is allowed", BookingPayments.isOptionalText(null, 10));
        check("text at the limit is allowed", BookingPayments.isOptionalText("x".repeat(255), BookingPayments.MAX_REF));
        check("text over the limit is refused", !BookingPayments.isOptionalText("x".repeat(256), BookingPayments.MAX_REF));
        check("a number in place of text is refused", !BookingPayments.isOptionalText(42, 10));
        check("blank text is stored as null", BookingPayments.cleanText("   ") == null);
        check("text is trimmed", "abc".equals(BookingPayments.cleanText("  abc ")));

        // ── Card numbers: never stored ──
        check("a test card number together is found", BookingPayments.containsCardNumber("4111111111111111"));
        check("one in groups of four with spaces is found", BookingPayments.containsCardNumber("paid with 4111 1111 1111 1111 today"));
        check("one with dashes is found", BookingPayments.containsCardNumber("4111-1111-1111-1111"));
        check("an Amex-length one is found", BookingPayments.containsCardNumber("3782 822463 10005"));
        check("16 digits that fail the Luhn check are not a card", !BookingPayments.containsCardNumber("1234567812345678"));
        check("a short terminal reference is not a card", !BookingPayments.containsCardNumber("AUTH 004512"));
        check("a last-four note is not a card", !BookingPayments.containsCardNumber("card ending 1111"));
        check("a date and an amount are not a card", !BookingPayments.containsCardNumber("26/11/2026 120.00"));
        check("null is not a card", !BookingPayments.containsCardNumber(null));
        check("two spaces break a group (separate numbers)", !BookingPayments.containsCardNumber("4111  1111  1111  1111"));
        check("the Luhn check accepts a valid number", BookingPayments.luhn("79927398713", 0, 11));
        check("the Luhn check refuses an invalid one", !BookingPayments.luhn("79927398710", 0, 11));

        check("separators people type between groups are seen through", BookingPayments.containsCardNumber("4111.1111.1111.1111")
            && BookingPayments.containsCardNumber("4111/1111/1111/1111") && BookingPayments.containsCardNumber("4111_1111_1111_1111"));

        // ── Online is recorded by hand only as a refund ──
        check("an Online refund is allowed", BookingPayments.onlineOnlyAsRefund(5, -1500));
        check("a positive Online payment is not", !BookingPayments.onlineOnlyAsRefund(5, 1500));
        check("cash may be either sign", BookingPayments.onlineOnlyAsRefund(1, 1500) && BookingPayments.onlineOnlyAsRefund(1, -1500));

        // ── The log never carries the database's text ──
        check("the SQLSTATE is read", "P0001".equals(BookingPayments.sqlState("ERROR: No open money account found for this payment (P0001)")));
        check("the last code wins", "23514".equals(BookingPayments.sqlState("ERROR: x (ABCDE) violates check constraint (23514)")));
        check("no code reads as ?", "?".equals(BookingPayments.sqlState("Failing row contains (a, b)")) && "?".equals(BookingPayments.sqlState(null)));

        // ── History wording ──
        check("12.50", "12.50".equals(BookingPayments.formatAmount(1250)));
        check("-7.00", "-7.00".equals(BookingPayments.formatAmount(-700)));
        check("0.05", "0.05".equals(BookingPayments.formatAmount(5)));
        Booking a = new Booking(10, 7, 1957, 1, null, 0), b = new Booking(11, 8, 1957, 1, null, 0), c = new Booking(12, 9, 1993, 1, null, 0);
        check("a booking of the same event is named by its ref", "#8".equals(BookingPayments.labelFrom(b, a)));
        check("one of another event says which", "#9 (event 1993)".equals(BookingPayments.labelFrom(c, a)));
        check("a reconciled event is closed", new Booking(1, 1, 1, 1, "RECONCILED", 0).eventClosed());
        check("an archived event is closed", new Booking(1, 1, 1, 1, "ARCHIVED", 0).eventClosed());
        check("an event with no state is open", !new Booking(1, 1, 1, 1, null, 0).eventClosed());
        check("an open event is open", !new Booking(1, 1, 1, 1, "OPEN", 0).eventClosed());

        // ── A transfer takes no more than was paid on the booking it leaves ──
        Booking paid120 = new Booking(1, 1, 1, 1, null, 12000);
        check("a transfer of everything paid is allowed", !paid120.cannotGive(12000));
        check("a transfer of part of it is allowed", !paid120.cannotGive(1));
        check("a transfer of a cent more is refused", paid120.cannotGive(12001));
        check("nothing paid gives nothing", new Booking(1, 1, 1, 1, null, 0).cannotGive(1));
        check("an overdrawn booking gives nothing", new Booking(1, 1, 1, 1, null, -500).cannotGive(1));
        check("the booking read takes the paid amount", BookingPayments.BOOKING_SQL.contains("d.price_deposit"));

        // ── Which payments staff may change ──
        check("a hand-entered cash payment may be changed", BookingPayments.notChangeableReason(payment(1, false, false, false, false, null, false)) == null);
        check("a hand-entered online refund may be changed", BookingPayments.notChangeableReason(payment(5, false, false, false, false, null, false)) == null);
        check("a gateway's payment may not", "gateway".equals(BookingPayments.notChangeableReason(payment(5, true, false, false, false, null, false))));
        check("a verified one may not", "verified".equals(BookingPayments.notChangeableReason(payment(1, false, true, false, false, null, false))));
        check("one in a batch or on a statement may not", "batched".equals(BookingPayments.notChangeableReason(payment(1, false, false, true, false, null, false))));
        check("a receipts batch may not", "batched".equals(BookingPayments.notChangeableReason(payment(1, false, false, false, true, null, false))));
        check("a split payment's child may not", "split".equals(BookingPayments.notChangeableReason(payment(5, false, false, false, false, 99, false))));
        check("a split payment may not", "split".equals(BookingPayments.notChangeableReason(payment(5, false, false, false, false, null, true))));
        check("a positive Online payment may not, even with its gateway columns cleared", "gateway".equals(BookingPayments.notChangeableReason(
            new Payment(1, 10, null, false, 5, 5000, false, false, false, false, "Online", true, false))));
        check("a pending or failed attempt may not", "unsettled".equals(BookingPayments.notChangeableReason(
            new Payment(1, 10, null, false, 1, 500, false, false, false, false, "Cash", false, false))));
        check("one exported to accounting may not", "exported".equals(BookingPayments.notChangeableReason(
            new Payment(1, 10, null, false, 4, 500, false, false, false, false, "Bank transfer", true, true))));
        check("a gift card payment may not", "method".equals(BookingPayments.notChangeableReason(payment(7, false, false, false, false, null, false))));
        check("a transfer row is not refused as a split child (deleteTransfer judges it)",
            BookingPayments.notChangeableReason(payment(6, false, false, false, false, 99, false)) == null);

        // ── Failures the client can translate ──
        check("a closed account says so", BookingPayments.ACCOUNT_CLOSED.equals(
            BookingPayments.failureMessage("ERROR: 'WorldPay Fall 2013 Euro' money account is closed")));
        check("no account for the method says so", BookingPayments.NO_ACCOUNT.equals(
            BookingPayments.failureMessage("ERROR: No open money account found for this payment (P0001)")));
        check("a guard that found nothing says the payment cannot be changed", BookingPayments.NOT_CHANGEABLE.equals(
            BookingPayments.failureMessage("ERROR: division by zero (22012)")));
        check("anything else stays generic", BookingPayments.NOT_SAVED.equals(BookingPayments.failureMessage("deadlock detected")));
        check("no message stays generic", BookingPayments.NOT_SAVED.equals(BookingPayments.failureMessage(null)));

        // ── Ids ──
        check("same key across Integer and Long", BookingPayments.sameKey(5, 5L));
        check("different keys differ", !BookingPayments.sameKey(5, 6));
        check("null is never the same key", !BookingPayments.sameKey(null, null));

        // ── The statements bind their values ──
        for (String sql : BookingPayments.statements())
            check("bound, not concatenated: " + sql.substring(0, Math.min(40, sql.length())), sql.contains("$1")
                && sql.replace("'RECONCILED', 'FINALISED', 'ARCHIVED'", "").indexOf('\'') < 0);
        for (String sql : new String[] { BookingPayments.EDIT_SQL, BookingPayments.DELETE_SQL, BookingPayments.DELETE_TRANSFER_SQL })
            check("guarded: the write and its history are one statement that fails when nothing qualifies — "
                  + sql.substring(0, 30), sql.startsWith("with changed as (") && sql.contains("1 / (select count(*)::int from changed)")
                  && sql.contains("insert into history") && sql.endsWith("where g.guard = 1"));
        for (String rule : new String[] { "not mt.verified", "mt.statement_id is null", "mt.transfer_id is null", "not mt.receipts_transfer",
                "mt.accounting_export_date is null", "not mt.pending and mt.successful", "mt.gateway_response is null",
                "mt.gateway_company_id is null", "mt.document_id = $2", "(mt.method_id <> 5 or mt.amount < 0)", "e.state in" })
            check("the guard repeats: " + rule, BookingPayments.STILL_CHANGEABLE.contains(rule));
        check("edit and delete also require the method and amount as read",
            BookingPayments.EDIT_SQL.contains("mt.method_id = $8 and mt.amount = $9") && BookingPayments.DELETE_SQL.contains("mt.method_id = $3 and mt.amount = $4"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    private static Payment payment(int method, boolean gateway, boolean verified, boolean batched, boolean receipts,
                                   Integer parent, boolean spread) {
        return new Payment(1, 10, parent, spread, method, method == 5 ? -1000 : 1000, verified, receipts, batched, gateway,
            "Cash", true, false);
    }

    private static boolean refused(Future<?> future) {
        return future.failed() && future.cause() != null
               && "This operation is not available to you".equals(future.cause().getMessage());
    }
}

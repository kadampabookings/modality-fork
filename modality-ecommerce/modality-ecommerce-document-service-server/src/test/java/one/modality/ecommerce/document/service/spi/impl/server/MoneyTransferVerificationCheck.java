package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.stack.session.state.StateAccessor;
import one.modality.ecommerce.document.service.spi.impl.server.MoneyTransferVerification.Bucket;

/**
 * Check for {@link MoneyTransferVerification} — the payment ticks of Finance › Payments and Statements.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero on failure,
 * following VerifiedBackofficeCheck. The database half (the classification and the UPDATE against real rows) is
 * rehearsed on a disposable postgres:17 — see docs/design/payment-verification-endpoint-for-bruno.md.
 */
public class MoneyTransferVerificationCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("MoneyTransferVerificationCheck");

        // ── Refused before anything is read ──
        // A front-office claim, an anonymous state, no state at all: refused by the fence, never reaching the shape
        // check or the database. Each would answer the same refusal; the point is that none gets further.
        Object claimedOnly = StateAccessor.setBackoffice(StateAccessor.createEmptyState(), true);
        check("a back office claimed by the client, with no verified token, is refused",
            refused(MoneyTransferVerification.verify(claimedOnly, 1, new Object[] { 1 }, true)));
        check("an empty state is refused", refused(MoneyTransferVerification.verify(StateAccessor.createEmptyState(), 1, new Object[] { 1 }, true)));
        check("no state at all (read after an async hop) is refused", refused(MoneyTransferVerification.verify(null, 1, new Object[] { 1 }, true)));
        Object verifiedNobody = StateAccessor.setVerifiedBackoffice(StateAccessor.createEmptyState(), true);
        check("a verified back-office audience with no principal is refused (no person to grant or to stamp)",
            refused(MoneyTransferVerification.verify(verifiedNobody, 1, new Object[] { 1 }, true)));

        // ── The shape: refused, never defaulted ──
        check("no ids is malformed", MoneyTransferVerification.distinctIds(new Object[0]) == null);
        check("a null id list is malformed", MoneyTransferVerification.distinctIds(null) == null);
        check("1,000 ids are accepted", MoneyTransferVerification.distinctIds(ids(1000)) != null);
        check("1,001 ids are malformed", MoneyTransferVerification.distinctIds(ids(1001)) == null);
        check("one bad id refuses the whole call", MoneyTransferVerification.distinctIds(new Object[] { 1, 2, -3 }) == null);
        check("duplicates collapse", MoneyTransferVerification.distinctIds(new Object[] { 7, 7, 8 }).length == 2);
        check("ids arrive as Integers whatever number type the bus chose",
            MoneyTransferVerification.distinctIds(new Object[] { 5L, 6.0 })[1].equals(6));
        check("zero is not an id", MoneyTransferVerification.positiveInt(0) == null);
        check("a fraction is not an id", MoneyTransferVerification.positiveInt(2.5) == null);
        check("an overflow is not an id", MoneyTransferVerification.positiveInt(3_000_000_000L) == null);
        check("NaN is not an id", MoneyTransferVerification.positiveInt(Double.NaN) == null);
        check("a digit string is not an id", MoneyTransferVerification.positiveInt("12") == null);
        check("a boolean is not an id", MoneyTransferVerification.positiveInt(true) == null);

        // ── The buckets ──
        check("a split child or contra row is ineligible, whatever else is true",
            MoneyTransferVerification.bucketOf(true, false, true, false, false) == Bucket.INELIGIBLE);
        check("an ineligible row already as asked still says ineligible",
            MoneyTransferVerification.bucketOf(false, false, true, false, true) == Bucket.INELIGIBLE);
        check("a tick of a ticked row is unchanged", MoneyTransferVerification.bucketOf(true, true, false, false, false) == Bucket.UNCHANGED);
        check("taking back a tick that is not there is unchanged, not frozen",
            MoneyTransferVerification.bucketOf(false, false, false, false, true) == Bucket.UNCHANGED);
        check("a tick of a payout whose amount is not the expected one is a mismatch",
            MoneyTransferVerification.bucketOf(true, false, false, true, false) == Bucket.MISMATCH);
        check("an untick reaching a closed event is frozen",
            MoneyTransferVerification.bucketOf(false, true, false, false, true) == Bucket.FROZEN);
        check("an untick is never a mismatch (the amount test is the tick's)",
            MoneyTransferVerification.bucketOf(false, true, false, true, false) == Bucket.CHANGE);
        check("a tick is never frozen (the lock holds the untick)",
            MoneyTransferVerification.bucketOf(true, false, false, false, true) == Bucket.CHANGE);
        check("an ordinary tick changes", MoneyTransferVerification.bucketOf(true, false, false, false, false) == Bucket.CHANGE);

        // ── The reply ──
        MoneyTransferVerification.Tally tally = new MoneyTransferVerification.Tally();
        tally.add(Bucket.CHANGE);
        tally.add(Bucket.CHANGE);
        tally.add(Bucket.FROZEN);
        tally.notFound = 3;
        check("the reply is the six counts, in the contract's order and spelling",
            "{\"changed\":2,\"unchanged\":0,\"mismatch\":0,\"frozen\":1,\"ineligible\":0,\"notFound\":3}".equals(tally.json()));

        // ── The database's refusal ──
        check("on_verified's refusal reaches the page as a payout mismatch",
            MoneyTransferVerification.PAYOUT_AMOUNT_MISMATCH.equals(MoneyTransferVerification.failureMessage(
                new RuntimeException("ERROR: Can't mark a transfer with error as verified (P0001)"))));
        check("any other failure is generic", MoneyTransferVerification.NOT_UPDATED.equals(
            MoneyTransferVerification.failureMessage(new RuntimeException("ERROR: deadlock detected"))));
        check("a failure with no message is generic",
            MoneyTransferVerification.NOT_UPDATED.equals(MoneyTransferVerification.failureMessage(new RuntimeException())));

        // ── The statements ──
        String classifyTick = MoneyTransferVerification.classifySql(true), classifyUntick = MoneyTransferVerification.classifySql(false);
        String updateTick = MoneyTransferVerification.updateSql(true), updateUntick = MoneyTransferVerification.updateSql(false);
        String[] names = { "classify (tick)", "classify (untick)", "update (tick)", "update (untick)" };
        String[] statements = { classifyTick, classifyUntick, updateTick, updateUntick };
        for (int i = 0; i < statements.length; i++) {
            String sql = statements[i];
            check(names[i] + " is pinned to the organisation through its accounts",
                sql.contains("mt.to_money_account_id in (select ma.id from money_account ma where ma.organization_id = $")
                && sql.contains("or ma.event_id in (select ev.id from event ev where ev.organization_id = $"));
        }
        check("the classification reads the closed event only for an untick",
            classifyUntick.contains("RECONCILED") && !classifyTick.contains("RECONCILED"));
        check("the classification reads the payout amount only for a tick",
            classifyTick.contains("expected_amount") && !classifyUntick.contains("expected_amount"));
        check("the update writes only the changed direction, and only a flip",
            updateTick.startsWith("update money_transfer mt set verified = $2 where mt.id = any($1) and mt.verified is distinct from $2"));
        check("the update re-asserts eligibility", updateTick.contains("not " + MoneyTransferVerification.NEVER_VERIFIED)
                                                 && updateUntick.contains("not " + MoneyTransferVerification.NEVER_VERIFIED));
        check("the tick's update re-asserts the payout amount", updateTick.contains("not " + MoneyTransferVerification.PAYOUT_AMOUNT_ERROR));
        check("the untick's update re-asserts the lock", updateUntick.contains("not " + MoneyTransferVerification.REACHES_CLOSED_EVENT));
        check("the update never writes a verifier (the trigger stamps it)", !updateTick.contains("verifier") && !updateUntick.contains("verifier"));
        check("the closed states are exactly RECONCILED, FINALISED and ARCHIVED",
            MoneyTransferVerification.REACHES_CLOSED_EVENT.contains("e.state in ('RECONCILED', 'FINALISED', 'ARCHIVED')"));
        check("the lock reaches children, batch members and members' children",
            MoneyTransferVerification.REACHES_CLOSED_EVENT.contains("c.parent_id = mt.id")
            && MoneyTransferVerification.REACHES_CLOSED_EVENT.contains("m.transfer_id = mt.id")
            && MoneyTransferVerification.REACHES_CLOSED_EVENT.contains("mc.parent_id = m.id"));
        check("no statement reads a name or an email",
            !(classifyTick + classifyUntick + updateTick + updateUntick).matches("(?s).*(first_name|last_name|email|person).*"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    private static boolean refused(dev.webfx.platform.async.Future<String> future) {
        return future.failed() && "This operation is not available to you".equals(future.cause().getMessage());
    }

    private static Object[] ids(int n) {
        Object[] ids = new Object[n];
        for (int i = 0; i < n; i++)
            ids[i] = i + 1;
        return ids;
    }
}

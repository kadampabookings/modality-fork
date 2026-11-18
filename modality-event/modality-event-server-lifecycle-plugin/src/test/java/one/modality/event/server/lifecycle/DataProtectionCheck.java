package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Check for the Data Protection Officer's gate (V0163): the strictly-DPO gate's database-free fences, and the access
 * answer the back office draws from.
 *
 * <p>No test framework (see MoneyTransferVerificationCheck); main() exits non-zero on failure. The operation itself is
 * asked of the authorization framework through {@code OperationAuthority} (OperationAuthorityCheck in gateway-shared
 * covers the seam; the framework's "held for a whole organization" answer is CoreSemanticsCheck's), and the flag guard
 * needs a database: that a super administrator without the role is refused is checked on staging.
 */
public class DataProtectionCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    private static final String REFUSED = "This operation is not available to you";

    private static boolean refusedWithoutWork(Object state) {
        AtomicInteger runs = new AtomicInteger();
        Future<String> answer = ThreadLocalStateHolder.runWithState(state,
            () -> EventErasureAccess.whenDataProtectionOfficer("check", caller -> {
                runs.incrementAndGet();
                return Future.succeededFuture("ran");
            }));
        return answer.failed() && REFUSED.equals(answer.cause().getMessage()) && runs.get() == 0;
    }

    private static boolean toldNo(Object state) {
        Future<Boolean> answer = ThreadLocalStateHolder.runWithState(state, EventErasureAccess::isDataProtectionOfficer);
        return answer.succeeded() && Boolean.FALSE.equals(answer.result());
    }

    public static void main(String[] args) {
        System.out.println("DataProtectionCheck");

        // ── The gate's fences, before any database lookup ──
        ModalityUserPrincipal principal = new ModalityUserPrincipal(456, 7);
        Object anonymous = StateAccessor.createEmptyState();
        Object noFamily = StateAccessor.setVerifiedBackoffice(StateAccessor.setUserId(StateAccessor.createEmptyState(), principal), true);
        Object frontOffice = StateAccessor.setVerifiedBackoffice(StateAccessor.setSessionFamilyId(
            StateAccessor.setUserId(StateAccessor.createEmptyState(), principal), "family-1"), false);
        Object supportView = StateAccessor.setVerifiedBackoffice(StateAccessor.setSessionFamilyId(StateAccessor.setUserId(
            StateAccessor.createEmptyState(), new ModalityUserPrincipal(99, 8, 456)), "family-1"), true);
        check("an anonymous caller is refused, and nothing runs", refusedWithoutWork(anonymous));
        check("a session the server did not establish is refused", refusedWithoutWork(noFamily));
        check("a front-office session is refused", refusedWithoutWork(frontOffice));
        check("a support view is refused", refusedWithoutWork(supportView));
        check("dpo/access tells an anonymous caller no, without refusing", toldNo(anonymous));
        check("dpo/access tells a front-office session no", toldNo(frontOffice));
        check("dpo/access tells a support view no", toldNo(supportView));
        check("the operation is V0163's", "DataProtection".equals(EventErasureAccess.DATA_PROTECTION_OPERATION));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

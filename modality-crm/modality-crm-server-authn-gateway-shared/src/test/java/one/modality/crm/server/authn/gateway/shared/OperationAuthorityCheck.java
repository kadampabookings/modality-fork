package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;

/**
 * The seam the erasure endpoints ask "does the caller hold this operation?" through: it must answer no whenever it
 * cannot answer properly — nothing registered, an authority that fails, one that throws — and pass the registered
 * answer (and the fresh flag) through untouched otherwise.
 *
 * <p>No test framework; main() exits non-zero on failure, like the checks next door.
 *
 * @author Claude Code
 */
public class OperationAuthorityCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static boolean answer(Future<Boolean> future) {
        return future.isComplete() && future.succeeded() && Boolean.TRUE.equals(future.result());
    }

    static boolean refused(Future<Boolean> future) {
        return future.isComplete() && future.succeeded() && Boolean.FALSE.equals(future.result());
    }

    public static void main(String[] args) {
        System.out.println("OperationAuthorityCheck");

        OperationAuthority.register(null);
        check("nothing registered: no", refused(OperationAuthority.holdsInSomeOrganization("DataProtection", false)));
        check("...and it says it is not registered", !OperationAuthority.isRegistered());

        boolean[] lastFresh = { false };
        OperationAuthority.register((code, fresh) -> {
            lastFresh[0] = fresh;
            return Future.succeededFuture("DataProtection".equals(code));
        });
        check("a registered yes passes through", answer(OperationAuthority.holdsInSomeOrganization("DataProtection", false)));
        check("a registered no passes through", refused(OperationAuthority.holdsInSomeOrganization("Other", false)));
        OperationAuthority.holdsInSomeOrganization("DataProtection", true);
        check("the fresh flag reaches the authority", lastFresh[0]);
        check("no operation code: no", refused(OperationAuthority.holdsInSomeOrganization(null, false)));

        OperationAuthority.register((code, fresh) -> Future.failedFuture("database down"));
        check("an authority that fails: no", refused(OperationAuthority.holdsInSomeOrganization("DataProtection", false)));

        OperationAuthority.register((code, fresh) -> { throw new IllegalStateException("boom"); });
        check("an authority that throws: no", refused(OperationAuthority.holdsInSomeOrganization("DataProtection", false)));

        OperationAuthority.register(null);
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

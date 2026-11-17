package one.modality.crm.server.services.authz;

import dev.webfx.stack.db.query.ServerCallerParameters;

import java.util.Arrays;

/**
 * That "this caller may read nothing", "there is no such caller" and "nobody has worked out what this caller
 * may read" stay three answers.
 *
 * <p>They were two, and then one. The 11 November staging rehearsal turned the back-office inbox from 249
 * conversations to 0 because a super administrator's scope — unrestricted, and carrying EMPTY arrays to say
 * so — was bound as if it were a list of grants. That is pinned here too: what saves such a caller is the
 * token they read under, not these values, and the check says so rather than leaving the empty arrays
 * looking harmless.
 *
 * <p>What no check here catches is the call site, which reads the thread state: that
 * {@code grantedScopeOf} passes {@code personId != null && isEffectivelyBackoffice()} and not something
 * else. The predicate is pinned, the call is not — the same honest gap {@code CallerReadScopeCheck} records
 * for its own.
 *
 * @author Claude Code
 */
public class ModalityCallerParameterResolverCheck {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        CallerReadScope.Scope granted = new CallerReadScope.Scope(new int[] {7, 8}, new int[] {151}, false);
        CallerReadScope.Scope noGrants = new CallerReadScope.Scope(new int[0], new int[0], false);
        CallerReadScope.Scope unrestricted = new CallerReadScope.Scope(new int[0], new int[0], true);

        check("a computed scope binds its granted events",
              Arrays.equals(new int[] {7, 8},
                  (int[]) ModalityCallerParameterResolver.grantedScopeAnswer(granted, true, true)));
        check("and its granted organisations, which are a different array",
              Arrays.equals(new int[] {151},
                  (int[]) ModalityCallerParameterResolver.grantedScopeAnswer(granted, false, true)));

        // A REAL answer, and the reason "not computed yet" could not go on sharing it.
        check("an operator holding no grants binds empty arrays, which is an answer",
              Arrays.equals(new int[0],
                  (int[]) ModalityCallerParameterResolver.grantedScopeAnswer(noGrants, true, true)));

        // The 11 November defect, pinned the way it actually behaves rather than the way it ought to look.
        check("an UNRESTRICTED scope binds empty arrays too — identical to holding no grants",
              Arrays.equals(new int[0],
                  (int[]) ModalityCallerParameterResolver.grantedScopeAnswer(unrestricted, true, true)));
        check("...so what saves a super admin is the UNRESTRICTED token, not these values",
              unrestricted.unrestricted() && !noGrants.unrestricted());

        // THE change: the two meanings of an absent scope, which were one null.
        check("no scope, where one was expected, is UNAVAILABLE — the read is refused, not emptied",
              ModalityCallerParameterResolver.grantedScopeAnswer(null, true, true)
                  == ServerCallerParameters.UNAVAILABLE);
        check("and on both arrays, since a statement may bind either",
              ModalityCallerParameterResolver.grantedScopeAnswer(null, false, true)
                  == ServerCallerParameters.UNAVAILABLE);
        check("no scope, where none will ever be computed, stays null and matches nothing",
              ModalityCallerParameterResolver.grantedScopeAnswer(null, true, false) == null);
        check("...which is not the same answer, and that is the whole point",
              ModalityCallerParameterResolver.grantedScopeAnswer(null, true, false)
                  != ModalityCallerParameterResolver.grantedScopeAnswer(null, true, true));

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " caller parameter resolver checks failed");
    }

    private static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else    { fail++; System.out.println("  FAIL " + what); }
    }
}

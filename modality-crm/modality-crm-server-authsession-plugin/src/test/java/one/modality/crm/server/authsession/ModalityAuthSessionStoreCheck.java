package one.modality.crm.server.authsession;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.session.token.SessionFamilyStore;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;

import java.util.List;

/**
 * What the session store does before it reaches the database — which is where its one safety rule lives.
 *
 * <p>"Sign out my other devices" is a person-scoped statement, and a guest has no person: they hold a
 * principal made from an email and reach their booking through a link. Asking the store to end a
 * guest's other sessions must therefore end nothing, and it must decide that BEFORE running anything —
 * which is what makes this checkable with no database at all, and why the rule is written as an early
 * return rather than as a predicate inside the SQL.
 *
 * <p>Why it is worth pinning: {@code where person_id = null} matches no rows today, so the early return
 * looks redundant. It is not. A statement whose predicate means "nobody" is one edit away from meaning
 * "everybody", and of every statement in this system, this is the one where that would be worst.
 *
 * <p>No test framework, for the reason recorded in webfx-stack-authz-core. Run from main(); it exits
 * non-zero while the issue stands.
 */
public class ModalityAuthSessionStoreCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); } else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        SessionFamilyStore store = new ModalityAuthSessionStore();

        System.out.println("a guest has no other devices to sign out:");
        ModalityGuestPrincipal guest = new ModalityGuestPrincipal("someone@example.com");
        Future<List<String>> guestResult = store.revokeOtherFamilies(guest, "their-cart-session", "signed-out-elsewhere");
        // No datasource is configured here, so reaching one would fail the future or throw. Succeeding
        // with nothing is the proof that it never went.
        check("it answers at once, having ended nothing",
              guestResult.succeeded() && guestResult.result().isEmpty());

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}

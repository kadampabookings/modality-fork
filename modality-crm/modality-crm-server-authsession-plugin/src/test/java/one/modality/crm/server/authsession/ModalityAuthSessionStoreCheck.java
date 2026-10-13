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


        System.out.println("\nwhich holders are a SECOND holder, and which are just the late one:");
        // A generation advances only when the one before it is presented, so the highest generation
        // anybody has ever presented is exactly (family generation - 1). That makes the question exact.
        check("one behind is NOT reuse — nobody has presented anything later",
              !ModalityAuthSessionStore.isReuse(4, 5));
        check("two behind IS reuse — somebody else has",
              ModalityAuthSessionStore.isReuse(3, 5));
        check("far behind is reuse",
              ModalityAuthSessionStore.isReuse(0, 5));
        check("and that holds at the very start of a family",
              !ModalityAuthSessionStore.isReuse(0, 1) && ModalityAuthSessionStore.isReuse(0, 2));

        System.out.println("two tabs converge instead of killing each other, however long they run:");
        // Each hands the other the current generation, so neither is ever more than one behind. This is
        // the case that cost 42 families a day, 40 people, while the rule was a clock instead of a count.
        boolean everKilled = false;
        for (int generation = 1; generation <= 1000; generation++)
            everKilled |= ModalityAuthSessionStore.isReuse(generation - 1, generation);
        check("a thousand rotations and not one false kill", !everKilled);

        System.out.println("the insert that opens a family is self-consistent:");
        // Not pedantry: this statement runs on EVERY login, so a column list and a values list that
        // disagree is total — nobody can sign in — and the mistake is one careless edit away, because
        // adding a column means touching both halves AND the parameter list. V0126 added two columns and
        // did exactly that. A mismatch would otherwise surface as a Postgres syntax error on the first
        // login after a deploy, which is a bad moment to find out.
        String columns = ModalityAuthSessionStore.OPEN_SQL.substring(
            ModalityAuthSessionStore.OPEN_SQL.indexOf('(') + 1, ModalityAuthSessionStore.OPEN_SQL.indexOf(')'));
        int valuesStart = ModalityAuthSessionStore.OPEN_SQL.indexOf("values (") + "values (".length();
        String values = ModalityAuthSessionStore.OPEN_SQL.substring(
            valuesStart, ModalityAuthSessionStore.OPEN_SQL.lastIndexOf(')'));
        check("as many values as columns",
              columns.split(",").length == values.split(",").length);
        // The parameters are passed positionally, so the highest $n must be the number of them and no
        // index may be skipped — $8 with only seven arguments binds null and writes it silently.
        int highest = 0;
        for (int i = 1; i <= 99; i++)
            if (ModalityAuthSessionStore.OPEN_SQL.contains("$" + i + "") && !ModalityAuthSessionStore.OPEN_SQL.contains("$" + i + "0"))
                highest = Math.max(highest, i);
        check("the placeholders run 1..N with no gap", highest == 7);
        check("and it records the two claims V0126 added",
              columns.contains("audience") && columns.contains("methods"));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}

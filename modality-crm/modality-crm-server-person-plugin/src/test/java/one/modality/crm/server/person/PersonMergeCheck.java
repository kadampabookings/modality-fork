package one.modality.crm.server.person;

import java.util.List;
import java.util.Set;

/**
 * Check for the two things about a person merge that are decidable without a database: what the
 * reference list accounts for, and what the batch does with it.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero on
 * failure, following the checks in {@code modality-crm-server-authz-required-plugin}.
 *
 * <p><b>The completeness of the list against the live database is deliberately NOT pinned here.</b> It
 * cannot be — the catalogue is the authority and it is not present at build time — which is exactly why
 * {@code PersonMergeCascade} asks it on every merge and refuses on a reference it does not know. What this
 * file pins is the half that a test can hold: that everything the list names is written before the person
 * is deleted, that nothing is written twice, and that the audit tables are named at all. That last one was
 * the sharpest case in the browser-side test this replaces, and it still is: those tables carry no foreign
 * key BY DESIGN, so the database will never complain on their behalf and the catalogue check cannot see
 * them either. These assertions complain instead.
 */
public class PersonMergeCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static boolean writesTable(List<String> statements, String table) {
        String quoted = '"' + table + '"';
        return statements.stream().anyMatch(s -> s.contains(quoted));
    }

    public static void main(String[] args) {
        System.out.println("PersonMergeCheck");
        List<String> statements = PersonMergeCascade.mergeStatements();

        // --- the audit trails, which no foreign key protects ---
        check("the person/account move trail is repointed", writesTable(statements, "person_account_move"));
        check("the link-change trail is repointed", writesTable(statements, "person_link_change"));
        for (String column : new String[] { "person_id", "old_account_person_id", "new_account_person_id", "changed_by_person_id" })
            check("link-change." + column + " is repointed",
                statements.stream().anyMatch(s -> s.contains("person_link_change") && s.contains('"' + column + '"')));

        // --- the bookings and the identity a member reads ---
        check("bookings follow the survivor", writesTable(statements, "document"));
        check("history follows the survivor", writesTable(statements, "history"));
        check("the account link follows the survivor", writesTable(statements, "person"));
        // recipient.person_id is ON DELETE SET NULL: forgetting it erases silently rather than refusing.
        check("mail recipients follow the survivor", writesTable(statements, "recipient"));

        // --- every table the list names is written ---
        boolean allNamed = true;
        for (String key : PersonReferences.REPOINTED.keySet())
            if (!writesTable(statements, PersonReferences.table(key))) { allNamed = false; System.out.println("    missing: " + key); }
        for (String table : PersonReferences.DEDUPLICATED.keySet())
            if (!writesTable(statements, table)) { allNamed = false; System.out.println("    missing: " + table); }
        check("every repointed table is written by the batch", allNamed);

        // --- order: the delete is last, or it fails on the rows still naming the duplicate ---
        String last = statements.get(statements.size() - 1);
        check("the person is deleted last", last.startsWith("delete from person where id = $2"));
        check("nothing is written after the delete",
            statements.stream().filter(s -> s.startsWith("delete from person where id = $2")).count() == 1);

        // --- the survivor's own references are cleared, not pointed at itself ---
        // The lock comes before everything, or the window it closes is still open: a booking committed
        // between a repoint and the delete is SET NULL rather than refused.
        check("the duplicate's row is locked first", statements.get(0).equals("select id from person where id = $2 for update"));
        check("the survivor's link to the duplicate is cleared before any repoint",
            indexOfFirst(statements, t -> t.contains("set account_person_id = null where id = $1"))
                < indexOfFirst(statements, t -> t.startsWith("update \"person\" set")));
        check("a repoint of person never touches the survivor's own row",
            statements.stream().filter(s -> s.startsWith("update \"person\" set")).allMatch(s -> s.contains("and id <> $1")));

        // --- collisions are dropped before the repoint that would hit them ---
        for (String table : PersonReferences.DEDUPLICATED.keySet()) {
            int delete = indexOfFirst(statements, s -> s.startsWith("delete from \"" + table + '"'));
            int update = indexOfFirst(statements, s -> s.startsWith("update \"" + table + '"'));
            check(table + ": the colliding rows go before the repoint", delete >= 0 && update >= 0 && delete < update);
        }

        // --- nothing is issued twice ---
        check("no statement is issued twice", Set.copyOf(statements).size() == statements.size());

        // --- the privileged tables are refused, never repointed ---
        boolean noGrantWrites = PersonReferences.REFUSING.keySet().stream()
            .map(PersonReferences::table)
            .noneMatch(table -> writesTable(statements, table));
        check("no authorization table is rewritten by a merge", noGrantWrites);

        // --- what the catalogue check compares against ---
        check("the catalogue comparison covers repointed, deduplicated, cascading and refusing",
            PersonReferences.accountedFor().size()
                == PersonReferences.REPOINTED.size() + PersonReferences.DEDUPLICATED.size()
                 + PersonReferences.DATABASE_CASCADES.size() + PersonReferences.REFUSING.size());
        check("a quoted catalogue name compares equal", "session_user.user_id".equals(
            PersonReferences.unquote("\"session_user\"") + ".user_id"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    static int indexOfFirst(List<String> statements, java.util.function.Predicate<String> test) {
        for (int i = 0; i < statements.size(); i++)
            if (test.test(statements.get(i))) return i;
        return -1;
    }
}

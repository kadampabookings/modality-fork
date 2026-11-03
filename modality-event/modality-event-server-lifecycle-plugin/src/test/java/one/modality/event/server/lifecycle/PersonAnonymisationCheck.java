package one.modality.event.server.lifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check for {@link PersonAnonymisationRunner}'s argument rules, and for V0153's refusal keys.
 *
 * <p>No test framework, as {@link EventBookingStateRulesCheck}: runs from main() and exits non-zero on failure. Run
 * with the module directory as the working directory: the keys are read from V0153's own source. The SQL itself is
 * rehearsed on a disposable postgres:17 (see docs/operations/gdpr-person-erasure-reference.md in the aggregate repo).
 */
public class PersonAnonymisationCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    private static final Path V0153 = Path.of("../../modality-base/modality-base-server-dbmigration-plugin/src/main/resources"
        + "/one/modality/base/server/services/dbmigration/scripts/V0153__anonymise_person.sql");

    /** The back office's customers feature, when this module sits in the kbs3-aggregate checkout. */
    private static final Path BACK_OFFICE = Path.of("../../../kbs3-react/backoffice/src/features/customers");

    public static void main(String[] args) throws Exception {
        System.out.println("confirmPhrase");
        check("the phrase names the person", PersonAnonymisationRunner.confirmPhrase(42).equals("ANONYMISE PERSON 42"));

        System.out.println("candidateIds");
        check("nothing ticked = empty", PersonAnonymisationRunner.candidateIds(null).length == 0);
        check("an empty array = empty", PersonAnonymisationRunner.candidateIds(new Object[0]).length == 0);
        check("numbers of any boxed type, de-duplicated, in order",
            Arrays.equals(PersonAnonymisationRunner.candidateIds(new Object[] { 3, 5L, (byte) 3, 7.0 }), new Integer[] { 3, 5, 7 }));
        check("a single number is not an array", PersonAnonymisationRunner.candidateIds(3) == null);
        check("a negative id refuses the lot", PersonAnonymisationRunner.candidateIds(new Object[] { 3, -1 }) == null);
        check("a fraction refuses the lot", PersonAnonymisationRunner.candidateIds(new Object[] { 3.5 }) == null);
        check("a null element refuses the lot", PersonAnonymisationRunner.candidateIds(new Object[] { 3, null }) == null);
        check("a word refuses the lot", PersonAnonymisationRunner.candidateIds(new Object[] { "abc" }) == null);

        System.out.println("sameIds (a commit must send exactly the ticks its preview ran with)");
        check("same set, any order", PersonAnonymisationRunner.sameIds(new Integer[] { 1004, 1003 }, "1003,1004"));
        check("none ticked = an empty or null array", PersonAnonymisationRunner.sameIds(new Integer[0], "")
            && PersonAnonymisationRunner.sameIds(new Integer[0], null));
        check("one more refuses", !PersonAnonymisationRunner.sameIds(new Integer[] { 1003, 1005 }, "1003"));
        check("one fewer refuses", !PersonAnonymisationRunner.sameIds(new Integer[] { 1003 }, "1003,1004"));

        System.out.println("the ledger's grid and the effective ticks");
        java.util.List<EventErasureRunner.GridRow> grid = java.util.List.of(
            new EventErasureRunner.GridRow("scope", "own_bookings", "2"),
            new EventErasureRunner.GridRow("candidate", "1003", "{}"),
            new EventErasureRunner.GridRow("member", "108", "{}"),
            new EventErasureRunner.GridRow("namesake", "7190", "{}"),
            new EventErasureRunner.GridRow("ticks", "candidates", "1003,1004"),
            new EventErasureRunner.GridRow("ticks", "members", null));
        check("the ledger keeps counts, never who was listed",
            PersonAnonymisationRunner.storedGrid(grid).stream().map(EventErasureRunner.GridRow::step).toList().equals(java.util.List.of("scope")));
        check("the effective candidate ticks are read back", Arrays.equals(PersonAnonymisationRunner.tickedIds(grid, "candidates"), new Integer[] { 1003, 1004 }));
        check("no member ticked = none", PersonAnonymisationRunner.tickedIds(grid, "members").length == 0);

        System.out.println("V0153 refusal keys");
        String sql = Files.readString(V0153);
        int headerEnd = sql.indexOf("-- ===== person.anonymised_date");
        String header = sql.substring(0, headerEnd), body = sql.substring(headerEnd);
        Set<String> raised = new TreeSet<>();
        Matcher m = Pattern.compile("'\\[(PersonAnonymise[A-Za-z]+Error)\\]").matcher(body);
        while (m.find())
            raised.add(m.group(1));
        check("the function raises keys (" + raised.size() + ")", raised.size() >= 10);
        for (String key : raised)
            check(key + " is listed in the header", header.contains("--   " + key));

        System.out.println("conflicts (retried by staff, not reported as failures)");
        for (String state : new String[] { "40001", "40P01", "55P03" })
            check("SQLSTATE " + state + " is a conflict", PersonAnonymisationRunner.CONFLICT_SQL_STATES.contains(state));
        check("a unique violation is not (that one is 'busy')", !PersonAnonymisationRunner.CONFLICT_SQL_STATES.contains("23505"));

        // In the aggregate checkout only: every key the server can send is one the back office translates
        if (Files.isDirectory(BACK_OFFICE)) {
            System.out.println("back-office key map");
            String client;
            try (var files = Files.walk(BACK_OFFICE)) {
                client = files.filter(f -> f.toString().endsWith(".ts") && !f.toString().contains("__tests__"))
                    .map(f -> { try { return Files.readString(f); } catch (java.io.IOException e) { throw new RuntimeException(e); } })
                    .reduce("", String::concat);
            }
            Set<String> sent = new TreeSet<>(raised);
            sent.addAll(Set.of(PersonAnonymisationRunner.NOT_FOUND_KEY, PersonAnonymisationRunner.CONFIRM_MISMATCH_KEY,
                PersonAnonymisationRunner.PREVIEW_REQUIRED_KEY, PersonAnonymisationRunner.CANDIDATE_KEY,
                PersonAnonymisationRunner.MEMBER_KEY, PersonAnonymisationRunner.BUSY_KEY, PersonAnonymisationRunner.SQL_KEY,
                PersonAnonymisationRunner.CONFLICT_KEY));
            for (String key : sent)
                check(key + " is mapped by the back office", client.contains(key));
        }

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

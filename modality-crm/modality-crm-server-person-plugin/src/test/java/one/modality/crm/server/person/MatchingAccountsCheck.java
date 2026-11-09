package one.modality.crm.server.person;

/**
 * What {@link MatchingAccountsEndpoint} accepts, and what it charges for.
 *
 * <p>The argument parsing is the whole of what can be checked without a database, and it is also where the
 * metering is decided: permits are spent per address AFTER this has de-duplicated them, so what this returns
 * is what the caller is charged. A batch endpoint on a per-call limit is amplification wearing a uniform —
 * thirty calls of a hundred addresses is three thousand answers on a budget of thirty — so the size of this
 * array is the thing that matters, not just its contents.
 *
 * <p>The SQL is validated separately, against the real schema on staging: it resolves, and 3 accounts carry
 * more than one {@code owner=true} person row, which is why the statement groups and takes {@code min(p.id)}
 * rather than trusting one row per address.
 *
 * <p>The reply's two pending flags are not asserted here — they need a QueryResult, and the first real client
 * call is what exercises them. They are two rather than one because the callers mean opposite things: the
 * members page excludes a member only on a pending SHARE request ({@code inviterPayer=true}), the invite
 * manager asks about a manager invitation ({@code false}). A single flag shipped first and would have hidden
 * a member card because a "book for me" invitation was outstanding.
 *
 * @author Claude Code
 */
public class MatchingAccountsCheck {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // ── What it accepts ───────────────────────────────────────────────────────────────────────────────
        check("one address", 1, lengthOf(new Object[] {"a@b.com"}));
        check("several", 3, lengthOf(new Object[] {"a@b.com", "c@d.com", "e@f.com"}));
        check("addresses are lower-cased, so the sql's lower() has nothing left to do",
              "mixed@case.com", first(new Object[] {"Mixed@Case.COM"}));
        check("and trimmed, which is what a pasted address arrives as",
              "a@b.com", first(new Object[] {"  a@b.com  "}));

        // ── De-duplication, which is metering and not tidiness ────────────────────────────────────────────
        // Permits are spent per address in the returned array. Without this, the same address sent fifty
        // times would cost fifty permits for one question — and a caller could make a small batch look large.
        check("duplicates cost one permit, not many", 1, lengthOf(new Object[] {"a@b.com", "a@b.com"}));
        check("including when they differ only in case or spacing",
              1, lengthOf(new Object[] {"a@b.com", "A@B.com", " a@b.com "}));
        check("and the first spelling is the one kept, so two calls agree",
              "a@b.com", first(new Object[] {"a@b.com", "A@B.COM"}));

        // ── What it refuses ───────────────────────────────────────────────────────────────────────────────
        check("nothing at all", null == MatchingAccountsEndpoint.addressesOf(new Object[0]));
        check("not an array", null == MatchingAccountsEndpoint.addressesOf("a@b.com"));
        check("null", null == MatchingAccountsEndpoint.addressesOf(null));
        // One bad address refuses the CALL. Skipping it silently would answer a different question from the
        // one asked, and the caller would read the gap as "no account" — a wrong answer dressed as an answer.
        check("one bad address among good ones",
              null == MatchingAccountsEndpoint.addressesOf(new Object[] {"a@b.com", "not-an-address"}));
        check("a non-string element", null == MatchingAccountsEndpoint.addressesOf(new Object[] {"a@b.com", 7}));
        check("an empty string", null == MatchingAccountsEndpoint.addressesOf(new Object[] {""}));

        // ── The cap, which bounds an array the caller chooses ─────────────────────────────────────────────
        Object[] atCap = new Object[MatchingAccountsEndpoint.MAX_ADDRESSES];
        for (int i = 0; i < atCap.length; i++)
            atCap[i] = "a" + i + "@b.com";
        check("at the cap", MatchingAccountsEndpoint.MAX_ADDRESSES, lengthOf(atCap));
        Object[] overCap = new Object[MatchingAccountsEndpoint.MAX_ADDRESSES + 1];
        for (int i = 0; i < overCap.length; i++)
            overCap[i] = "a" + i + "@b.com";
        check("one over it is refused, before any permit is spent",
              null == MatchingAccountsEndpoint.addressesOf(overCap));
        // Refused on the RAW length, not the de-duplicated one: otherwise a caller could send ten thousand
        // copies of one address and have the cap pass, which is a request this should not be parsing at all.
        Object[] manyDuplicates = new Object[MatchingAccountsEndpoint.MAX_ADDRESSES + 1];
        java.util.Arrays.fill(manyDuplicates, "a@b.com");
        check("and a long array of ONE repeated address is refused too",
              null == MatchingAccountsEndpoint.addressesOf(manyDuplicates));

        // ── One address is judged the same here as by the endpoint that answers it singly ─────────────────
        // Divergence would mean an address the single lookup accepts and the batch refuses, or worse the
        // other way, and the two answer the same question.
        for (String bad : new String[] {"no-at-sign", "@nolocal.com", ""})
            check("rejected by both: '" + bad + "'",
                  null == EmailHasAccountEndpoint.emailOf(new Object[] {bad})
                  && null == MatchingAccountsEndpoint.addressesOf(new Object[] {bad}));

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " matching accounts checks failed");
    }

    private static int lengthOf(Object argument) {
        String[] addresses = MatchingAccountsEndpoint.addressesOf(argument);
        return addresses == null ? -1 : addresses.length;
    }

    private static String first(Object argument) {
        String[] addresses = MatchingAccountsEndpoint.addressesOf(argument);
        return addresses == null || addresses.length == 0 ? null : addresses[0];
    }

    private static void check(String what, int expected, int actual) {
        check(what + " (" + actual + ")", expected == actual);
    }

    private static void check(String what, String expected, String actual) {
        check(what + " (" + actual + ")", expected.equals(actual));
    }

    private static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else    { fail++; System.out.println("  FAIL " + what); }
    }
}

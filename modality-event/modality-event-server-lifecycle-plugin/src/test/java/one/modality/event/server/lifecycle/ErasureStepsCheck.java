package one.modality.event.server.lifecycle;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstArray;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryResultBuilder;

import java.util.List;
import java.util.Set;

/**
 * Check for the "Erase personal data" steps around the anonymisation, without a database: what a search text is and
 * what the find statements keep ({@link ErasureFind}), what a residue check accepts and counts
 * ({@link ErasureResidueCheck}), both limited to the caller's organisations, and the erase-everywhere approvals' rules
 * ({@link ErasureApprovals}); and the JSON they write — read back with the JSON parser, so every reply is checked to
 * be JSON, with the contract's keys. Formerly ErasureRequestsCheck (the register is gone, V0174).
 *
 * <p>No test framework, as {@link PersonAnonymisationCheck}: runs from main() and exits non-zero on failure. The SQL is
 * rehearsed on a disposable copy of the database (every statement, with timings), not here.
 */
public class ErasureStepsCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** The caller's organisations in the SQL checks. */
    private static final Set<Integer> OWN = Set.of(187, 151);

    public static void main(String[] args) {
        find();
        organisations();
        residueCheck();
        approvals();
        replies();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    // ===== find ===================================================================================================

    private static ErasureFind.Kind kind(Object argument) {
        ErasureFind.Classified c = ErasureFind.classify(argument);
        return c.query() == null ? null : c.query().kind();
    }

    private static boolean refused(Object argument) {
        ErasureFind.Classified c = ErasureFind.classify(argument);
        return c.query() == null && c.refusal() != null;
    }

    private static void find() {
        System.out.println("find: what the text is");
        check("an @ makes an email", kind(new Object[] { "jane@example.org" }) == ErasureFind.Kind.EMAIL);
        check("the email is bound trimmed", "jane@example.org".equals(ErasureFind.classify(new Object[] { "  jane@example.org \t" }).query().parameter()));
        check("a bare string is accepted too", kind("jane@example.org") == ErasureFind.Kind.EMAIL);
        check("digits only (3 to 9) make a booking reference", kind(new Object[] { "1234" }) == ErasureFind.Kind.REF
            && Integer.valueOf(1234).equals(ErasureFind.classify(new Object[] { "1234" }).query().parameter()));
        check("nine digits are still a reference", kind(new Object[] { "123456789" }) == ErasureFind.Kind.REF);
        check("ten digits are one word: refused as a name", refused(new Object[] { "1234567890" }));
        check("two words make a name", kind(new Object[] { "Jane Doe" }) == ErasureFind.Kind.NAME);
        check("a hyphenated name counts its parts", kind(new Object[] { "Jean-Luc" }) == ErasureFind.Kind.NAME);
        check("one word is refused", refused(new Object[] { "Jane" }));
        check("a word and a number is one word (abc_names drops numbers)", refused(new Object[] { "Jane 42" }));
        check("names with commas and # count as abc_names counts", ErasureFind.nameWords("Doe, Jane #2") == 2);
        check("a separator at either end is stripped (abc_names would keep an empty word)",
            "Doe, Jane".equals(ErasureFind.classify(new Object[] { "Doe, Jane #" }).query().parameter())
            && "Jane Doe".equals(ErasureFind.classify(new Object[] { " -Jane Doe- " }).query().parameter())
            && "Jean-Luc Doe".equals(ErasureFind.classify(new Object[] { "Jean-Luc Doe" }).query().parameter()));
        check("separators around one word are still one word", refused(new Object[] { "#, Jane -" }));
        check("fewer than 3 characters refused", refused(new Object[] { "ab" }) && refused(new Object[] { "a@" }));
        check("a short booking reference is a reference", !refused(new Object[] { "7" })
            && ErasureFind.classify(new Object[] { "12" }).query().kind() == ErasureFind.Kind.REF);
        check("reference 0 is not one", refused(new Object[] { "0" }));
        check("an empty or blank text refused", refused(new Object[] { "" }) && refused(new Object[] { "    " }));
        check("201 characters refused", refused(new Object[] { "a b" + "c".repeat(198) }));
        check("200 characters accepted", kind(new Object[] { "a b" + "c".repeat(197) }) == ErasureFind.Kind.NAME);
        check("a control character refused", refused(new Object[] { "Jane\u0000Doe x" }) && refused(new Object[] { "jane@x.org\n" + "y" }));
        check("not a string refused", refused(new Object[] { 1234 }) && refused(new Object[] { null }) && refused(null));
        check("two texts refused", refused(new Object[] { "Jane Doe", "John Doe" }));
        check("the refusal key is the contract's", "ErasureQueryError".equals(ErasureFind.QUERY_KEY));

        System.out.println("find: the SQL binds the text, and computes abc_names() once");
        for (ErasureFind.Kind k : ErasureFind.Kind.values()) {
            check(k.json + ": every statement binds $1", ErasureFind.customersSql(k, OWN).contains("$1")
                && ErasureFind.bookingsSql(k, OWN).contains("$1") && ErasureFind.hiddenSql(k, OWN).contains("$1"));
            check(k.json + ": both read one row more than they answer, customers before counting their bookings",
                ErasureFind.customersSql(k, OWN).contains("order by p0.id desc limit 51) p cross join lateral")
                && ErasureFind.bookingsSql(k, OWN).endsWith("limit 51"));
        }
        check("name: abc_names() in an uncorrelated scalar subquery only",
            ErasureFind.NAME_CUSTOMERS_SQL.contains("= (select public.abc_names($1::text))")
            && ErasureFind.NAME_BOOKINGS_SQL.contains("= (select public.abc_names($1::text))")
            && ErasureFind.NAME_BOOKINGS_SQL.split("abc_names\\(").length == 2);
        check("customers are never anonymised rows", ErasureFind.EMAIL_CUSTOMERS_SQL.contains("p0.anonymised_date is null"));
        for (ErasureFind.Kind k : ErasureFind.Kind.values())
            check(k.json + ": bookings number their booker as anonymise_booker's 'auto' rule (no account, address, two-word name)",
                ErasureFind.bookingsSql(k, OWN).contains("dense_rank() over (partition by (d.person_id is null and position('@' in coalesce(trim(d.person_email), '')) > 1"
                    + " and position(' ' in trim(coalesce(d.person_abc_names, ''))) > 0) order by lower(trim(d.person_email)), d.person_abc_names)"));
        check("bookings by email in the indexed form (lower(person_email), no trim on the column)",
            ErasureFind.EMAIL_BOOKINGS_SQL.contains("lower(d.person_email) = lower(trim($1::text))")
            && !ErasureFind.EMAIL_BOOKINGS_SQL.substring(ErasureFind.EMAIL_BOOKINGS_SQL.lastIndexOf(" where ")).contains("trim(d.person_email)"));
    }

    // ===== the caller's organisations ==============================================================================

    private static void organisations() {
        System.out.println("find: only the caller's organisations (removing the filter fails these)");
        check("the ids are written as an int[] literal, sorted, de-duplicated, nulls left out",
            "'{151,187}'::int[]".equals(ErasureFind.intArray(List.of(187, 151, 187)))
            && "'{}'::int[]".equals(ErasureFind.intArray(List.of()))
            && "'{5}'::int[]".equals(ErasureFind.intArray(java.util.Arrays.asList(null, 5))));
        String covered = "coalesce(coalesce(e.organization_id, d.organization_id) = any('{151,187}'::int[]), false)";
        for (ErasureFind.Kind k : ErasureFind.Kind.values()) {
            String customers = ErasureFind.customersSql(k, OWN), bookings = ErasureFind.bookingsSql(k, OWN), hidden = ErasureFind.hiddenSql(k, OWN);
            check(k.json + ": no placeholder left", !customers.contains(ErasureFind.COVERED) && !bookings.contains(ErasureFind.COVERED)
                && !hidden.contains(ErasureFind.COVERED));
            check(k.json + ": bookings only of the caller's organisations, before the limit",
                bookings.contains(" where " + covered + " and ") && bookings.indexOf(covered) < bookings.indexOf("limit 51"));
            check(k.json + ": a booking carries its organisation (the event's, else its own)",
                bookings.contains(", coalesce(e.organization_id, d.organization_id), o.name from document d"));
            check(k.json + ": customers only with a record here, or none anywhere, before the limit",
                customers.contains("join f on f.id = p0.id and (f.here or not f.anywhere) order by p0.id desc limit 51"));
            check(k.json + ": 'here' is a booking or an application in the caller's organisations",
                customers.contains(" f as materialized (select p0.id, " + ErasureFind.recordsHere("p0.id", "'{151,187}'::int[]") + " as here")
                && ErasureFind.recordsHere("p0.id", "x").contains("and " + covered.replace("'{151,187}'::int[]", "x") + ")")
                && ErasureFind.recordsHere("p0.id", "x").contains("va.person_id in (select p0.id union all select lc.id from person lc where lc.account_person_id = p0.id)"
                    + " and coalesce(va.organization_id = any(x), false)"));
            check(k.json + ": counts here and in all, the last event start of those here only",
                customers.contains("count(*) filter (where " + covered + ") as here")
                && customers.contains("max(e.start_date) filter (where " + covered + ") as last_start"));
            check(k.json + ": hiddenElsewhere = a customer with records elsewhere only, or a booking elsewhere",
                hidden.contains("select exists (select 1 from f where f.anywhere and not f.here) or exists (select 1 from document d")
                && hidden.contains("where not " + covered + " and "));
        }
        check("a DPO of nothing finds only people with no record",
            ErasureFind.bookingsSql(ErasureFind.Kind.REF, Set.of()).contains("= any('{}'::int[]), false) and "));
        check("bookingsElsewhere = all minus here", ErasureFind.elsewhere(5L, 3L).longValue() == 2 && ErasureFind.elsewhere(null, 3L) == null);

        System.out.println("check: the caller's organisations only (removing the filter fails these)");
        check("bookings: the caller's organisations; the others apart, as keptElsewhere",
            ErasureResidueCheck.BOOKINGS_SQL.contains(") and coalesce(coalesce((select e.organization_id from event e where e.id = d.event_id), d.organization_id) = any(q.covered), false)")
            && ErasureResidueCheck.KEPT_ELSEWHERE_SQL.contains(") and not coalesce(coalesce((select e.organization_id from event e where e.id = d.event_id), d.organization_id) = any(q.covered), false)"));
        check("...both over the same match", matchOf(ErasureResidueCheck.BOOKINGS_SQL) != null
            && matchOf(ErasureResidueCheck.BOOKINGS_SQL).equals(matchOf(ErasureResidueCheck.KEPT_ELSEWHERE_SQL)));
        check("emails: not those of another organisation's booking's mails",
            ErasureResidueCheck.EMAILS_SQL.contains("and not exists (select 1 from mail m where m.id = r.mail_id and exists (select 1 from document d"));
        check("history: not that of another organisation's booking",
            ErasureResidueCheck.HISTORY_SQL.contains("and not exists (select 1 from document d left join event e on e.id = d.event_id where d.id = h.document_id and not "));
        check("volunteering: the caller's organisations' applications",
            ErasureResidueCheck.VOLUNTEERING_SQL.endsWith("and coalesce(va.organization_id = any(q.covered), false) limit 100) t"));
        check("customers, logins and magic links belong to no organisation: unfiltered",
            !ErasureResidueCheck.CUSTOMERS_SQL.contains("q.covered)") && !ErasureResidueCheck.LOGINS_SQL.contains("q.covered)")
            && !ErasureResidueCheck.MAGIC_LINKS_SQL.contains("q.covered)"));
        check("a booking read by id carries its organisation", ErasureResidueCheck.DOCUMENTS_SQL.contains("coalesce(e.organization_id, d.organization_id), o.name"));

        System.out.println("check: an id is no key to another organisation's people or bookings (B1; removing the rule fails these)");
        check("people: the subject, or somebody the caller could find (a record here, or none anywhere, linked copies counted)",
            ErasureResidueCheck.PEOPLE_SQL.contains("where p.id = any($1::int[]) and (p.id = $2::int or " + ErasureFind.recordsHere("p.id", "$3::int[]")
                + " or not " + ErasureFind.recordsAnywhere("p.id") + ")"));
        var foreign = parsed(ErasureResidueCheck.bookingJson(4711, 1234, "Jane", "Doe", "jane@x.org", true, 12000, true, 1, "NKT-IKBU", false));
        check("another organisation's booking: id, ref, organisation, covered only", foreign.getInteger("documentId") == 4711
            && foreign.getInteger("ref") == 1234 && foreign.getInteger("organizationId") == 1 && "NKT-IKBU".equals(foreign.getString("organizationName"))
            && !foreign.isTrue("covered") && foreign.get("firstName") == null && foreign.get("lastName") == null && foreign.get("email") == null
            && foreign.get("priceNet") == null && !foreign.isTrue("hasPhone") && !foreign.isTrue("cancelled"));
        var own = parsed(ErasureResidueCheck.bookingJson(4712, 1235, "Jane", "Doe", "jane@x.org", true, 12000, true, 151, "Manjushri", true));
        check("...a covered one as it is", "Jane".equals(own.getString("firstName")) && own.getInteger("priceNet") == 12000 && own.isTrue("hasPhone"));

        System.out.println("find: linked copies' records count as the customer's (S2)");
        String customers = ErasureFind.customersSql(ErasureFind.Kind.EMAIL, OWN);
        check("here and anywhere over the person and their linked copies", customers.contains(ErasureFind.recordsHere("p0.id", "'{151,187}'::int[]"))
            && customers.contains(ErasureFind.recordsAnywhere("p0.id"))
            && ErasureFind.recordsAnywhere("p0.id").contains("d.person_id in (select p0.id union all select lc.id from person lc where lc.account_person_id = p0.id)"));
        check("...and the counts", customers.contains("where d.person_id in (select p.id union all select lc.id from person lc where lc.account_person_id = p.id)) b"));
    }

    /** A slice of a main source file, read from the module directory (the checks run from there). */
    private static String source(String file, String from, String to) {
        try {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/one/modality/event/server/lifecycle", file));
            int start = text.indexOf(from), end = text.indexOf(to, Math.max(start, 0));
            return start < 0 || end < 0 ? "" : text.substring(start, end);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** The WHERE clause of a capped area statement up to its organisation filter, or null without one. */
    private static String matchOf(String sql) {
        int from = sql.indexOf(" where "), to = sql.indexOf(") and ");
        return from < 0 || to < from ? null : sql.substring(from, to);
    }

    // ===== approvals ===============================================================================================

    private static ErasureApprovals.Row row(int org, String state, boolean expired, Integer askedBy) {
        return new ErasureApprovals.Row(42, org, "Org " + org, state, askedBy, "2026-11-19T10:00:00Z", "2026-12-03T10:00:00Z",
            null, null, expired, PersonAnonymisationRunner.Subject.person(7), false);
    }

    private static void approvals() {
        System.out.println("approvals: who is asked (removing the coverage rule fails these)");
        check("every organisation involved or touched that the caller does not cover",
            ErasureApprovals.organisationsToAsk(Set.of(1, 151, 187), Set.of(151), Set.of(151)).equals(Set.of(1, 187)));
        check("all covered: nothing to ask (the page shows no choice)", ErasureApprovals.organisationsToAsk(Set.of(1, 151), Set.of(1), Set.of(1, 151)).isEmpty());
        check("none of the subject's own covered: may not ask at all", ErasureApprovals.organisationsToAsk(Set.of(1), Set.of(1), Set.of(151)) == null);
        check("...covering only a booking they paid for is not enough (R4)", ErasureApprovals.organisationsToAsk(Set.of(1, 151), Set.of(1), Set.of(151)) == null);
        check("no records anywhere: nothing to ask", ErasureApprovals.organisationsToAsk(Set.of(), Set.of(), Set.of()).isEmpty());

        System.out.println("approvals: who decides (fresh coverage)");
        List<ErasureApprovals.Row> proposal = List.of(row(1, "pending", false, 3), row(187, "pending", false, 3), row(5, "pending", true, 3));
        check("a DPO decides the pending rows of the organisations they cover",
            ErasureApprovals.decidable(proposal, Set.of(1, 151)).equals(Set.of(1)));
        check("...all of theirs at once", ErasureApprovals.decidable(proposal, Set.of(1, 187)).equals(Set.of(1, 187)));
        check("...never another organisation's", ErasureApprovals.decidable(proposal, Set.of(151)).isEmpty());
        check("...nor an expired row", ErasureApprovals.decidable(proposal, Set.of(5)).isEmpty());
        check("...nor a decided one", ErasureApprovals.decidable(List.of(row(1, "approved", false, 3), row(1, "declined", false, 3)), Set.of(1)).isEmpty());

        System.out.println("approvals: the state");
        check("complete = every row approved and unexpired", ErasureApprovals.complete(List.of(row(1, "approved", false, 3), row(187, "approved", false, 3))));
        check("one pending: not complete", !ErasureApprovals.complete(List.of(row(1, "approved", false, 3), row(187, "pending", false, 3))));
        check("an approval past its expiry: not complete, shown expired", !ErasureApprovals.complete(List.of(row(1, "approved", true, 3)))
            && "expired".equals(row(1, "approved", true, 3).shownState()) && "expired".equals(row(1, "pending", true, 3).shownState()));
        check("a declined or withdrawn row keeps its state", "declined".equals(row(1, "declined", true, 3).shownState())
            && "withdrawn".equals(row(1, "withdrawn", false, 3).shownState()));
        check("no row: not complete", !ErasureApprovals.complete(List.of()));

        System.out.println("approvals: the lists");
        check("waiting: a pending row in an organisation the caller covers, asked by somebody else",
            ErasureApprovals.waitingFor(proposal, 9, Set.of(187)) && !ErasureApprovals.waitingFor(proposal, 9, Set.of(151))
            && !ErasureApprovals.waitingFor(proposal, 3, Set.of(187)) && !ErasureApprovals.waitingFor(proposal, 9, Set.of(5)));
        check("mine: the caller's, expired ones included (shown expired, so the asker sees them lapse)",
            ErasureApprovals.mine(proposal, 3) && !ErasureApprovals.mine(proposal, 9)
            && ErasureApprovals.mine(List.of(row(1, "pending", true, 3)), 3)
            && "expired".equals(row(1, "pending", true, 3).shownState()));
        check("...but an expired row is never waiting for anybody", !ErasureApprovals.waitingFor(List.of(row(187, "pending", true, 3)), 9, Set.of(187)));
        check("a declined proposal stays in mine while unexpired (the asker sees the decline)",
            ErasureApprovals.mine(List.of(row(1, "declined", false, 3)), 3));
        List<ErasureApprovals.Row> withdrawn = List.of(row(1, "withdrawn", false, 3), row(187, "approved", false, 3));
        check("withdrawn or committed: in neither list", !ErasureApprovals.mine(withdrawn, 3) && !ErasureApprovals.waitingFor(withdrawn, 9, Set.of(187))
            && !ErasureApprovals.mine(List.of(new ErasureApprovals.Row(42, 1, "x", "pending", 3, null, null, null, null, false,
                PersonAnonymisationRunner.Subject.person(7), true)), 3));

        System.out.println("approvals: the SQL");
        check("a row expires after 14 days", ErasureApprovals.ASK_SQL.contains("now() + interval '14 days'") && "14 days".equals(ErasureApprovals.VALIDITY));
        check("asking again adds only the organisations not asked yet",
            ErasureApprovals.ASK_SQL.endsWith("on conflict (run_id, organization_id) do nothing"));
        check("a decision writes pending, unexpired rows of the given organisations only",
            ErasureApprovals.DECIDE_SQL.contains("organization_id = any(" + ErasureFind.COVERED + ")")
            && ErasureApprovals.DECIDE_SQL.contains("state = 'pending' and expires_at > now()"));
        check("a withdrawal: the asker's rows not final yet, pending AND approved (S3)",
            ErasureApprovals.WITHDRAW_SQL.contains("asked_by_person_id = $2::int and state in ('pending', 'approved')"));
        check("withdrawn or declined: the proposal has ended (no decision, no commit)",
            ErasureApprovals.ended(List.of(row(1, "approved", false, 3), row(187, "withdrawn", false, 3)))
            && ErasureApprovals.ended(List.of(row(1, "approved", false, 3), row(187, "declined", false, 3)))
            && !ErasureApprovals.ended(List.of(row(1, "approved", false, 3), row(187, "pending", true, 3)))
            && "ErasureApprovalWithdrawnError".equals(ErasureApprovals.WITHDRAWN_KEY));
        String decideSource = source("ErasureApprovals.java", "    static Future<String> decide(", "    private static Future<Void> stillTheSame(");
        check("decide refuses the asker, and an ended proposal (C1, S3)",
            decideSource.contains("rows.stream().anyMatch(row -> Objects.equals(row.askedBy(), caller.personId()))")
            && decideSource.contains("if (ended(rows))\n                    return Future.<String>failedFuture(framed(WITHDRAWN_KEY"));
        String sameSource = source("ErasureApprovals.java", "    private static Future<Void> stillTheSame(", "    // ===== Withdraw");
        check("a busy re-run is not 'stale' (R10)", sameSource.contains("if (PersonAnonymisationRunner.BUSY_KEY.equals(key))\n                                return Future.failedFuture(why);"));
        Future<String> busy = DpoErasure.failure(new RuntimeException("[PersonAnonymiseBusyError] Another anonymisation of this person is running"), "x", 5, "y");
        check("...and reaches the page as the busy key", busy.failed() && busy.cause().getMessage().startsWith("[PersonAnonymiseBusyError] "));
        String forCaller = ErasureApprovals.openRowsSql(List.of(187, 151), 9);
        check("the list keeps, in SQL and before its limit, the caller's organisations' proposals and their own (C5)",
            forCaller.contains("and (x.organization_id = any('{151,187}'::int[]) or x.asked_by_person_id = 9))")
            && forCaller.indexOf("x.asked_by_person_id = 9") < forCaller.indexOf("limit 2000") && !forCaller.contains("{{"));
        check("the table holds ids, dates and states: nothing else is written",
            !ErasureApprovals.ASK_SQL.contains("reason") && !ErasureApprovals.DECIDE_SQL.contains("reason"));
        check("the list reads open proposals, and expired ones for 7 days more",
            ErasureApprovals.OPEN_ROWS_SQL.contains("x.expires_at > now() - interval '7 days'") && "7 days".equals(ErasureApprovals.EXPIRED_SHOWN_FOR));
        check("the keys are the contract's", "ErasureApprovalNotNeededError".equals(ErasureApprovals.NOT_NEEDED_KEY)
            && "ErasureApprovalStaleError".equals(ErasureApprovals.STALE_KEY) && "ErasureApprovalNotFoundError".equals(ErasureApprovals.NOT_FOUND_KEY)
            && "ErasureApprovalExpiredError".equals(ErasureApprovals.EXPIRED_KEY));

        System.out.println("approvals: the replies");
        ReadOnlyAstObject state = parsed(ErasureApprovals.stateJson(42, List.of(
            new ErasureApprovals.Row(42, 1, "NKT-IKBU", "approved", 3, "2026-11-19T10:00:00Z", "2026-12-03T10:00:00Z", 5,
                "2026-11-19T11:00:00Z", false, PersonAnonymisationRunner.Subject.person(7), false),
            row(187, "pending", true, 3)), java.util.Map.of(5L, "Pat \"Officer\"")));
        ReadOnlyAstObject first = state.getArray("organizations").getObject(0);
        check("state: the contract's keys", state.getInteger("proposalRunId") == 42 && !state.isTrue("complete")
            && state.getArray("organizations").size() == 2 && first.getInteger("id") == 1 && "NKT-IKBU".equals(first.getString("name"))
            && "approved".equals(first.getString("state")) && first.getInteger("decidedByPersonId") == 5
            && "Pat \"Officer\"".equals(first.getString("decidedByName")) && "2026-11-19T11:00:00Z".equals(first.getString("decidedAt"))
            && first.keys().size() == 6);
        check("an expired pending row reads expired, with no decider",
            "expired".equals(state.getArray("organizations").getObject(1).getString("state"))
            && state.getArray("organizations").getObject(1).get("decidedByName") == null);
        ReadOnlyAstObject proposalJson = parsed(ErasureApprovals.proposalJson(List.of(row(1, "pending", false, 3)),
            java.util.Map.of(3L, "Sam Asker"), "Jane Doe"));
        check("a proposal: the contract's keys", proposalJson.getInteger("proposalRunId") == 42
            && "person".equals(proposalJson.getObject("subject").getString("kind")) && proposalJson.getObject("subject").getInteger("id") == 7
            && "Jane Doe".equals(proposalJson.getString("subjectName")) && proposalJson.getInteger("askedByPersonId") == 3
            && "Sam Asker".equals(proposalJson.getString("askedByName")) && "2026-11-19T10:00:00Z".equals(proposalJson.getString("askedAt"))
            && "2026-12-03T10:00:00Z".equals(proposalJson.getString("expiresAt")) && proposalJson.getObject("approvals") != null
            && proposalJson.getArray("candidates").size() == 0 && proposalJson.getArray("members").size() == 0
            && proposalJson.keys().size() == 10);
        ReadOnlyAstObject ticked = parsed(ErasureApprovals.proposalJson(List.of(new ErasureApprovals.Row(42, 1, "x", "pending", 3,
                "2026-11-19T10:00:00Z", "2026-12-03T10:00:00Z", null, null, false, PersonAnonymisationRunner.Subject.person(7), false,
                "905,12", "8")), java.util.Map.of(), "Jane Doe"));
        check("a proposal carries its ticks (ids, ascending): the approver re-previews with exactly these",
            ticked.getArray("candidates").size() == 2 && ticked.getArray("candidates").getInteger(0) == 12
            && ticked.getArray("candidates").getInteger(1) == 905 && ticked.getArray("members").getInteger(0) == 8);
        check("the reading SQL reads the proposal run's ticks", ErasureApprovals.ROWS_SQL.contains("array_to_string(r.candidate_document_ids, ',')")
            && ErasureApprovals.ROWS_SQL.contains("array_to_string(r.member_ids, ',')"));

        System.out.println("refusals before any database work");
        Future<String> findAnswer = ErasureFind.find(new Object[] { "Jane" }, new EventErasureAccess.Caller(5));
        check("find: [ErasureQueryError] text", findAnswer.failed() && findAnswer.cause().getMessage().startsWith("[ErasureQueryError] "));
        Future<String> checkAnswer = ErasureResidueCheck.check(new Object[0], new EventErasureAccess.Caller(5));
        check("check: [ErasureCheckError] text", checkAnswer.failed() && checkAnswer.cause().getMessage().startsWith("[ErasureCheckError] "));
        Future<String> ask = ErasureApprovals.ask(new Object[] { "x" }, new EventErasureAccess.Caller(5));
        check("ask: no proposal id", ask.failed() && ask.cause().getMessage().startsWith("[ErasureApprovalNotFoundError] "));
        Future<String> decide = ErasureApprovals.decide(new Object[] { 42, "maybe" }, new EventErasureAccess.Caller(5));
        check("decide: neither approve nor decline", decide.failed() && decide.cause().getMessage().startsWith("[ErasureApprovalNotFoundError] "));
        Future<String> withdraw = ErasureApprovals.withdraw(new Object[0], new EventErasureAccess.Caller(5));
        check("withdraw: no proposal id", withdraw.failed() && withdraw.cause().getMessage().startsWith("[ErasureApprovalNotFoundError] "));
    }

    // ===== check ==================================================================================================

    private static ErasureResidueCheck.Identifiers ids(Object argument) {
        return ErasureResidueCheck.parse(argument).identifiers();
    }

    private static boolean checkRefused(Object argument) {
        ErasureResidueCheck.Parsed p = ErasureResidueCheck.parse(argument);
        return p.identifiers() == null && p.refusal() != null;
    }

    private static Object[] many(int n, java.util.function.IntFunction<Object> entry) {
        Object[] a = new Object[n];
        for (int i = 0; i < n; i++)
            a[i] = entry.apply(i);
        return a;
    }

    private static void residueCheck() {
        System.out.println("check: phones on their last 9 digits");
        check("+44 7700 900123 and 07700 900123 meet", "700900123".equals(ErasureResidueCheck.phoneKey("+44 7700 900123"))
            && "700900123".equals(ErasureResidueCheck.phoneKey("07700 900123")));
        check("punctuation is not a digit", "123456789".equals(ErasureResidueCheck.phoneKey("(12) 345-678.9")));
        check("fewer than 9 digits: no key", ErasureResidueCheck.phoneKey("12 34 56 78") == null && ErasureResidueCheck.phoneKey("") == null);

        System.out.println("check: the argument");
        ErasureResidueCheck.Identifiers all = ids(new Object[] {
            new Object[] { " Jane@Example.org ", "jane@example.org", "j.doe@example.org" },
            new Object[] { "+44 7700 900123", "07700 900123", "1234" },
            new Object[] { 12, 12L, "13" },
            new Object[] { 4711.0 } });
        check("emails trimmed and de-duplicated as the database compares them", all.emails().equals(List.of("Jane@Example.org", "j.doe@example.org")));
        check("phones normalised, de-duplicated, the short one ignored", all.phones().equals(List.of("700900123")));
        check("person ids of any numeric type, de-duplicated", all.personIds().equals(List.of(12, 13)));
        check("booking ids", all.documentIds().equals(List.of(4711)));
        check("absent and null lists are empty", ids(new Object[] { new Object[] { "a@b.c" } }).phones().isEmpty()
            && ids(new Object[] { null, null, new Object[] { 5 } }).personIds().equals(List.of(5)));
        check("every list empty: refused", checkRefused(new Object[] { new Object[0], new Object[0], new Object[0], new Object[0] })
            && checkRefused(new Object[0]) && checkRefused(null));
        check("only a short phone: nothing to check, refused", checkRefused(new Object[] { null, new Object[] { "12345" } }));
        check("10 entries accepted", ids(new Object[] { many(10, i -> "p" + i + "@x.org") }) != null);
        check("11 entries refused, in any list", checkRefused(new Object[] { many(11, i -> "p" + i + "@x.org") })
            && checkRefused(new Object[] { null, many(11, i -> "0770090012" + i) })
            && checkRefused(new Object[] { null, null, many(11, i -> i + 1) })
            && checkRefused(new Object[] { null, null, null, many(11, i -> i + 1) }));
        check("an email without @ refused", checkRefused(new Object[] { new Object[] { "jane.example.org" } }));
        check("an email too short, too long or with a control character refused",
            checkRefused(new Object[] { new Object[] { "@x" } })
            && checkRefused(new Object[] { new Object[] { "a@" + "b".repeat(199) } })
            && checkRefused(new Object[] { new Object[] { "a@b.c\r\nBcc: x" } }));
        check("an email that is not a string refused", checkRefused(new Object[] { new Object[] { 42 } })
            && checkRefused(new Object[] { new Object[] { null } }));
        check("a phone that is not a string, too long, or with a control character refused",
            checkRefused(new Object[] { null, new Object[] { 770090012 } })
            && checkRefused(new Object[] { null, new Object[] { "1".repeat(51) } })
            && checkRefused(new Object[] { null, new Object[] { "07700\u0000900123" } }));
        check("a malformed id refused", checkRefused(new Object[] { null, null, new Object[] { -1 } })
            && checkRefused(new Object[] { null, null, null, new Object[] { 1.5 } })
            && checkRefused(new Object[] { null, null, null, new Object[] { "abc" } }));
        check("a list that is not an array refused", checkRefused(new Object[] { "jane@example.org" }));
        check("a fifth argument that is not a subject refused", checkRefused(new Object[] { null, null, null, new Object[] { 1 }, new Object[0] })
            && checkRefused(new Object[] { new Object[] { "a@b.c" }, null, null, null, 7 })
            && checkRefused(new Object[] { new Object[] { "a@b.c" }, null, null, null, new Object[] { "person", 0 } })
            && checkRefused(new Object[] { new Object[] { "a@b.c" }, null, null, null, new Object[] { "request", 7 } })
            && checkRefused(new Object[] { new Object[] { "a@b.c" }, null, null, null, new Object[] { "person", 7, 8 } }));
        check("a sixth argument refused", checkRefused(new Object[] { new Object[] { "a@b.c" }, null, null, null, null, null }));
        PersonAnonymisationRunner.Subject person = ErasureResidueCheck.parse(new Object[] { new Object[] { "a@b.c" }, null, null, null,
            new Object[] { "person", 7.0 } }).subject();
        PersonAnonymisationRunner.Subject booker = ErasureResidueCheck.parse(new Object[] { new Object[] { "a@b.c" }, null, null, null,
            new Object[] { "booker", "4711" } }).subject();
        check("the subject, optional: [\"person\", id] or [\"booker\", documentId]",
            person != null && !person.isBooker() && person.id() == 7 && booker != null && booker.isBooker() && booker.id() == 4711
            && ErasureResidueCheck.parse(new Object[] { new Object[] { "a@b.c" }, null, null, null, null }).subject() == null
            && ErasureResidueCheck.parse(new Object[] { new Object[] { "a@b.c" } }).subject() == null);
        check("a subject alone is nothing to check", checkRefused(new Object[] { null, null, null, null, new Object[] { "person", 7 } }));
        check("the refusal key is the contract's", "ErasureCheckError".equals(ErasureResidueCheck.CHECK_KEY));

        System.out.println("check: the areas");
        check("seven areas, in the contract's order", ErasureResidueCheck.AREAS.stream().map(ErasureResidueCheck.Area::name).toList()
            .equals(List.of("customers", "logins", "bookings", "emails", "history", "magicLinks", "volunteering")));
        ErasureResidueCheck.Identifiers phoneOnly = ids(new Object[] { null, new Object[] { "07700 900123" } });
        check("a phone alone concerns customers, bookings and volunteering only",
            ErasureResidueCheck.AREAS.stream().filter(a -> a.concerns(phoneOnly)).map(ErasureResidueCheck.Area::name).toList()
                .equals(List.of("customers", "bookings", "volunteering")));
        ErasureResidueCheck.Identifiers idsOnly = ids(new Object[] { null, null, new Object[] { 12 } });
        check("ids alone concern no area", ErasureResidueCheck.AREAS.stream().noneMatch(a -> a.concerns(idsOnly)));
        check("every area binds the three arrays and is capped at 100", ErasureResidueCheck.AREAS.stream().allMatch(a ->
            a.sql().contains("$1::text[]") && a.sql().contains("$2::text[]") && a.sql().contains("$3::int[] as covered")
            && a.sql().contains("limit 100")) && ErasureResidueCheck.KEPT_ELSEWHERE_SQL.contains("$3::int[] as covered"));
        check("history matches the address and 'guest:' + its first 58 characters, built once in the CTE",
            ErasureResidueCheck.HISTORY_SQL.contains("'guest:' || left(x, 58) from unnest(e) x) as usernames")
            && ErasureResidueCheck.HISTORY_SQL.contains("where lower(trim(h.username)) = any(q.usernames) and ")
            && ErasureResidueCheck.HISTORY_SQL.endsWith("limit 100) t"));
        check("its connection is not named like an event erasure's (kbs-erasure-*)",
            ErasureResidueCheck.SETTINGS_SQL.contains("'kbs-dpo-check'") && !ErasureResidueCheck.SETTINGS_SQL.contains("kbs-erasure-"));

        System.out.println("check: the subject's erased bookings, by the tag the functions write");
        check("'#' + the person, '#B' + the seed booking", "#12".equals(ErasureResidueCheck.erasedTag(PersonAnonymisationRunner.Subject.person(12)))
            && "#B4711".equals(ErasureResidueCheck.erasedTag(PersonAnonymisationRunner.Subject.booker(4711))));
        check("found by that last name, bound, newest 50", ErasureResidueCheck.ERASED_BOOKINGS_SQL.contains("d.person_last_name = $1::text")
            && ErasureResidueCheck.ERASED_BOOKINGS_SQL.endsWith("limit 50") && !ErasureResidueCheck.ERASED_BOOKINGS_SQL.contains("erasure_request"));
        check("no stamping, no register: nothing in the check writes", !ErasureResidueCheck.class.getDeclaredFields().toString().contains("STAMP")
            && java.util.Arrays.stream(ErasureResidueCheck.class.getDeclaredFields()).noneMatch(f -> f.getName().contains("STAMP"))
            && java.util.Arrays.stream(ErasureResidueCheck.Findings.class.getDeclaredFields()).noneMatch(f -> f.getName().equals("recorded")));
        check("phones compared on the last 9 digits, as V0153",
            ErasureResidueCheck.CUSTOMERS_SQL.contains("right(regexp_replace(p.phone, '\\D', '', 'g'), 9) = any(q.phones)"));
        check("the check's transaction only reads, under a statement timeout",
            ErasureResidueCheck.BEGIN_SQL.endsWith("READ ONLY") && ErasureResidueCheck.SETTINGS_SQL.contains("statement_timeout"));
    }

    // ===== replies ================================================================================================

    private static ReadOnlyAstObject parsed(String json) {
        return AST.parseObject(json, "json");
    }

    private static QueryResult rows(Object[][] values) {
        QueryResultBuilder b = QueryResultBuilder.create(values.length, values.length == 0 ? 0 : values[0].length);
        for (int r = 0; r < values.length; r++)
            for (int c = 0; c < values[r].length; c++)
                b.setValue(r, c, values[r][c]);
        return b.build();
    }

    private static void replies() {
        System.out.println("find: the reply");
        Object[] customer = { 12, "Jane \"JD\"", "Doe\\", "jane@x.org", null, true, null, false, 4L, "2026-05-01", 3L };
        Object[] booking = { 4711, 1234, "Autumn retreat", "2026-10-05", "2026-10-12", "Jane", "Doe", null, "+44\n1", false, null, true, 3L, 151, "Manjushri KMC" };
        Object[][] customers = new Object[51][];
        for (int i = 0; i < 51; i++)
            customers[i] = customer;
        ReadOnlyAstObject find = parsed(ErasureFind.replyJson(ErasureFind.Kind.EMAIL, rows(customers), rows(new Object[][] { booking }), true));
        check("valid JSON, the contract's keys", "email".equals(find.getString("kind")) && find.getArray("customers") != null
            && find.getArray("bookings") != null && find.has("truncated") && find.isTrue("hiddenElsewhere"));
        check("51 rows read: 50 answered, truncated", find.getArray("customers").size() == 50 && find.isTrue("truncated"));
        ReadOnlyAstObject c = find.getArray("customers").getObject(0);
        check("a customer, escaped and typed", c.getInteger("personId") == 12 && "Jane \"JD\"".equals(c.getString("firstName"))
            && "Doe\\".equals(c.getString("lastName")) && c.get("phone") == null && c.isTrue("hasAccount")
            && c.get("linkedCopyOf") == null && !c.isTrue("removed") && c.getInteger("bookings") == 4
            && "2026-05-01".equals(c.getString("lastEventStart")) && c.getInteger("bookingsHere") == 3
            && c.getInteger("bookingsElsewhere") == 1 && c.keys().size() == 12);
        ReadOnlyAstObject b = find.getArray("bookings").getObject(0);
        check("a booking, escaped and typed", b.getInteger("documentId") == 4711 && b.getInteger("ref") == 1234
            && "Autumn retreat".equals(b.getString("eventName")) && "2026-10-12".equals(b.getString("eventEnd"))
            && b.get("email") == null && "+44\n1".equals(b.getString("phone")) && b.get("personId") == null
            && b.isTrue("anonymised") && !b.isTrue("cancelled") && b.getInteger("sameBooker") == 3
            && b.getInteger("organizationId") == 151 && "Manjushri KMC".equals(b.getString("organizationName")) && b.keys().size() == 15);
        ReadOnlyAstObject empty = parsed(ErasureFind.replyJson(ErasureFind.Kind.NAME, rows(new Object[0][]), rows(new Object[0][]), false));
        check("nothing found: empty lists, not truncated, nothing hidden", empty.getArray("customers").size() == 0
            && !empty.isTrue("truncated") && !empty.isTrue("hiddenElsewhere"));

        System.out.println("check: the reply");
        ErasureResidueCheck.Findings findings = new ErasureResidueCheck.Findings();
        findings.found.put("customers", 0L);
        findings.found.put("bookings", 250L);
        findings.people.add(ErasureResidueCheck.personJson(12, "Anonymised", "#12", null, true));
        findings.bookings.add(ErasureResidueCheck.bookingJson(4711, 1234, "Anonymised", "#B4711", null, false, 12000, false, 151, "Manjushri", true));
        findings.keptElsewhere = 3L;
        ReadOnlyAstObject residue = parsed(ErasureResidueCheck.replyJson(findings, Set.of(151)));
        ReadOnlyAstArray areas = residue.getArray("areas");
        check("only the areas searched, in the contract's order: one never looked at is left out, not answered 0",
            areas.size() == 2 && "customers".equals(areas.getObject(0).getString("area")) && areas.getObject(0).getInteger("found") == 0
            && "bookings".equals(areas.getObject(1).getString("area")));
        check("a count above 100 is 100", areas.getObject(1).getInteger("found") == 100);
        check("no 'recorded': the check stamps nothing", !residue.has("recorded"));
        check("other organisations' bookings: a number beside the areas, not an area (no failed verdict)",
            residue.getInteger("keptElsewhere") == 3 && areas.size() == 2);
        ErasureResidueCheck.Findings idsOnly = new ErasureResidueCheck.Findings();
        ReadOnlyAstObject byIds = parsed(ErasureResidueCheck.replyJson(idsOnly, Set.of()));
        check("a check by ids only answers no area at all, and no kept count", byIds.getArray("areas").size() == 0
            && byIds.has("keptElsewhere") && byIds.get("keptElsewhere") == null);
        ReadOnlyAstObject person = residue.getArray("people").getObject(0);
        check("a person as now", person.getInteger("personId") == 12 && person.get("email") == null && person.isTrue("removed")
            && person.keys().size() == 5);
        ReadOnlyAstObject doc = residue.getArray("bookings").getObject(0);
        check("a booking as now, with its organisation and whether it is the caller's", doc.getInteger("priceNet") == 12000
            && !doc.isTrue("hasPhone") && "#B4711".equals(doc.getString("lastName")) && doc.getInteger("organizationId") == 151
            && "Manjushri".equals(doc.getString("organizationName")) && doc.isTrue("covered") && doc.keys().size() == 11);
    }
}

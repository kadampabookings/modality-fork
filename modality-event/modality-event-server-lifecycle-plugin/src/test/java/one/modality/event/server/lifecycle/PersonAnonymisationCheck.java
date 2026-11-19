package one.modality.event.server.lifecycle;

import dev.webfx.platform.ast.AST;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check for {@link PersonAnonymisationRunner}'s argument rules, and for V0153's and V0163's refusal keys.
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

    private static final Path SCRIPTS = Path.of("../../modality-base/modality-base-server-dbmigration-plugin/src/main/resources"
        + "/one/modality/base/server/services/dbmigration/scripts");
    private static final Path V0153 = SCRIPTS.resolve("V0153__anonymise_person.sql");
    private static final Path V0163 = SCRIPTS.resolve("V0163__anonymise_booker.sql");
    /** Written by the SQL track in parallel: checked when present. */
    private static final Path V0175 = SCRIPTS.resolve("V0175__anonymise_by_organisation.sql");
    private static final Path RUNNER = Path.of("src/main/java/one/modality/event/server/lifecycle/PersonAnonymisationRunner.java");
    private static final Path ACCESS = Path.of("src/main/java/one/modality/event/server/lifecycle/EventErasureAccess.java");

    /** The back office's customers feature, when this module sits in the kbs3-aggregate checkout. */
    private static final Path BACK_OFFICE = Path.of("../../../kbs3-react/backoffice/src/features/customers");

    public static void main(String[] args) throws Exception {
        System.out.println("confirmPhrase");
        check("the phrase names the person", PersonAnonymisationRunner.Subject.person(42).confirmPhrase().equals("ANONYMISE PERSON 42"));
        check("a booker's phrase names the booking", PersonAnonymisationRunner.Subject.booker(4567).confirmPhrase().equals("ANONYMISE BOOKER 4567"));

        System.out.println("refusal keys the functions raise (V0175 adds the organisation refusal)");
        java.util.regex.Matcher dpoKey = PersonAnonymisationRunner.FUNCTION_KEY.matcher("[DpoNotYourOrganizationError] booking 9 belongs to an organisation this run does not cover");
        check("the organisation refusal is a refusal, with its key", dpoKey.find() && "DpoNotYourOrganizationError".equals(dpoKey.group(1)));
        java.util.regex.Matcher personKey = PersonAnonymisationRunner.FUNCTION_KEY.matcher("[PersonAnonymiseBalanceError] person 4 has bookings with money still owed");
        check("the function's own keys still are", personKey.find() && "PersonAnonymiseBalanceError".equals(personKey.group(1)));
        check("any other bracketed text is not", !PersonAnonymisationRunner.FUNCTION_KEY.matcher("[SomethingElseError] x").find());

        System.out.println("subjects (V0163: a booker's ledger key is minus the booking, so V0153's scrub reaches it)");
        check("a person keys the ledger by their id", PersonAnonymisationRunner.Subject.person(42).ledgerKey() == 42);
        check("a booker keys it by minus the booking", PersonAnonymisationRunner.Subject.booker(4567).ledgerKey() == -4567);
        check("the log names the subject by id", PersonAnonymisationRunner.Subject.booker(4567).noun().equals("booking 4567")
            && PersonAnonymisationRunner.Subject.person(42).noun().equals("person 42"));

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
        List<EventErasureRunner.GridRow> grid = List.of(
            new EventErasureRunner.GridRow("scope", "own_bookings", "2"),
            new EventErasureRunner.GridRow("candidate", "1003", "{}"),
            new EventErasureRunner.GridRow("member", "108", "{}"),
            new EventErasureRunner.GridRow("namesake", "7190", "{}"),
            new EventErasureRunner.GridRow("addressOwner", "7191", "{}"),
            new EventErasureRunner.GridRow("photo", "9301", "volunteers/vol-x.png"),
            new EventErasureRunner.GridRow("ticks", "candidates", "1003,1004"),
            new EventErasureRunner.GridRow("ticks", "members", null));
        check("the ledger keeps counts, never who was listed",
            PersonAnonymisationRunner.storedGrid(grid).stream().map(EventErasureRunner.GridRow::step).toList().equals(List.of("scope")));
        check("the effective candidate ticks are read back", Arrays.equals(PersonAnonymisationRunner.tickedIds(grid, "candidates"), new Integer[] { 1003, 1004 }));
        check("no member ticked = none", PersonAnonymisationRunner.tickedIds(grid, "members").length == 0);

        System.out.println("a booker commit finds what its preview showed (V0163)");
        List<EventErasureRunner.GridRow> shown = List.of(
            new EventErasureRunner.GridRow("booker", "named", "true"),
            new EventErasureRunner.GridRow("booker", "has_address", "true"),
            new EventErasureRunner.GridRow("booker", "address", "theirs"),
            new EventErasureRunner.GridRow("booker", "address_owners", "0"),
            new EventErasureRunner.GridRow("booker", "scope_digest", "d41d8cd9"),
            new EventErasureRunner.GridRow("scope", "guest_bookings_matched", "2"),
            new EventErasureRunner.GridRow("scope", "candidates", "3"),
            new EventErasureRunner.GridRow("scope", "address_candidates_unticked", "0"),
            new EventErasureRunner.GridRow("scope", "mails", "4"));
        List<EventErasureRunner.GridRow> later = new ArrayList<>(shown);
        later.set(8, new EventErasureRunner.GridRow("scope", "mails", "5"));
        check("the same facts vouch (other counts may move)", PersonAnonymisationRunner.describeBookerDrift(shown, later) == null);
        later.set(2, new EventErasureRunner.GridRow("booker", "address", "shared"));
        later.set(7, new EventErasureRunner.GridRow("scope", "address_candidates_unticked", "1"));
        String drift = PersonAnonymisationRunner.describeBookerDrift(shown, later);
        check("an address that stopped being theirs refuses, naming the facts",
            drift != null && drift.contains("booker/address theirs -> shared") && drift.contains("address_candidates_unticked 0 -> 1"));
        List<EventErasureRunner.GridRow> swapped = new ArrayList<>(shown);
        swapped.set(4, new EventErasureRunner.GridRow("booker", "scope_digest", "0cc175b9"));
        check("the same counts over other bookings refuse (the digest)", PersonAnonymisationRunner.describeBookerDrift(shown, swapped) != null);
        check("a preview without the facts never vouches", PersonAnonymisationRunner.describeBookerDrift(List.of(), shown) != null);
        check("nor a commit without them", PersonAnonymisationRunner.describeBookerDrift(shown, List.of()) != null);

        byOrganisation();

        Set<String> raised = new TreeSet<>();
        for (Path script : new Path[] { V0153, V0163 }) {
            System.out.println(script.getFileName() + " refusal keys");
            String sql = Files.readString(script);
            int headerEnd = sql.indexOf("-- ===== ");
            String header = sql.substring(0, headerEnd), body = sql.substring(headerEnd);
            Set<String> keys = new TreeSet<>();
            Matcher m = Pattern.compile("'\\[(PersonAnonymise[A-Za-z]+Error)\\]").matcher(body);
            while (m.find())
                keys.add(m.group(1));
            check("the function raises keys (" + keys.size() + ")", keys.size() >= 10);
            for (String key : keys)
                check(key + " is listed in the header", header.contains("--   " + key));
            raised.addAll(keys);
        }

        // The back office reads the customers a booker refusal names from 'person <id>' in its text (and V0153's scrub
        // of other runs matches the same form): a rewording would silently drop the "anonymise that customer" switch
        System.out.println("V0163 refusal wording the back office parses");
        String v0163 = Files.readString(V0163);
        check("HasAccount names 'person <id>'", v0163.contains("[PersonAnonymiseBookerHasAccountError] booking % belongs to person %"));
        check("IsCustomer lists 'person <id>' each", v0163.contains("string_agg('person ' || x.pid, ', ' ORDER BY x.pid)")
            && v0163.contains("[PersonAnonymiseBookerIsCustomerError] the booker of booking % has a customer record: %"));
        check("refused bookings are listed as '(refs ...)'", v0163.contains("(refs %)"));

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
                PersonAnonymisationRunner.PREVIEW_REQUIRED_KEY, PersonAnonymisationRunner.PREVIEW_CHANGED_KEY,
                PersonAnonymisationRunner.SCOPE_CHANGED_KEY, PersonAnonymisationRunner.APPROVAL_MISSING_KEY,
                PersonAnonymisationRunner.CANDIDATE_KEY,
                PersonAnonymisationRunner.MEMBER_KEY, PersonAnonymisationRunner.BUSY_KEY, PersonAnonymisationRunner.SQL_KEY,
                PersonAnonymisationRunner.CONFLICT_KEY));
            for (String key : sent)
                check(key + " is mapped by the back office", client.contains(key));
        }

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    private static EventErasureRunner.GridRow row(String step, String metric, String value) {
        return new EventErasureRunner.GridRow(step, metric, value);
    }

    /** Erasing by organisation (V0175/V0176, 19 Nov 2026): the runner's scope rules. */
    private static void byOrganisation() throws Exception {
        System.out.println("the scope the client chose");
        check("absent or null = mine", PersonAnonymisationRunner.SCOPE_MINE.equals(PersonAnonymisationRunner.scopeOf(null)));
        check("mine and everywhere", "mine".equals(PersonAnonymisationRunner.scopeOf("mine"))
            && "everywhere".equals(PersonAnonymisationRunner.scopeOf("everywhere")));
        check("anything else is malformed", PersonAnonymisationRunner.scopeOf("all") == null && PersonAnonymisationRunner.scopeOf(1) == null);
        check("the functions get the organisations as their new last argument",
            PersonAnonymisationRunner.ANONYMISE_PERSON_SQL.endsWith("anonymise_person($1, $2::int[], $3::int[], $4, $5::int[])")
            && PersonAnonymisationRunner.ANONYMISE_BOOKER_SQL.endsWith("anonymise_booker($1, $2::int[], $3, $4::int[])"));

        System.out.println("the ledger never keeps the kept bookings' ids (removing the strip fails this)");
        List<EventErasureRunner.GridRow> grid = List.of(row("scope", "organisations", "151"), row("kept", "document_other_organisation", "2"),
            row("kept", "organisations", "1"), row("kept", "document", "4711,4712"), row("updated", "document_detached", "2"),
            row("candidate", "1003", "{}"));
        List<EventErasureRunner.GridRow> stored = PersonAnonymisationRunner.storedGrid(grid);
        check("kept/document dropped, the counts and organisations kept", stored.stream().map(r -> r.step() + "/" + r.metric()).toList()
            .equals(List.of("scope/organisations", "kept/document_other_organisation", "kept/organisations", "updated/document_detached")));
        Map<Integer, Integer> organizationOf = new LinkedHashMap<>();
        organizationOf.put(10, 151); organizationOf.put(11, 151); organizationOf.put(4711, 1); organizationOf.put(4712, 1); organizationOf.put(99, null);
        Map<Integer, Long> applications = new LinkedHashMap<>();
        applications.put(187, 1L); applications.put(5, 0L);
        List<EventErasureRunner.GridRow> withOrganisations = PersonAnonymisationRunner.storedGrid(grid, Set.of(151, 187),
            Set.of(1, 151, 187, 200), organizationOf);
        check("the runner adds the organisations of the subject's own records, those involved, and the bookings of each (ids and counts)",
            "151,187".equals(EventErasureRunner.valueOf(withOrganisations, "organisations", "own"))
            && "1,151,187,200".equals(EventErasureRunner.valueOf(withOrganisations, "organisations", "involved"))
            && "-:1,1:2,151:2".equals(EventErasureRunner.valueOf(withOrganisations, "organisations", "bookings"))
            && withOrganisations.stream().noneMatch(r -> "kept".equals(r.step()) && "document".equals(r.metric())));

        System.out.println("a booking's fate");
        check("full = erased; contact, payer, carer as they are; kept", "erased".equals(PersonAnonymisationRunner.fateOf("full"))
            && "contact".equals(PersonAnonymisationRunner.fateOf("contact")) && "payer".equals(PersonAnonymisationRunner.fateOf("payer"))
            && "carer".equals(PersonAnonymisationRunner.fateOf("carer")) && "kept".equals(PersonAnonymisationRunner.fateOf("kept"))
            && PersonAnonymisationRunner.fateOf("other") == null && PersonAnonymisationRunner.fateOf(null) == null);
        check("the organisations of the records: the bookings' and the applications', none for no organisation",
            PersonAnonymisationRunner.involvedOrganizations(organizationOf, applications).equals(Set.of(1, 151, 187)));

        System.out.println("the subject's OWN records (the ownership gate's): their full bookings, kept or not, and applications");
        Map<Integer, String> ownFates = new LinkedHashMap<>();
        ownFates.put(10, "erased"); ownFates.put(11, "payer"); ownFates.put(4711, "kept"); ownFates.put(4712, "kept");
        Map<Integer, String> keptScopes = Map.of(4711, "full", 4712, "payer");
        Map<Integer, Integer> where = Map.of(10, 151, 11, 187, 4711, 1, 4712, 5);
        check("erased in full, kept-would-be-full, applications; not paid for, cared for or a contact",
            PersonAnonymisationRunner.ownOrganizations(ownFates, keptScopes, where, Map.of(300, 1L, 301, 0L)).equals(Set.of(1, 151, 300)));

        System.out.println("who may run it (removing the organisation rule, or judging it on everything involved, fails these)");
        String[] none = PersonAnonymisationRunner.scopeRefusal(false, false, Set.of(151), Set.of(1), Set.of(1), Set.of());
        check("own records, none in the caller's organisations: [DpoNotYourOrganizationError]",
            none != null && "DpoNotYourOrganizationError".equals(none[0]));
        check("...erasing everywhere too", PersonAnonymisationRunner.scopeRefusal(true, false, Set.of(151), Set.of(1), Set.of(1), Set.of()) != null
            && PersonAnonymisationRunner.scopeRefusal(true, true, Set.of(151), Set.of(1), Set.of(1), Set.of(1)) != null);
        check("a booking they only paid for in the caller's organisation does not make them the caller's",
            PersonAnonymisationRunner.scopeRefusal(false, false, Set.of(151), Set.of(1), Set.of(1, 151), Set.of()) != null);
        check("own records in the caller's, others elsewhere: goes on",
            PersonAnonymisationRunner.scopeRefusal(false, false, Set.of(151), Set.of(151), Set.of(1, 151), Set.of()) == null);
        check("no own record anywhere: any DPO", PersonAnonymisationRunner.scopeRefusal(false, true, Set.of(), Set.of(), Set.of(1), Set.of()) == null);
        check("an everywhere preview needs no approval yet", PersonAnonymisationRunner.scopeRefusal(true, false, Set.of(151), Set.of(151), Set.of(1, 151), Set.of()) == null);

        System.out.println("erasing everywhere: every other organisation approved (removing the coverage rule fails these)");
        String[] missing = PersonAnonymisationRunner.scopeRefusal(true, true, Set.of(151), Set.of(151), Set.of(1, 151, 187), Set.of(187));
        check("an organisation neither covered nor approved: refused, named by id",
            missing != null && "PersonAnonymiseApprovalMissingError".equals(missing[0]) && missing[1].startsWith("organisations 1 "));
        check("...an organisation only touched (a mate's line, a payer's booking) counts too",
            PersonAnonymisationRunner.scopeRefusal(true, true, Set.of(151), Set.of(151), Set.of(151, 200), Set.of()) != null);
        check("covered or approved, each: goes on", PersonAnonymisationRunner.scopeRefusal(true, true, Set.of(151), Set.of(151), Set.of(1, 151, 187), Set.of(1, 187)) == null);
        check("covering them all needs no approval", PersonAnonymisationRunner.scopeRefusal(true, true, Set.of(1, 151), Set.of(1), Set.of(1, 151), Set.of()) == null);
        check("approvals never stand in for covering one", PersonAnonymisationRunner.scopeRefusal(true, true, Set.of(), Set.of(1), Set.of(1, 151), Set.of(1, 151)) != null);

        System.out.println("a commit re-checks the covered organisations fresh (removing the re-check fails these)");
        List<EventErasureRunner.GridRow> mine = List.of(row("scope", "organisations", "151,187"));
        check("the same organisations: vouches", PersonAnonymisationRunner.describeScopeDrift(mine, "mine", Set.of(187, 151)) == null);
        check("...written with spaces: still", PersonAnonymisationRunner.describeScopeDrift(List.of(row("scope", "organisations", " 187, 151")), "mine", Set.of(151, 187)) == null);
        check("a role revoked since: refused", PersonAnonymisationRunner.describeScopeDrift(mine, "mine", Set.of(151)) != null);
        check("a role granted since: refused", PersonAnonymisationRunner.describeScopeDrift(mine, "mine", Set.of(1, 151, 187)) != null);
        check("a preview of the other scope: refused", PersonAnonymisationRunner.describeScopeDrift(mine, "everywhere", Set.of(151, 187)) != null
            && PersonAnonymisationRunner.describeScopeDrift(List.of(row("scope", "organisations", "all")), "mine", Set.of(151)) != null);
        check("an everywhere preview vouches for everywhere", PersonAnonymisationRunner.describeScopeDrift(List.of(row("scope", "organisations", "all")), "everywhere", Set.of(151)) == null);
        check("a preview without the row (before V0175) never vouches", PersonAnonymisationRunner.describeScopeDrift(List.of(), "mine", Set.of()) != null);
        check("nobody's organisations: '' vouches for none", PersonAnonymisationRunner.describeScopeDrift(List.of(row("scope", "organisations", "")), "mine", Set.of()) == null);
        String runner = Files.readString(RUNNER), access = Files.readString(ACCESS);
        String commit = runner.substring(runner.indexOf("    private static Future<String> commit(Subject subject"), runner.indexOf("    /** Ticked ids (bookings or members)"));
        check("the commit asks the covered organisations again, and compares them with its preview's",
            commit.contains("organizationsCovered(subject, candidates, members, caller)") && commit.contains("describeScopeDrift(baseline, scope, covered)"));
        check("the preview asks them too", runner.contains("return organizationsCovered(subject, candidates, members, caller).compose(covered ->\n            execute(new Request(subject, false"));
        String covered = runner.substring(runner.indexOf("    static Future<Set<Integer>> organizationsCovered("), runner.indexOf("    private static Future<String> commit(Subject subject"));
        String fresh = access.substring(access.indexOf("static Future<Set<Integer>> organizationsHeldFresh"), access.indexOf("private static Future<Boolean> holdsDataProtection"));
        check("covered = the subject's organisations, each asked of FRESH grants (R7)",
            covered.contains("compose(organizations -> EventErasureAccess.organizationsHeldFresh(caller, organizations))")
            && fresh.contains("answers.add(holdsIn(caller, id, true));") && !access.contains("static Future<Set<Integer>> organizationsCovered"));
        String personOrganizations = PersonAnonymisationRunner.personOrganizationsSql(7, List.of(8), List.of(1003));
        check("the subject's organisations: their bookings, linked copies, ticked members, paid or cared for, ticked candidates,"
                + " address-matched bookings, applications",
            personOrganizations.contains("(select 7 union select q.id from person q where q.account_person_id = 7 union select m from unnest('{8}'::int[]) m")
            && personOrganizations.contains("where d.person_id in (select * from s) union")
            && personOrganizations.contains("where d.id = any('{1003}'::int[]) union")
            // the indexed person link is never OR-ed with another lookup (that turns it into a scan of every booking)
            && !personOrganizations.contains("d.person_id in (select * from s) or")
            && personOrganizations.contains("d.payer_id in (select * from s) or d.person_carer1_id in (select * from s) or d.person_carer2_id in (select * from s)")
            && personOrganizations.contains("d.person_id is null and lower(d.person_email) in (select e from em)")
            && personOrganizations.contains("va.person_id in (select * from s)"));
        check("a booker's: the seed, ticked candidates, the bookings and applications under its address",
            PersonAnonymisationRunner.bookerOrganizationsSql(4711, List.of()).contains("where d.id = 4711 or d.id = any('{}'::int[])")
            && PersonAnonymisationRunner.bookerOrganizationsSql(4711, List.of()).contains("va.person_id is null and lower(trim(va.email)) in (select e from a)"));
        String checkPreview = runner.substring(runner.indexOf("    private static Future<String[]> checkPreview("), runner.indexOf("    /** The preview's stored grid, as rows"));
        check("a commit needs the caller's OWN preview (C3)", PersonAnonymisationRunner.PREVIEW_SQL.contains("p.requested_by_person_id")
            && checkPreview.contains("callerId.equals(EventErasureRunner.asInteger(preview.getValue(0, 7)))"));
        check("a proposal withdrawn or declined refuses the commit (S3)",
            PersonAnonymisationRunner.PROPOSAL_SQL.contains("a.state in ('withdrawn', 'declined')")
            && runner.contains("if (Boolean.TRUE.equals(p.getValue(0, 6)))\n                return Future.succeededFuture(new ProposalCheck(null, new String[] { ErasureApprovals.WITHDRAWN_KEY,"));
        check("a caller with no state covers nothing", EventErasureAccess.organizationsHeldFresh(new EventErasureAccess.Caller(5), Set.of(1, 151)).result().isEmpty()
            && EventErasureAccess.allOrganizationsHeld(new EventErasureAccess.Caller(5)).result().isEmpty());

        System.out.println("a proposal's facts (an approval and an everywhere commit compare them)");
        List<EventErasureRunner.GridRow> proposal = List.of(row("where", "run_at", "2026-11-19 10:00"), row("person", "id", "7"),
            row("scope", "own_bookings", "3"), row("scope", "mails", "12"), row("scope", "organisations", "all"),
            row("kept", "organisations", ""), row("organisations", "involved", "1,151"), row("organisations", "bookings", "1:1,151:2"),
            row("updated", "document", "3"));
        List<EventErasureRunner.GridRow> same = new ArrayList<>(proposal);
        same.set(0, row("where", "run_at", "2026-11-25 09:00"));
        same.set(3, row("scope", "mails", "13"));
        same.set(8, row("updated", "document", "4"));
        check("the same facts (a mail since, another moment, other write counts): vouches", PersonAnonymisationRunner.describeProposalDrift(proposal, same) == null);
        List<EventErasureRunner.GridRow> booked = new ArrayList<>(proposal);
        booked.set(2, row("scope", "own_bookings", "4"));
        check("a booking since: refused", PersonAnonymisationRunner.describeProposalDrift(proposal, booked) != null);
        List<EventErasureRunner.GridRow> moved = new ArrayList<>(proposal);
        moved.set(7, row("organisations", "bookings", "1:2,151:1"));
        check("the same count in other organisations: refused", PersonAnonymisationRunner.describeProposalDrift(proposal, moved) != null);
        List<EventErasureRunner.GridRow> another = new ArrayList<>(proposal);
        another.set(6, row("organisations", "involved", "1,151,187"));
        check("another organisation involved: refused", PersonAnonymisationRunner.describeProposalDrift(proposal, another) != null);
        List<EventErasureRunner.GridRow> extra = new ArrayList<>(proposal);
        extra.add(row("scope", "candidates", "1"));
        check("a fact on one side only: refused", PersonAnonymisationRunner.describeProposalDrift(proposal, extra) != null);
        check("a proposal with no facts never vouches", PersonAnonymisationRunner.describeProposalDrift(List.of(), proposal) != null);
        check("a proposal keeps its ticks while an approval of it is open",
            PersonAnonymisationRunner.EXPIRED_TICKS_SQL.contains("and not (exists (select 1 from person_erasure_approval a where a.run_id = r.id"
                + " and a.state in ('pending', 'approved') and a.expires_at > now())"));
        check("...but not once withdrawn, declined or committed (S4)",
            PersonAnonymisationRunner.EXPIRED_TICKS_SQL.contains("and not exists (select 1 from person_erasure_approval a where a.run_id = r.id and a.state in ('withdrawn', 'declined'))")
            && PersonAnonymisationRunner.EXPIRED_TICKS_SQL.contains("and not exists (select 1 from person_erasure_run c where c.person_id = r.person_id and c.mode = 'commit'"));

        System.out.println("the reply: who each candidate, member and namesake is, in the caller's organisations only (R2/S1)");
        var candidateHere = AST.parseObject(PersonAnonymisationRunner.candidateJson(1003, "Jane", "Doe", "j@x.org", "0770", false, "Manjushri", true), "json");
        var candidateElsewhere = AST.parseObject(PersonAnonymisationRunner.candidateJson(1004, "Jane", "Doe", "j@x.org", "0770", true, "NKT-IKBU", false), "json");
        check("a covered candidate is named; another organisation's is not", "Jane".equals(candidateHere.getString("firstName"))
            && candidateElsewhere.get("firstName") == null && candidateElsewhere.get("lastName") == null && candidateElsewhere.get("email") == null
            && candidateElsewhere.get("phone") == null && candidateElsewhere.isTrue("cancelled") && "NKT-IKBU".equals(candidateElsewhere.getString("organizationName")));
        check("members, namesakes, address owners: only people with a record here or none anywhere, linked copies counted",
            PersonAnonymisationRunner.PERSON_DETAILS_SQL.contains(" and (" + ErasureFind.recordsHere("q.id", "$2::int[]") + " or not " + ErasureFind.recordsAnywhere("q.id") + ")")
            && ErasureFind.recordsHere("q.id", "$2::int[]").contains("d.person_id in (select q.id union all select lc.id from person lc where lc.account_person_id = q.id)"));
        check("the candidates' organisation id is read to judge it", PersonAnonymisationRunner.CANDIDATE_DETAILS_SQL.contains("coalesce(e.organization_id, d.organization_id)"));

        System.out.println("the reply: who each booking is for, in the caller's organisations only");
        PersonAnonymisationRunner.BookingFacts facts = new PersonAnonymisationRunner.BookingFacts(10, 123, "Summer", "2025-07-01", "2025-07-05",
            151, "Manjushri KMC", false, false, true, "Jane", "Doe", "jane@x.org", "07700 900123");
        var erased = AST.parseObject(PersonAnonymisationRunner.bookingJson(facts, "erased", true), "json");
        check("a covered booking the run reaches: named, with its fate and state", "Jane".equals(erased.getString("firstName"))
            && "jane@x.org".equals(erased.getString("email")) && "erased".equals(erased.getString("fate")) && erased.isTrue("covered")
            && erased.isTrue("balance") && !erased.isTrue("upcoming") && erased.getInteger("organizationId") == 151 && erased.getInteger("ref") == 123
            && "2025-07-05".equals(erased.getString("eventEnd")) && erased.keys().size() == 16);
        var kept = AST.parseObject(PersonAnonymisationRunner.bookingJson(facts, "kept", false), "json");
        check("a kept booking: event, dates and state, no name or address", kept.get("firstName") == null && kept.get("lastName") == null
            && kept.get("email") == null && kept.get("phone") == null && "kept".equals(kept.getString("fate")) && "Summer".equals(kept.getString("eventName")));
        var elsewhere = AST.parseObject(PersonAnonymisationRunner.bookingJson(facts, "erased", false), "json");
        check("erasing everywhere, another organisation's booking: no name either", elsewhere.get("firstName") == null && elsewhere.get("email") == null
            && !elsewhere.isTrue("covered"));
        List<PersonAnonymisationRunner.BookingFacts> bookings = List.of(facts,
            new PersonAnonymisationRunner.BookingFacts(4711, 5, "Festival", "2026-12-01", "2026-12-05", 1, "NKT-IKBU", false, true, false, null, null, null, null),
            new PersonAnonymisationRunner.BookingFacts(4712, 6, "Retreat", "2024-01-01", "2024-01-02", 1, "NKT-IKBU", true, false, true, null, null, null, null));
        Map<Integer, String> fates = Map.of(10, "erased", 4711, "kept", 4712, "kept");
        List<String> keptOrganizations = PersonAnonymisationRunner.keptOrganizationsJson(false, Set.of(151), bookings, fates,
            Map.of(187, 2L, 151, 1L), Map.of(1, "NKT-IKBU", 187, "Glen Spey"));
        var nkt = AST.parseObject(keptOrganizations.get(0), "json");
        check("kept organisations: bookings, upcoming, balance, applications", keptOrganizations.size() == 2 && nkt.getInteger("id") == 1
            && "NKT-IKBU".equals(nkt.getString("name")) && nkt.getInteger("bookings") == 2 && nkt.getInteger("upcoming") == 1
            && nkt.getInteger("balance") == 1 && nkt.getInteger("applications") == 0 && nkt.keys().size() == 6);
        var glen = AST.parseObject(keptOrganizations.get(1), "json");
        check("...an organisation with kept applications only; a covered one's applications are not kept",
            glen.getInteger("id") == 187 && glen.getInteger("bookings") == 0 && glen.getInteger("applications") == 2);
        check("erasing everywhere keeps nothing", PersonAnonymisationRunner.keptOrganizationsJson(true, Set.of(151), bookings, fates,
            Map.of(187, 2L), Map.of()).isEmpty());
        List<String> others = PersonAnonymisationRunner.otherOrganizationsJson(Set.of(151), bookings,
            Map.of(187, 2L, 151, 1L), Map.of(1, "NKT-IKBU", 187, "Glen Spey"));
        var otherNkt = AST.parseObject(others.get(0), "json");
        var otherGlen = AST.parseObject(others.get(1), "json");
        check("other organisations (any scope): the uncovered ones, an application-only one included, never a covered one",
            others.size() == 2 && otherNkt.getInteger("id") == 1 && otherNkt.getInteger("bookings") == 2 && otherNkt.getInteger("upcoming") == 1
            && otherGlen.getInteger("id") == 187 && otherGlen.getInteger("bookings") == 0 && otherGlen.getInteger("applications") == 2
            && "Glen Spey".equals(otherGlen.getString("name")));
        check("...nothing when the caller covers them all", PersonAnonymisationRunner.otherOrganizationsJson(Set.of(1, 151, 187), bookings,
            Map.of(187, 2L), Map.of()).isEmpty());

        if (Files.exists(V0175)) {
            System.out.println("V0175 (the SQL track's), as the runner reads it");
            String v0175 = Files.readString(V0175);
            check("both functions take p_organization_ids last", v0175.contains("p_organization_ids integer[] DEFAULT NULL"));
            check("the kept ids row the runner reads and strips", v0175.contains("'kept'") && v0175.contains("'document'"));
            check("the scope row the commit compares", v0175.contains("'organisations'"));
            check("the applications the runner reads: ap_applications(id, organization_id) (R3)",
                v0175.contains("CREATE TEMP TABLE ap_applications (id integer") && v0175.contains("organization_id integer"));
            check("the organisations the run touches beyond the subject's bookings: organisations/touched (R9)",
                v0175.contains("'organisations'") && v0175.contains("'touched'"));
            check("the kept tables with their would-be scope (ap_kept, ab_kept)", v0175.contains("CREATE TEMP TABLE ap_kept (id integer PRIMARY KEY, scope text")
                && v0175.contains("CREATE TEMP TABLE ab_kept (id integer PRIMARY KEY, scope text"));
            check("the temp tables the runner reads (ap_docs, ab_docs, ab_applications)", v0175.contains("ap_docs (id integer PRIMARY KEY, scope text")
                && v0175.contains("ab_docs (id integer PRIMARY KEY, scope text") && v0175.contains("CREATE TEMP TABLE ab_applications")
                && v0175.contains("CREATE TEMP TABLE ab_kept_applications (id integer"));
            check("an everywhere run says 'all'; a mine run its ids, comma-separated",
                v0175.contains("('scope',  'organisations', coalesce(array_to_string(v_orgs, ','), 'all'))"));
        } else
            System.out.println("V0175 not written yet: its checks skipped");

        // V0175 (cost, 19 Nov 2026): one erasure read document whole about ten times — 73 s on staging, cold, for one
        // booking; document is 1.1 GB on production. Both functions now read it once, into a pool. A later migration
        // restating either function from an older body would bring the reads back without anybody noticing: whichever
        // script restates it LAST must keep the pools.
        System.out.println("the newest body of each function reads document once (V0175's pools)");
        String person = newestBody("anonymise_person"), booker = newestBody("anonymise_booker");
        check("anonymise_person reads document once (ap_doc_pool) and document_line once (ap_line_pool)",
            person.contains("CREATE TEMP TABLE ap_doc_pool AS") && person.contains("CREATE TEMP TABLE ap_line_pool AS"));
        check("anonymise_booker reads document once (ab_doc_pool)", booker.contains("CREATE TEMP TABLE ab_doc_pool AS"));
        check("neither counts the mail table whole (its own counters instead)",
            person.contains("pg_stat_xact_user_tables") && booker.contains("pg_stat_xact_user_tables"));
    }

    /** The text of the migration listed last in index.txt that restates public.&lt;name&gt;(. */
    private static String newestBody(String name) throws java.io.IOException {
        String newest = "";
        for (String line : Files.readAllLines(SCRIPTS.resolve("index.txt"))) {
            String file = line.trim();
            if (file.isEmpty() || file.startsWith("#") || !Files.exists(SCRIPTS.resolve(file)))
                continue;
            String sql = Files.readString(SCRIPTS.resolve(file));
            if (sql.contains("CREATE OR REPLACE FUNCTION public." + name + "("))
                newest = sql;
        }
        return newest;
    }
}

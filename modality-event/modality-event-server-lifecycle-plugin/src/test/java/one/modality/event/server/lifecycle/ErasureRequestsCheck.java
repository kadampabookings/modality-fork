package one.modality.event.server.lifecycle;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstArray;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryResultBuilder;
import dev.webfx.stack.db.submit.SubmitResult;

import java.time.LocalDate;
import java.util.List;

/**
 * Check for the "Erasure requests" endpoints' rules, without a database: what a search text is
 * ({@link ErasureFind#classify}), what a residue check accepts ({@link ErasureResidueCheck#parse}), what a saved request
 * must be ({@link ErasureRequests#parseSave}, {@link ErasureRequests#dateRefusal}), and the JSON the three write — read
 * back with the JSON parser, so every reply is checked to be JSON, with the contract's keys.
 *
 * <p>No test framework, as {@link PersonAnonymisationCheck}: runs from main() and exits non-zero on failure. The SQL is
 * rehearsed on a disposable copy of the database (every statement, with timings), not here.
 */
public class ErasureRequestsCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    public static void main(String[] args) {
        find();
        residueCheck();
        save();
        dates();
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
            check(k.json + ": both statements bind $1", ErasureFind.customersSql(k).contains("$1") && ErasureFind.bookingsSql(k).contains("$1"));
            check(k.json + ": both read one row more than they answer",
                ErasureFind.customersSql(k).endsWith("limit 51") && ErasureFind.bookingsSql(k).endsWith("limit 51"));
        }
        check("name: abc_names() in an uncorrelated scalar subquery only",
            ErasureFind.NAME_CUSTOMERS_SQL.contains("= (select public.abc_names($1::text))")
            && ErasureFind.NAME_BOOKINGS_SQL.contains("= (select public.abc_names($1::text))")
            && ErasureFind.NAME_BOOKINGS_SQL.split("abc_names\\(").length == 2);
        check("customers are never anonymised rows", ErasureFind.EMAIL_CUSTOMERS_SQL.contains("p.anonymised_date is null"));
        check("bookings by email in the indexed form (lower(person_email), no trim on the column)",
            ErasureFind.EMAIL_BOOKINGS_SQL.contains("lower(d.person_email) = lower(trim($1::text))")
            && !ErasureFind.EMAIL_BOOKINGS_SQL.contains("trim(d.person_email)"));
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
        check("a fifth argument that is not an id refused", checkRefused(new Object[] { null, null, null, new Object[] { 1 }, new Object[0] })
            && checkRefused(new Object[] { new Object[] { "a@b.c" }, null, null, null, 0 })
            && checkRefused(new Object[] { new Object[] { "a@b.c" }, null, null, null, "x" }));
        check("a sixth argument refused", checkRefused(new Object[] { new Object[] { "a@b.c" }, null, null, null, 7, null }));
        check("the request id, optional", Integer.valueOf(7).equals(ErasureResidueCheck.parse(new Object[] { new Object[] { "a@b.c" }, null, null, null, 7.0 }).requestId())
            && ErasureResidueCheck.parse(new Object[] { new Object[] { "a@b.c" }, null, null, null, null }).requestId() == null
            && ErasureResidueCheck.parse(new Object[] { new Object[] { "a@b.c" } }).requestId() == null);
        check("a request id alone is nothing to check", checkRefused(new Object[] { null, null, null, null, 7 }));
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
        check("every area binds both arrays and is capped at 100", ErasureResidueCheck.AREAS.stream().allMatch(a ->
            a.sql().contains("$1::text[]") && a.sql().contains("$2::text[]") && a.sql().contains("limit 100")));
        check("history matches the address and 'guest:' + its first 58 characters, built once in the CTE",
            ErasureResidueCheck.HISTORY_SQL.contains("'guest:' || left(x, 58) from unnest(e) x) as usernames")
            && ErasureResidueCheck.HISTORY_SQL.endsWith("where lower(trim(h.username)) = any(q.usernames) limit 100) t"));
        check("its connection is not named like an event erasure's (kbs-erasure-*)",
            ErasureResidueCheck.SETTINGS_SQL.contains("'kbs-dpo-check'") && !ErasureResidueCheck.SETTINGS_SQL.contains("kbs-erasure-"));

        System.out.println("check: what may be recorded as checked");
        ErasureResidueCheck.Identifiers byEmail = ids(new Object[] { new Object[] { "jane@example.org" } });
        ErasureResidueCheck.Findings clean = new ErasureResidueCheck.Findings();
        ErasureResidueCheck.AREAS.forEach(a -> clean.found.put(a.name(), 0L));
        check("every area searched and at 0: complete and clean", ErasureResidueCheck.completeAndClean(byEmail, clean));
        ErasureResidueCheck.Findings oneLeft = new ErasureResidueCheck.Findings();
        ErasureResidueCheck.AREAS.forEach(a -> oneLeft.found.put(a.name(), a.name().equals("history") ? 1L : 0L));
        check("one area not at 0: not clean", !ErasureResidueCheck.completeAndClean(byEmail, oneLeft));
        ErasureResidueCheck.Findings threeAreas = new ErasureResidueCheck.Findings();
        List.of("customers", "bookings", "volunteering").forEach(a -> threeAreas.found.put(a, 0L));
        check("a phone-only check (three areas): not complete",
            !ErasureResidueCheck.completeAndClean(ids(new Object[] { null, new Object[] { "07700 900123" } }), threeAreas));
        check("ids only: not complete", !ErasureResidueCheck.completeAndClean(idsOnly, new ErasureResidueCheck.Findings()));
        check("the stamp needs the request's subject and run, and is the server's own write",
            ErasureRequests.STAMP_CHECKED_SQL.contains("subject_id is not null and run_id is not null")
            && ErasureRequests.STAMP_CHECKED_SQL.contains("checked_at = now()") && ErasureRequests.STAMP_CHECKED_SQL.contains("$1::int"));
        check("phones compared on the last 9 digits, as V0153",
            ErasureResidueCheck.CUSTOMERS_SQL.contains("right(regexp_replace(p.phone, '\\D', '', 'g'), 9) = any(q.phones)"));
        check("the check's transaction only reads, under a statement timeout",
            ErasureResidueCheck.BEGIN_SQL.endsWith("READ ONLY") && ErasureResidueCheck.SETTINGS_SQL.contains("statement_timeout"));
    }

    // ===== save ===================================================================================================

    private static final String VALID = "{\"id\":null,\"receivedOn\":\"2026-11-15\",\"status\":\"open\",\"onHoldUntil\":null,"
        + "\"verifiedBy\":\"email\",\"subjectKind\":null,\"subjectId\":null,\"runId\":null,"
        + "\"outside\":{\"jotform\":false,\"knackSttp\":false,\"knackAbsence\":false,\"payments\":true,\"mailbox\":false}}";

    private static ErasureRequests.Save save(Object argument) {
        return ErasureRequests.parseSave(argument).save();
    }

    private static boolean saveRefused(String json) {
        ErasureRequests.ParsedSave p = ErasureRequests.parseSave(new Object[] { json });
        return p.save() == null && p.refusal() != null;
    }

    /** VALID with one field replaced (its raw JSON value), or removed when value is null. */
    private static String with(String key, String value) {
        ReadOnlyAstObject valid = AST.parseObject(VALID, "json");
        StringBuilder sb = new StringBuilder("{");
        ReadOnlyAstArray keys = valid.keys();
        boolean replaced = false;
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.getElement(i);
            String v = k.equals(key) ? value : raw(valid.get(k));
            replaced |= k.equals(key);
            if (v != null)
                sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v);
        }
        if (!replaced && value != null)
            sb.append(",\"").append(key).append("\":").append(value);
        return sb.append('}').toString();
    }

    private static String raw(Object value) {
        if (value == null)
            return "null";
        if (value instanceof String s)
            return EventErasureJson.quote(s);
        if (AST.isObject(value)) {
            ReadOnlyAstObject o = (ReadOnlyAstObject) value;
            StringBuilder json = new StringBuilder("{");
            ReadOnlyAstArray keys = o.keys();
            for (int i = 0; i < keys.size(); i++) {
                String key = String.valueOf(keys.<Object>getElement(i));
                json.append(json.length() > 1 ? "," : "").append(EventErasureJson.quote(key)).append(':').append(String.valueOf((Object) o.get(key)));
            }
            return json.append('}').toString();
        }
        return value.toString();
    }

    private static void save() {
        System.out.println("save: a new request, as a JSON string");
        ErasureRequests.Save s = save(new Object[] { VALID });
        check("accepted", s != null);
        check("id null = create", s.id() == null);
        check("the fields as sent", s.receivedOn().equals(LocalDate.of(2026, 11, 15)) && "open".equals(s.status())
            && s.onHoldUntil() == null && "email".equals(s.verifiedBy()) && s.subjectKind() == null && s.subjectId() == null
            && s.runId() == null && new ErasureRequests.Outside(List.of(false, false, false, true, false)).equals(s.outside()));
        check("the outside checklist is stored as one boolean per processor, written by the server",
            "{\"jotform\":false,\"knackSttp\":false,\"knackAbsence\":false,\"payments\":true,\"mailbox\":false}".equals(s.outside().toJson()));

        System.out.println("save: an update, as the object the bus decoded");
        AstObject decoded = AST.createObject();
        decoded.set("id", 14.0);
        decoded.set("receivedOn", "2026-11-15");
        decoded.set("status", "on_hold");
        decoded.set("onHoldUntil", "2026-12-20");
        decoded.set("subjectKind", "booker");
        decoded.set("subjectId", 4711);
        decoded.set("runId", "88");
        ErasureRequests.Save u = save(new Object[] { decoded });
        check("accepted", u != null && Integer.valueOf(14).equals(u.id()) && "on_hold".equals(u.status())
            && LocalDate.of(2026, 12, 20).equals(u.onHoldUntil()) && "booker".equals(u.subjectKind())
            && Integer.valueOf(4711).equals(u.subjectId()) && Integer.valueOf(88).equals(u.runId()));
        check("absent nullable fields are null; absent outside leaves it", u.verifiedBy() == null && u.outside() == null);

        System.out.println("save: what is refused");
        check("not one argument", ErasureRequests.parseSave(new Object[0]).save() == null
            && ErasureRequests.parseSave(new Object[] { VALID, VALID }).save() == null && ErasureRequests.parseSave(null).save() == null);
        check("not JSON, or not an object", saveRefused("{receivedOn:") && saveRefused("[" + VALID + "]") && saveRefused("42")
            && ErasureRequests.parseSave(new Object[] { 42 }).save() == null);
        check("a text longer than 2000 characters", saveRefused(VALID.substring(0, VALID.length() - 1) + " ".repeat(2000) + "}"));
        check("an unknown field", saveRefused(with("createdByPersonId2", "5")) && saveRefused(with("note", "\"Jane Doe\"")));
        check("the reply-only fields are refused: the server sets them", saveRefused(with("createdByPersonId", "1"))
            && saveRefused(with("closedAt", "\"x\"")) && saveRefused(with("dueOn", "\"y\"")) && saveRefused(with("checkedAt", "\"z\""))
            && saveRefused(with("createdAt", "\"w\"")) && saveRefused(with("lastRunId", "91")) && saveRefused(with("erasedAt", "\"v\"")));
        check("id: not a positive integer", saveRefused(with("id", "0")) && saveRefused(with("id", "-3"))
            && saveRefused(with("id", "1.5")) && saveRefused(with("id", "\"abc\"")) && saveRefused(with("id", "true")));
        check("receivedOn: required, strict YYYY-MM-DD, a real day", saveRefused(with("receivedOn", null))
            && saveRefused(with("receivedOn", "\"2026-11-5\"")) && saveRefused(with("receivedOn", "\"2026-02-30\""))
            && saveRefused(with("receivedOn", "\"15/11/2026\"")) && saveRefused(with("receivedOn", "20261115")));
        check("status: the closed set only", saveRefused(with("status", "\"pending\"")) && saveRefused(with("status", null))
            && saveRefused(with("status", "\"OPEN\"")) && saveRefused(with("status", "1")));
        check("on_hold needs onHoldUntil", saveRefused(with("status", "\"on_hold\"")));
        check("onHoldUntil only when on_hold", saveRefused(with("onHoldUntil", "\"2026-12-20\"")));
        check("onHoldUntil must be a date", saveRefused(with("status", "\"on_hold\"").replace("\"onHoldUntil\":null", "\"onHoldUntil\":\"soon\"")));
        check("on_hold with a date accepted", save(new Object[] { with("status", "\"on_hold\"").replace("\"onHoldUntil\":null", "\"onHoldUntil\":\"2026-12-20\"") }) != null);
        check("verifiedBy: the closed set or null", saveRefused(with("verifiedBy", "\"passport\""))
            && save(new Object[] { with("verifiedBy", "null") }) != null && save(new Object[] { with("verifiedBy", "\"id\"") }) != null
            && save(new Object[] { with("verifiedBy", "\"other\"") }) != null);
        check("subject: both or neither", saveRefused(with("subjectKind", "\"person\"")) && saveRefused(with("subjectId", "12")));
        check("a run needs the subject it erased", saveRefused(with("runId", "88")));
        check("the run proof is checked against the subject's ledger key",
            ErasureRequests.RUN_OF_SUBJECT_SQL.contains("mode = 'commit'") && ErasureRequests.RUN_OF_SUBJECT_SQL.contains("status = 'succeeded'")
            && ErasureRequests.RUN_OF_SUBJECT_SQL.contains("person_id = $2"));
        check("subject: kind in the closed set, id positive",
            saveRefused(with("subjectKind", "\"customer\"").replace("\"subjectId\":null", "\"subjectId\":12"))
            && saveRefused(with("subjectKind", "\"person\"").replace("\"subjectId\":null", "\"subjectId\":-12"))
            && save(new Object[] { with("subjectKind", "\"person\"").replace("\"subjectId\":null", "\"subjectId\":12") }) != null);
        check("runId: positive or null", saveRefused(with("runId", "0")) && saveRefused(with("runId", "\"x\"")));
        check("checked is not the page's to say: refused", saveRefused(with("checked", "true")) && saveRefused(with("checked", "false"))
            && !ErasureRequests.FIELDS.contains("checked"));
        check("outside: exactly the processors, as booleans",
            saveRefused(with("outside", "{\"jotform\":true,\"payments\":true}"))
            && saveRefused(with("outside", "{\"jotform\":true,\"knackSttp\":true,\"knackAbsence\":true,\"payments\":true,\"mailbox\":true,\"note\":\"Jane\"}"))
            && saveRefused(with("outside", "{\"jotform\":\"yes\",\"knackSttp\":true,\"knackAbsence\":true,\"payments\":true,\"mailbox\":true}"))
            && saveRefused(with("outside", "{\"photos\":true,\"knackSttp\":true,\"knackAbsence\":true,\"payments\":true,\"mailbox\":true}"))
            && save(new Object[] { with("outside", "{\"jotform\":true,\"knackSttp\":true,\"knackAbsence\":false,\"payments\":true,\"mailbox\":true}") }) != null
            && saveRefused(with("outside", "[true,true,true]")) && saveRefused(with("outside", "true")));
        check("outside: absent or null leaves it", save(new Object[] { with("outside", null) }).outside() == null
            && save(new Object[] { with("outside", "null") }).outside() == null);
        check("the refusal keys are the contract's", "ErasureRequestError".equals(ErasureRequests.REQUEST_KEY)
            && "ErasureRequestNotFoundError".equals(ErasureRequests.NOT_FOUND_KEY));
        check("the creator and closer are the session's: no field names them", !ErasureRequests.FIELDS.contains("createdByPersonId")
            && !ErasureRequests.FIELDS.contains("closedByPersonId"));
        check("the writes bind the caller last ($9 / $10), and stamp closed_at in SQL",
            ErasureRequests.INSERT_SQL.contains("then $9::int end)") && ErasureRequests.UPDATE_SQL.contains("else $10::int end")
            && ErasureRequests.UPDATE_SQL.contains("then r.closed_at else now()"));
        check("a new request starts unchecked; an update keeps checked_at only for the same subject and run",
            !ErasureRequests.INSERT_SQL.contains("checked_at")
            && ErasureRequests.UPDATE_SQL.contains("checked_at = case when r.subject_kind is not distinct from $6::text and r.subject_id is not distinct from $7::int"
                + " and r.run_id is not distinct from $8::int then r.checked_at end,"));

        System.out.println("save: the refusal reaches the caller framed, before any database work");
        Future<String> answer = ErasureRequests.save(new Object[] { with("status", "\"pending\"") }, new EventErasureAccess.Caller(5));
        check("[ErasureRequestError] text", answer.failed() && answer.cause().getMessage().startsWith("[ErasureRequestError] status must be"));
        Future<String> findAnswer = ErasureFind.find(new Object[] { "Jane" }, new EventErasureAccess.Caller(5));
        check("[ErasureQueryError] text", findAnswer.failed() && findAnswer.cause().getMessage().startsWith("[ErasureQueryError] "));
        Future<String> checkAnswer = ErasureResidueCheck.check(new Object[0], new EventErasureAccess.Caller(5));
        check("[ErasureCheckError] text", checkAnswer.failed() && checkAnswer.cause().getMessage().startsWith("[ErasureCheckError] "));
    }

    // ===== dates ==================================================================================================

    private static ErasureRequests.Save dated(String receivedOn, String status, String onHoldUntil) {
        return new ErasureRequests.Save(null, LocalDate.parse(receivedOn), status, onHoldUntil == null ? null : LocalDate.parse(onHoldUntil),
            null, null, null, null, null);
    }

    private static void dates() {
        System.out.println("save: dates against today (Europe/London)");
        check("received today: accepted", ErasureRequests.dateRefusal(dated("2026-11-17", "open", null), TODAY, null, null) == null);
        check("received tomorrow: refused", ErasureRequests.dateRefusal(dated("2026-11-18", "open", null), TODAY, null, null) != null);
        check("received exactly a year ago: accepted", ErasureRequests.dateRefusal(dated("2025-11-17", "open", null), TODAY, null, null) == null);
        check("received a year and a day ago: refused", ErasureRequests.dateRefusal(dated("2025-11-16", "open", null), TODAY, null, null) != null);
        check("...unless the request already holds that date (it can still be closed)",
            ErasureRequests.dateRefusal(dated("2025-11-16", "done", null), TODAY, LocalDate.of(2025, 11, 16), null) == null);
        check("...but changing it to another old date is refused",
            ErasureRequests.dateRefusal(dated("2025-11-15", "done", null), TODAY, LocalDate.of(2025, 11, 16), null) != null);
        check("held until tomorrow: accepted", ErasureRequests.dateRefusal(dated("2026-11-15", "on_hold", "2026-11-18"), TODAY, null, null) == null);
        check("held until today: refused (a future date)", ErasureRequests.dateRefusal(dated("2026-11-15", "on_hold", "2026-11-17"), TODAY, null, null) != null);
        check("held until a past date the request already holds: accepted",
            ErasureRequests.dateRefusal(dated("2026-11-01", "on_hold", "2026-11-10"), TODAY, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10)) == null);
        check("a strict date parser", ErasureRequests.date("2026-11-15") != null && ErasureRequests.date("2026-13-01") == null
            && ErasureRequests.date("2026-11-15T00:00") == null && ErasureRequests.date(null) == null && ErasureRequests.date(20261115) == null);
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
        Object[] customer = { 12, "Jane \"JD\"", "Doe\\", "jane@x.org", null, true, null, false, 4L, "2026-05-01" };
        Object[] booking = { 4711, 1234, "Autumn retreat", "2026-10-05", "2026-10-12", "Jane", "Doe", null, "+44\n1", false, null, true };
        Object[][] customers = new Object[51][];
        for (int i = 0; i < 51; i++)
            customers[i] = customer;
        ReadOnlyAstObject find = parsed(ErasureFind.replyJson(ErasureFind.Kind.EMAIL, rows(customers), rows(new Object[][] { booking })));
        check("valid JSON, the contract's keys", "email".equals(find.getString("kind")) && find.getArray("customers") != null
            && find.getArray("bookings") != null && find.has("truncated"));
        check("51 rows read: 50 answered, truncated", find.getArray("customers").size() == 50 && find.isTrue("truncated"));
        ReadOnlyAstObject c = find.getArray("customers").getObject(0);
        check("a customer, escaped and typed", c.getInteger("personId") == 12 && "Jane \"JD\"".equals(c.getString("firstName"))
            && "Doe\\".equals(c.getString("lastName")) && c.get("phone") == null && c.isTrue("hasAccount")
            && c.get("linkedCopyOf") == null && !c.isTrue("removed") && c.getInteger("bookings") == 4
            && "2026-05-01".equals(c.getString("lastEventStart")) && c.keys().size() == 10);
        ReadOnlyAstObject b = find.getArray("bookings").getObject(0);
        check("a booking, escaped and typed", b.getInteger("documentId") == 4711 && b.getInteger("ref") == 1234
            && "Autumn retreat".equals(b.getString("eventName")) && "2026-10-12".equals(b.getString("eventEnd"))
            && b.get("email") == null && "+44\n1".equals(b.getString("phone")) && b.get("personId") == null
            && b.isTrue("anonymised") && !b.isTrue("cancelled") && b.keys().size() == 12);
        ReadOnlyAstObject empty = parsed(ErasureFind.replyJson(ErasureFind.Kind.NAME, rows(new Object[0][]), rows(new Object[0][])));
        check("nothing found: empty lists, not truncated", empty.getArray("customers").size() == 0 && !empty.isTrue("truncated"));

        System.out.println("check: the reply");
        ErasureResidueCheck.Findings findings = new ErasureResidueCheck.Findings();
        findings.found.put("customers", 0L);
        findings.found.put("bookings", 250L);
        findings.people.add(ErasureResidueCheck.personJson(12, "Anonymised", "#12", null, true));
        findings.bookings.add(ErasureResidueCheck.bookingJson(4711, 1234, "Anonymised", "#B4711", null, false, 12000, false));
        ReadOnlyAstObject residue = parsed(ErasureResidueCheck.replyJson(findings));
        ReadOnlyAstArray areas = residue.getArray("areas");
        check("only the areas searched, in the contract's order: one never looked at is left out, not answered 0",
            areas.size() == 2 && "customers".equals(areas.getObject(0).getString("area")) && areas.getObject(0).getInteger("found") == 0
            && "bookings".equals(areas.getObject(1).getString("area")));
        check("a count above 100 is 100", areas.getObject(1).getInteger("found") == 100);
        check("recorded: false unless the check stamped its request", residue.has("recorded") && !residue.isTrue("recorded"));
        findings.recorded = true;
        check("recorded: true once stamped", parsed(ErasureResidueCheck.replyJson(findings)).isTrue("recorded"));
        ErasureResidueCheck.Findings idsOnly = new ErasureResidueCheck.Findings();
        check("a check by ids only answers no area at all",
            parsed(ErasureResidueCheck.replyJson(idsOnly)).getArray("areas").size() == 0);
        ReadOnlyAstObject person = residue.getArray("people").getObject(0);
        check("a person as now", person.getInteger("personId") == 12 && person.get("email") == null && person.isTrue("removed")
            && person.keys().size() == 5);
        ReadOnlyAstObject doc = residue.getArray("bookings").getObject(0);
        check("a booking as now", doc.getInteger("priceNet") == 12000 && !doc.isTrue("hasPhone") && "#B4711".equals(doc.getString("lastName"))
            && doc.keys().size() == 8);

        System.out.println("requests: the reply");
        Object[] request = { 14, "2026-11-15", "2026-12-15", "on_hold", "2026-12-20", "id", "booker", 4711, null,
            "2026-11-18T10:02:00Z", "{\"jotform\": true, \"payments\": false, \"photos\": true}", "2026-11-15T09:00:00Z", 5, null, 91, "2026-11-17T12:00:00Z" };
        ReadOnlyAstObject r = parsed(ErasureRequests.requestJson(rows(new Object[][] { request }), 0));
        check("the contract's keys and values", r.getInteger("id") == 14 && "2026-12-15".equals(r.getString("dueOn"))
            && "on_hold".equals(r.getString("status")) && "2026-12-20".equals(r.getString("onHoldUntil"))
            && "booker".equals(r.getString("subjectKind")) && r.getInteger("subjectId") == 4711 && r.get("runId") == null
            && "2026-11-18T10:02:00Z".equals(r.getString("checkedAt")) && r.getInteger("createdByPersonId") == 5
            && r.get("closedAt") == null && r.getInteger("lastRunId") == 91 && "2026-11-17T12:00:00Z".equals(r.getString("erasedAt"))
            && r.keys().size() == 16);
        ReadOnlyAstObject outside = r.getObject("outside");
        check("outside: every processor, a stored key it lacks unticked and an unknown one dropped",
            outside.isTrue("jotform") && !outside.isTrue("payments") && !outside.isTrue("knackSttp") && !outside.isTrue("mailbox")
            && outside.get("photos") == null && outside.keys().size() == 5);
        check("a save's id is the one returning gave back", Integer.valueOf(14).equals(ErasureRequests.writtenId(new SubmitResult(1, new Object[] { 14 })))
            && ErasureRequests.writtenId(new SubmitResult(1, new Object[0])) == null && ErasureRequests.writtenId(null) == null);
    }
}

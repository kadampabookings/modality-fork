package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.vertx.VertxAsync;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Step 3 of an erasure request — did it work? — for {@link ErasureCheckEndpoint}: an independent look, after (or
 * before) an anonymisation, at whether KBS still holds the identifiers the Data Protection Officer captured at step 1.
 * Independent of the functions on purpose: it asks the tables, not the functions' own grids.
 *
 * <h3>The argument: {@code [emails[], phones[], personIds[], documentIds[], requestId]}</h3>
 *
 * Each array may be null or absent, and holds at most {@link #MAX_ENTRIES} entries. An email is a string, trimmed, 3
 * to 200 characters, with an {@code @}, compared as {@code lower(trim(column)) = lower(trim(email))}. A phone is a
 * string of at most 50 characters, compared on its last 9 digits (digits only, as V0153 matches phones, so
 * {@code +44 7700 900123} and {@code 07700 900123} meet); a phone with fewer than 9 digits is IGNORED, not refused.
 * Ids are positive integers. {@code requestId}, optional (null or a positive id), is the erasure request the check is
 * for. Anything else, or nothing left to check, is refused {@code [ErasureCheckError]}.
 *
 * <h3>The areas</h3>
 *
 * Each area is one count, capped at {@link #CAP} ({@code found = min(count, 100)}): customers (person rows, any
 * state, with one of the emails or phones — an anonymised row holding one would be a failure to report, not to
 * hide), logins (account usernames), bookings (person_email, or person_phone), emails (mail recipients), history
 * (username: the address itself, or {@code 'guest:'} + its first 58 characters, as HistoryRecorder writes it),
 * magic links (email or old_email) and volunteering applications (email, or phone). An area none of the identifiers
 * can concern (an email-only area with no email) is not searched and is left out of the reply — never answered 0.
 *
 * <p>{@code people} / {@code bookings}: the given ids as they are now, for the page's before/after.
 *
 * <h3>Recording it on the request</h3>
 *
 * With a {@code requestId}, a check that is complete and clean — every area searched (an email concerns them all) and
 * every one at 0 — stamps that request's {@code checked_at} ({@link ErasureRequests#stampChecked}), provided the
 * request names its subject and the run that erased them. The server decides, never the page: "checked" in the
 * register is what a check established. The reply says whether it was stamped ({@code recorded}). The stamp is
 * written after the read-only transaction, on its own; if it fails, that is logged and answered
 * {@code recorded: false}, never as a failed check.
 *
 * <h3>How it runs</h3>
 *
 * On a dedicated connection ({@link EventErasureRunner#connect()}) rather than the pool, because only a transaction
 * of its own can bound every statement with a {@code statement_timeout} ({@link #STATEMENT_TIMEOUT}): history and
 * recipient have no index on the columns read, and are the two biggest tables. One {@code REPEATABLE READ READ ONLY}
 * transaction, so every area describes the same moment; rolled back, then closed. A statement that runs out of time
 * fails the whole check ({@code [ErasureSqlError]}): a partial answer could read as "nothing left".
 *
 * <p>The reply names people: reply only, never logged (the log says the counts), never stored.
 *
 * @author Claude Code
 */
final class ErasureResidueCheck {

    private ErasureResidueCheck() {
    }

    static final String CHECK_KEY = "ErasureCheckError";

    static final int MAX_ENTRIES = 10;
    static final int CAP = 100;
    static final int MAX_PHONE_LENGTH = 50;
    static final String STATEMENT_TIMEOUT = "10s";

    /** The identifiers to look for, normalised: emails trimmed, phones as their last 9 digits, all de-duplicated. */
    record Identifiers(List<String> emails, List<String> phones, List<Integer> personIds, List<Integer> documentIds) {
        boolean isEmpty() {
            return emails.isEmpty() && phones.isEmpty() && personIds.isEmpty() && documentIds.isEmpty();
        }
    }

    /** {@link #parse}'s answer: the identifiers and the request the check is for (or null), or the refusal's text. */
    record Parsed(Identifiers identifiers, Integer requestId, String refusal) {
        static Parsed refused(String text) {
            return new Parsed(null, null, text);
        }
    }

    // ===== SQL — constants only; the emails are bound as $1 (text[]), the phones' last 9 digits as $2 (text[]) ======

    /**
     * Every area statement starts here: the emails lower-cased by the database (as the columns are), and the history
     * usernames they give, computed once — an array built in the WHERE clause is rebuilt for every row (2.3 s against
     * 0.6 s on 2M rows, measured 17 Nov 2026). Both parameters are named in every statement, so the database can type them
     * whichever ones the body reads.
     */
    private static final String IDENTIFIERS_CTE =
        "with q as materialized (select e as emails, e || array(select 'guest:' || left(x, 58) from unnest(e) x) as usernames,"
        + " $2::text[] as phones from (select array(select lower(trim(x)) from unnest($1::text[]) x) as e) s) ";

    /** The last 9 digits of a phone column, as V0153 compares phones ('\D' = not a digit). */
    private static String lastNineDigits(String column) {
        return "right(regexp_replace(" + column + ", '\\D', '', 'g'), 9)";
    }

    private static String capped(String from, String where) {
        return IDENTIFIERS_CTE + "select count(*) from (select 1 from " + from + ", q where " + where + " limit " + CAP + ") t";
    }

    static final String CUSTOMERS_SQL = capped("person p",
        "lower(trim(p.email)) = any(q.emails) or (cardinality(q.phones) > 0 and " + lastNineDigits("p.phone") + " = any(q.phones))");

    static final String LOGINS_SQL = capped("frontend_account fa", "lower(trim(fa.username)) = any(q.emails)");

    static final String BOOKINGS_SQL = capped("document d",
        "lower(trim(d.person_email)) = any(q.emails) or (cardinality(q.phones) > 0 and " + lastNineDigits("d.person_phone") + " = any(q.phones))");

    static final String EMAILS_SQL = capped("recipient r", "lower(trim(r.email)) = any(q.emails)");

    /** A guest is recorded as 'guest:' + the first 58 characters of their address (64 in all); an SSO login as the address. */
    static final String HISTORY_SQL = capped("history h", "lower(trim(h.username)) = any(q.usernames)");

    static final String MAGIC_LINKS_SQL = capped("magic_link ml",
        "lower(trim(ml.email)) = any(q.emails) or lower(trim(ml.old_email)) = any(q.emails)");

    static final String VOLUNTEERING_SQL = capped("volunteering_application va",
        "lower(trim(va.email)) = any(q.emails) or (cardinality(q.phones) > 0 and " + lastNineDigits("va.phone") + " = any(q.phones))");

    static final String PEOPLE_SQL =
        "select p.id, p.first_name, p.last_name, p.email, p.removed from person p where p.id = any($1::int[]) order by p.id";

    static final String DOCUMENTS_SQL =
        "select d.id, d.ref, d.person_first_name, d.person_last_name, d.person_email, nullif(trim(d.person_phone), '') is not null,"
        + " d.price_net, d.cancelled from document d where d.id = any($1::int[]) order by d.id";

    static final String BEGIN_SQL = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY";

    /** After the BEGIN: local to the transaction, so nothing outlives the check. */
    static final String SETTINGS_SQL =
        "select set_config('statement_timeout', '" + STATEMENT_TIMEOUT + "', true), set_config('lock_timeout', '5s', true),"
        + " set_config('application_name', 'kbs-dpo-check', true)";

    /** One area of the reply: its name, its statement, and whether the phones concern it (the emails concern all). */
    record Area(String name, String sql, boolean byPhone) {
        boolean concerns(Identifiers identifiers) {
            return !identifiers.emails().isEmpty() || (byPhone && !identifiers.phones().isEmpty());
        }
    }

    /** In the reply's order. */
    static final List<Area> AREAS = List.of(
        new Area("customers", CUSTOMERS_SQL, true),
        new Area("logins", LOGINS_SQL, false),
        new Area("bookings", BOOKINGS_SQL, true),
        new Area("emails", EMAILS_SQL, false),
        new Area("history", HISTORY_SQL, false),
        new Area("magicLinks", MAGIC_LINKS_SQL, false),
        new Area("volunteering", VOLUNTEERING_SQL, true));

    // ===== The argument ============================================================================================

    static Parsed parse(Object argument) {
        Object[] arguments = EventErasureArguments.array(argument);
        if (arguments.length > 5)
            return Parsed.refused("Send at most four lists and a request id: emails, phones, person ids, booking ids");
        Object[][] lists = new Object[4][];
        for (int i = 0; i < 4; i++) {
            Object list = EventErasureArguments.at(arguments, i);
            if (list == null)
                lists[i] = new Object[0];
            else if (list instanceof Object[] array)
                lists[i] = array;
            else
                return Parsed.refused("Each list must be an array");
            if (lists[i].length > MAX_ENTRIES)
                return Parsed.refused("At most " + MAX_ENTRIES + " entries per list");
        }
        Map<String, String> emails = new LinkedHashMap<>(); // keyed lower-case: de-duplicated as the database compares
        for (Object entry : lists[0]) {
            String email = entry instanceof String s ? s.strip() : null;
            if (email == null || email.length() < ErasureFind.MIN_LENGTH || email.length() > ErasureFind.MAX_LENGTH
                || email.indexOf('@') < 0 || DpoErasure.hasControlCharacter(email))
                return Parsed.refused("An email is malformed");
            emails.putIfAbsent(email.toLowerCase(Locale.ROOT), email);
        }
        Set<String> phones = new LinkedHashSet<>();
        for (Object entry : lists[1]) {
            if (!(entry instanceof String phone) || phone.length() > MAX_PHONE_LENGTH || DpoErasure.hasControlCharacter(phone))
                return Parsed.refused("A phone is malformed");
            String key = phoneKey(phone);
            if (key != null) // fewer than 9 digits: ignored, as V0153 ignores them
                phones.add(key);
        }
        Object requestValue = EventErasureArguments.at(arguments, 4);
        Integer requestId = requestValue == null ? null : EventErasureArguments.positiveId(requestValue);
        if (requestValue != null && requestId == null)
            return Parsed.refused("The request id must be a positive integer or null");
        List<Integer> personIds = ids(lists[2]);
        List<Integer> documentIds = ids(lists[3]);
        if (personIds == null || documentIds == null)
            return Parsed.refused("An id is malformed");
        Identifiers identifiers = new Identifiers(List.copyOf(emails.values()), List.copyOf(phones), personIds, documentIds);
        if (identifiers.isEmpty())
            return Parsed.refused("Nothing to check: give an email, a phone of 9 digits or more, or an id");
        return new Parsed(identifiers, requestId, null);
    }

    /** The last 9 digits of a phone, or null when it has fewer than 9. */
    static String phoneKey(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() < 9 ? null : digits.substring(digits.length() - 9);
    }

    /** Positive ids, de-duplicated in order; null when one is not. */
    private static List<Integer> ids(Object[] entries) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Object entry : entries) {
            Integer id = EventErasureArguments.positiveId(entry);
            if (id == null)
                return null;
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    // ===== The check ===============================================================================================

    /** What one check found, filled statement by statement; {@code area} names the statement running, for the log. */
    static final class Findings {
        final Map<String, Long> found = new LinkedHashMap<>();
        final List<String> people = new ArrayList<>();
        final List<String> bookings = new ArrayList<>();
        String area = "connect";
        boolean recorded;
    }

    /** Every area searched (an email concerns them all) and every one at 0: what may be recorded as "checked". */
    static boolean completeAndClean(Identifiers identifiers, Findings findings) {
        return !identifiers.emails().isEmpty() && findings.found.size() == AREAS.size()
               && findings.found.values().stream().allMatch(n -> n == 0);
    }

    static Future<String> check(Object argument, EventErasureAccess.Caller caller) {
        Parsed parsed = parse(argument);
        if (parsed.identifiers() == null)
            return DpoErasure.refusal(CHECK_KEY, parsed.refusal(), "check", caller.personId());
        Identifiers identifiers = parsed.identifiers();
        Integer requestId = parsed.requestId();
        Findings findings = new Findings();
        long start = System.nanoTime();
        io.vertx.core.Future<Findings> done;
        try {
            done = EventErasureRunner.connect().compose(connection -> run(connection, identifiers, findings)
                .eventually(connection::close));
        } catch (RuntimeException e) {
            done = io.vertx.core.Future.failedFuture(e);
        }
        return VertxAsync.toWebfxFuture(done)
            .compose(ignored -> requestId == null || !completeAndClean(identifiers, findings) ? Future.succeededFuture(false)
                : ErasureRequests.stampChecked(requestId).recover(cause -> {
                    Console.log(DpoErasure.LOG_PREFIX + "check by person " + caller.personId() + ": request " + requestId
                                + " not stamped: " + EventErasureRunner.sqlStateOf(cause));
                    return Future.succeededFuture(false);
                }))
            .map(recorded -> {
                findings.recorded = recorded;
                StringBuilder line = new StringBuilder(DpoErasure.LOG_PREFIX).append("check by person ").append(caller.personId())
                    .append(" (").append(identifiers.emails().size()).append(" emails, ").append(identifiers.phones().size())
                    .append(" phones, ").append(identifiers.personIds().size()).append(" people, ")
                    .append(identifiers.documentIds().size()).append(" bookings) in ")
                    .append((System.nanoTime() - start) / 1_000_000).append(" ms:");
                findings.found.forEach((area, n) -> line.append(' ').append(area).append('=').append(n));
                if (requestId != null)
                    line.append("; request ").append(requestId).append(recorded ? " stamped" : " not stamped");
                Console.log(line.toString());
                return replyJson(findings);
            })
            .recover(cause -> {
                boolean timedOut = "57014".equals(EventErasureRunner.sqlStateOf(cause));
                return DpoErasure.failure(cause, "check (" + findings.area + ")", caller.personId(), timedOut
                    ? "The check took too long, so nothing can be said yet: try again later"
                    : "The check could not be completed");
            });
    }

    private static io.vertx.core.Future<Findings> run(PgConnection connection, Identifiers identifiers, Findings findings) {
        connection.noticeHandler(notice -> { });
        String[] emails = identifiers.emails().toArray(new String[0]);
        String[] phones = identifiers.phones().toArray(new String[0]);
        io.vertx.core.Future<?> chain = connection.query(BEGIN_SQL).execute()
            .compose(ignored -> connection.query(SETTINGS_SQL).execute());
        for (Area area : AREAS)
            chain = chain.compose(ignored -> {
                // An area none of the identifiers concerns is NOT searched, and is left out of the reply: answering it
                // 0 would read as "nothing left" for what was never looked at (a check sent ids only, after a reload)
                if (!area.concerns(identifiers))
                    return io.vertx.core.Future.succeededFuture();
                findings.area = area.name();
                return connection.preparedQuery(area.sql()).execute(Tuple.tuple().addArrayOfString(emails).addArrayOfString(phones))
                    .map(rows -> {
                        Long n = rows.iterator().next().getLong(0);
                        findings.found.put(area.name(), Math.min(n == null ? 0 : n, CAP));
                        return null;
                    });
            });
        return chain
            .compose(ignored -> {
                if (identifiers.personIds().isEmpty())
                    return io.vertx.core.Future.succeededFuture();
                findings.area = "people";
                return connection.preparedQuery(PEOPLE_SQL)
                    .execute(Tuple.tuple().addArrayOfInteger(identifiers.personIds().toArray(new Integer[0])))
                    .map(rows -> {
                        for (Row row : rows)
                            findings.people.add(personJson(row.getInteger(0), row.getString(1), row.getString(2),
                                row.getString(3), Boolean.TRUE.equals(row.getBoolean(4))));
                        return null;
                    });
            })
            .compose(ignored -> {
                if (identifiers.documentIds().isEmpty())
                    return io.vertx.core.Future.succeededFuture();
                findings.area = "bookings (ids)";
                return connection.preparedQuery(DOCUMENTS_SQL)
                    .execute(Tuple.tuple().addArrayOfInteger(identifiers.documentIds().toArray(new Integer[0])))
                    .map(rows -> {
                        for (Row row : rows)
                            findings.bookings.add(bookingJson(row.getInteger(0), row.getInteger(1), row.getString(2),
                                row.getString(3), row.getString(4), Boolean.TRUE.equals(row.getBoolean(5)),
                                row.getInteger(6), Boolean.TRUE.equals(row.getBoolean(7))));
                        return null;
                    });
            })
            .compose(ignored -> {
                findings.area = "rollback";
                return connection.query("ROLLBACK").execute();
            })
            .map(ignored -> findings);
    }

    // ===== The reply ===============================================================================================

    /**
     * {@code {areas: [{area, found}], people: [...], bookings: [...], recorded}}, the areas in {@link #AREAS}' order —
     * only those actually searched: a missing area means "not looked at", never "nothing found". {@code recorded}:
     * whether this check was stamped on its request.
     */
    static String replyJson(Findings findings) {
        List<String> areas = new ArrayList<>();
        for (Area area : AREAS)
            if (findings.found.containsKey(area.name()))
                areas.add(new EventErasureJson.ObjectWriter()
                    .string("area", area.name())
                    .number("found", Math.min(findings.found.get(area.name()), CAP))
                    .toJson());
        return new EventErasureJson.ObjectWriter()
            .raw("areas", EventErasureJson.array(areas))
            .raw("people", EventErasureJson.array(findings.people))
            .raw("bookings", EventErasureJson.array(findings.bookings))
            .bool("recorded", findings.recorded)
            .toJson();
    }

    static String personJson(Integer personId, String firstName, String lastName, String email, boolean removed) {
        return new EventErasureJson.ObjectWriter()
            .number("personId", personId)
            .string("firstName", firstName)
            .string("lastName", lastName)
            .string("email", email)
            .bool("removed", removed)
            .toJson();
    }

    static String bookingJson(Integer documentId, Integer ref, String firstName, String lastName, String email,
                              boolean hasPhone, Integer priceNet, boolean cancelled) {
        return new EventErasureJson.ObjectWriter()
            .number("documentId", documentId)
            .number("ref", ref)
            .string("firstName", firstName)
            .string("lastName", lastName)
            .string("email", email)
            .bool("hasPhone", hasPhone)
            .number("priceNet", priceNet)
            .bool("cancelled", cancelled)
            .toJson();
    }
}

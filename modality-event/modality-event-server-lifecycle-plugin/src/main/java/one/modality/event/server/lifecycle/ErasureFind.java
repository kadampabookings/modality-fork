package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 1 of an erasure request — who is it? — for {@link ErasureFindEndpoint}: the customers and the bookings an
 * email address, a booking reference or a full name points at, so the Data Protection Officer can pick the person to
 * erase without Registration or Customers access.
 *
 * <h3>What the text is ({@link #classify}, the server decides)</h3>
 *
 * <ul>
 *   <li>Trimmed, at most 200 characters, no control character; an email or a name needs 3 characters at least, a
 *       booking reference (usually a small number) does not — else {@code [ErasureQueryError]}.</li>
 *   <li>With an {@code @}: an <b>email</b>, compared as {@code lower(trim(x)) = lower(trim($1))} with person.email, an
 *       account login (frontend_account.username, giving the account's owner rows) and the bookings'
 *       person_email.</li>
 *   <li>Digits only, 9 at most: a <b>booking reference</b>, {@code document.ref = n}. A reference is numbered per
 *       event, so it names one booking of many events: all of them come back, with their event.</li>
 *   <li>Anything else: a <b>full name</b> — two words at least, counted as {@code abc_names()} counts them (separated
 *       by spaces, commas, hyphens or {@code #}; numbers are not words), else {@code [ErasureQueryError]}. Compared on
 *       the sorted, lower-case, unaccented form: {@code public.abc_names($1)}, computed ONCE per statement (an
 *       uncorrelated scalar subquery; it is plpgsql), against person.abc_names and document.person_abc_names.</li>
 * </ul>
 *
 * <p>Exact matches only, no pattern: a search finds who it names, not who resembles them. At most {@link #MAX_ROWS}
 * of each list, newest first ({@code truncated} says when one was cut). Customers are person rows not anonymised
 * (removed ones included); for a reference, the customers the matched bookings belong to.
 *
 * <p>The reply names people (names, emails, phones): reply only — never logged (the log line says the kind and the
 * counts), never stored.
 *
 * @author Claude Code
 */
final class ErasureFind {

    private ErasureFind() {
    }

    static final String QUERY_KEY = "ErasureQueryError";

    static final int MIN_LENGTH = 3;
    static final int MAX_LENGTH = 200;
    /** Of each list; one more row is read to know whether it was cut. */
    static final int MAX_ROWS = 50;

    enum Kind {
        EMAIL("email"), REF("ref"), NAME("name");

        final String json;

        Kind(String json) {
            this.json = json;
        }
    }

    /**
     * A query the server accepted: its kind and the value bound to $1 — the trimmed text (an email, a name) or the
     * reference as an Integer.
     */
    record Query(Kind kind, Object parameter) {
    }

    /** {@link #classify}'s answer: a query, or the refusal's text. */
    record Classified(Query query, String refusal) {
        static Classified refused(String text) {
            return new Classified(null, text);
        }
    }

    // ===== SQL — constants only; the text is bound as $1 =========================================================

    /**
     * One customer: who, whether they own an account, the row they are a linked copy of, and their bookings. The people
     * the search matches are computed once, as a set (a MATERIALIZED CTE, which the planner cannot see through), then
     * cut to the rows answered, and only theirs have their bookings counted. A booking reference is numbered per event,
     * so it matches hundreds of people: counting the bookings of all of them took 4.5 s on staging, and letting the
     * planner probe person after person for the reference 2.7 s.
     */
    private static final String CUSTOMER_SQL_HEAD = "with ids as materialized (";

    private static final String CUSTOMER_SQL_TAIL = ")"
        + " select p.id, p.first_name, p.last_name, p.email, p.phone, (p.frontend_account_id is not null and p.owner),"
        + " p.account_person_id, p.removed, b.n, to_char(b.last_start, 'YYYY-MM-DD')"
        + " from (select p0.* from person p0 where p0.anonymised_date is null and p0.id in (select * from ids)"
        + " order by p0.id desc limit " + (MAX_ROWS + 1) + ") p"
        + " cross join lateral (select count(*) as n, max(e.start_date) as last_start"
        + " from document d left join event e on e.id = d.event_id where d.person_id = p.id) b"
        + " order by p.id desc";

    static final String EMAIL_CUSTOMERS_SQL = CUSTOMER_SQL_HEAD
        + "select q.id from person q where lower(trim(q.email)) = lower(trim($1::text))"
        + " union select q.id from frontend_account fa join person q on q.frontend_account_id = fa.id and q.owner"
        + " where lower(trim(fa.username)) = lower(trim($1::text))"
        + CUSTOMER_SQL_TAIL;

    /** abc_names() is plpgsql: in a scalar subquery it runs once, as an InitPlan, never once per row. */
    static final String NAME_CUSTOMERS_SQL = CUSTOMER_SQL_HEAD
        + "select q.id from person q where q.abc_names = (select public.abc_names($1::text))"
        + CUSTOMER_SQL_TAIL;

    static final String REF_CUSTOMERS_SQL = CUSTOMER_SQL_HEAD
        + "select d.person_id from document d where d.ref = $1::int"
        + CUSTOMER_SQL_TAIL;

    /**
     * Whether anonymise_booker() (V0163) can take a booking along with others with no tick: no customer record, an
     * address (an '@' after the first character, its v_address) and a name of two words or more (its v_name). Two such
     * bookings with the same address (trimmed, any case) and the same abc_names are one booker: its 'auto' rule.
     */
    private static final String SAME_BOOKER_ELIGIBLE =
        "(d.person_id is null and position('@' in coalesce(trim(d.person_email), '')) > 1"
        + " and position(' ' in trim(coalesce(d.person_abc_names, ''))) > 0)";

    /**
     * One booking: its event, who it is for, whether anonymise_booker() already took it (its own test), and which
     * booker without an account it belongs to ({@link #SAME_BOOKER_ELIGIBLE}): bookings with the same number are erased
     * together. A window over the rows found, before the limit, so a booker's bookings get one number.
     */
    private static final String BOOKING_SQL_HEAD =
        "select d.id, d.ref, e.name, to_char(e.start_date, 'YYYY-MM-DD'), to_char(e.end_date, 'YYYY-MM-DD'),"
        + " d.person_first_name, d.person_last_name, d.person_email, d.person_phone, d.cancelled, d.person_id,"
        + " coalesce(d.person_first_name = 'Anonymised' and d.person_last_name like '#%' and d.person_email is null, false),"
        + " case when " + SAME_BOOKER_ELIGIBLE + " then dense_rank() over (partition by " + SAME_BOOKER_ELIGIBLE
        + " order by lower(trim(d.person_email)), d.person_abc_names) end"
        + " from document d left join event e on e.id = d.event_id where ";

    private static final String BOOKING_SQL_TAIL = " order by d.id desc limit " + (MAX_ROWS + 1);

    /**
     * lower(person_email), not lower(trim(…)): the form document_person_email_lower_idx indexes. With trim, the planner
     * walks every booking backwards by id looking for 51 matches: 17 s on staging (294k bookings) for an address that
     * has none, against 7 ms. Staging held no address stored with spaces around it (17 Nov 2026; anonymised, so no proof
     * for production); the check (ErasureResidueCheck) compares trimmed, so such a booking would still show there.
     */
    static final String EMAIL_BOOKINGS_SQL = BOOKING_SQL_HEAD
        + "lower(d.person_email) = lower(trim($1::text))" + BOOKING_SQL_TAIL;

    static final String NAME_BOOKINGS_SQL = BOOKING_SQL_HEAD
        + "d.person_abc_names = (select public.abc_names($1::text))" + BOOKING_SQL_TAIL;

    static final String REF_BOOKINGS_SQL = BOOKING_SQL_HEAD + "d.ref = $1::int" + BOOKING_SQL_TAIL;

    static String customersSql(Kind kind) {
        return switch (kind) {
            case EMAIL -> EMAIL_CUSTOMERS_SQL;
            case REF -> REF_CUSTOMERS_SQL;
            case NAME -> NAME_CUSTOMERS_SQL;
        };
    }

    static String bookingsSql(Kind kind) {
        return switch (kind) {
            case EMAIL -> EMAIL_BOOKINGS_SQL;
            case REF -> REF_BOOKINGS_SQL;
            case NAME -> NAME_BOOKINGS_SQL;
        };
    }

    // ===== The rules ===============================================================================================

    /**
     * What the argument asks for, by the rules of the class comment. {@code [query]} — exactly one string — or the
     * string bare.
     */
    static Classified classify(Object argument) {
        Object[] arguments = EventErasureArguments.array(argument);
        if (arguments.length != 1 || !(arguments[0] instanceof String raw))
            return Classified.refused("Send one search text");
        String text = raw.strip();
        // A booking reference is numbered per event, so most are short: it needs no minimum length
        if (text.matches("[0-9]{1,9}") && Integer.parseInt(text) > 0)
            return new Classified(new Query(Kind.REF, Integer.valueOf(text)), null);
        if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH)
            return Classified.refused("Type " + MIN_LENGTH + " to " + MAX_LENGTH + " characters");
        if (DpoErasure.hasControlCharacter(text))
            return Classified.refused("The search text holds a control character");
        if (text.indexOf('@') >= 0)
            return new Classified(new Query(Kind.EMAIL, text), null);
        // abc_names() keeps an empty word for a separator at either end ('Doe, Jane #' gives '  doe jane'), which the
        // stored names do not hold: strip them, so the text matches the words it shows
        String name = text.replaceAll("^[\\s,#-]+|[\\s,#-]+$", "");
        if (nameWords(name) < 2)
            return Classified.refused("Type a full name: a first name and a last name");
        return new Classified(new Query(Kind.NAME, name), null);
    }

    /**
     * How many words {@code abc_names()} would keep: it turns {@code #} and {@code ,} into spaces, splits on spaces,
     * commas and hyphens, and drops the words that are numbers.
     */
    static int nameWords(String text) {
        int words = 0;
        for (String word : text.split("[\\s,#-]+"))
            if (!word.isEmpty() && !word.matches("-?[0-9]+"))
                words++;
        return words;
    }

    // ===== The search ==============================================================================================

    static Future<String> find(Object argument, EventErasureAccess.Caller caller) {
        Classified classified = classify(argument);
        if (classified.query() == null)
            return DpoErasure.refusal(QUERY_KEY, classified.refusal(), "find", caller.personId());
        Query query = classified.query();
        return EventErasureRunner.query(customersSql(query.kind()), query.parameter())
            .compose(customers -> EventErasureRunner.query(bookingsSql(query.kind()), query.parameter())
                .map(bookings -> {
                    Console.log(DpoErasure.LOG_PREFIX + "find (" + query.kind().json + ") by person " + caller.personId()
                                + ": " + rowCount(customers) + " customers, " + rowCount(bookings) + " bookings");
                    return replyJson(query.kind(), customers, bookings);
                }))
            .recover(cause -> DpoErasure.failure(cause, "find (" + query.kind().json + ")", caller.personId(),
                "The search could not be completed"));
    }

    private static int rowCount(QueryResult result) {
        return result == null ? 0 : result.getRowCount();
    }

    // ===== The reply ===============================================================================================

    /** {@code {kind, customers, bookings, truncated}}, each list cut at {@link #MAX_ROWS}. */
    static String replyJson(Kind kind, QueryResult customers, QueryResult bookings) {
        List<String> customerJson = new ArrayList<>();
        for (int row = 0; row < Math.min(rowCount(customers), MAX_ROWS); row++)
            customerJson.add(customerJson(customers, row));
        List<String> bookingJson = new ArrayList<>();
        for (int row = 0; row < Math.min(rowCount(bookings), MAX_ROWS); row++)
            bookingJson.add(bookingJson(bookings, row));
        return new EventErasureJson.ObjectWriter()
            .string("kind", kind.json)
            .raw("customers", EventErasureJson.array(customerJson))
            .raw("bookings", EventErasureJson.array(bookingJson))
            .bool("truncated", rowCount(customers) > MAX_ROWS || rowCount(bookings) > MAX_ROWS)
            .toJson();
    }

    private static String customerJson(QueryResult r, int row) {
        return new EventErasureJson.ObjectWriter()
            .number("personId", DpoErasure.number(r.getValue(row, 0)))
            .string("firstName", DpoErasure.text(r.getValue(row, 1)))
            .string("lastName", DpoErasure.text(r.getValue(row, 2)))
            .string("email", DpoErasure.text(r.getValue(row, 3)))
            .string("phone", DpoErasure.text(r.getValue(row, 4)))
            .bool("hasAccount", DpoErasure.bool(r.getValue(row, 5)))
            .number("linkedCopyOf", DpoErasure.number(r.getValue(row, 6)))
            .bool("removed", DpoErasure.bool(r.getValue(row, 7)))
            .number("bookings", DpoErasure.number(r.getValue(row, 8)))
            .string("lastEventStart", DpoErasure.text(r.getValue(row, 9)))
            .toJson();
    }

    private static String bookingJson(QueryResult r, int row) {
        return new EventErasureJson.ObjectWriter()
            .number("documentId", DpoErasure.number(r.getValue(row, 0)))
            .number("ref", DpoErasure.number(r.getValue(row, 1)))
            .string("eventName", DpoErasure.text(r.getValue(row, 2)))
            .string("eventStart", DpoErasure.text(r.getValue(row, 3)))
            .string("eventEnd", DpoErasure.text(r.getValue(row, 4)))
            .string("firstName", DpoErasure.text(r.getValue(row, 5)))
            .string("lastName", DpoErasure.text(r.getValue(row, 6)))
            .string("email", DpoErasure.text(r.getValue(row, 7)))
            .string("phone", DpoErasure.text(r.getValue(row, 8)))
            .bool("cancelled", DpoErasure.bool(r.getValue(row, 9)))
            .number("personId", DpoErasure.number(r.getValue(row, 10)))
            .bool("anonymised", DpoErasure.bool(r.getValue(row, 11)))
            .number("sameBooker", DpoErasure.number(r.getValue(row, 12)))
            .toJson();
    }
}

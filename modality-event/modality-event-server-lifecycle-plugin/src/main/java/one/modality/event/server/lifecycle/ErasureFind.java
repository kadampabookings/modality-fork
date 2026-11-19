package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Step 1 of erasing a person's data — who is it? — for {@link ErasureFindEndpoint}: the customers and the bookings an
 * email address, a booking reference or a full name points at, so the Data Protection Officer can pick the person to
 * erase without Registration or Customers access.
 *
 * <h3>Only the caller's organisations</h3>
 *
 * A Data Protection Officer finds the people their organisations hold — the organisations where they hold
 * DataProtection for the whole organisation, {@link EventErasureAccess#allOrganizationsHeld} (cached grants; the gate
 * in front asked fresh). A booking belongs to its event's organisation, else its own.
 * <ul>
 *   <li>A customer is listed when they have a booking or a volunteering application in one of them — or no booking
 *       and no application anywhere (an account alone belongs to no organisation). Each says how many of their
 *       bookings (theirs and their linked copies') are in those organisations ({@code bookingsHere}) and elsewhere
 *       ({@code bookingsElsewhere}): counts,
 *       no detail. {@code lastEventStart} is of their bookings here.</li>
 *   <li>A booking is listed only in those organisations, with its organisation; {@code sameBooker} groups those.</li>
 *   <li>{@code hiddenElsewhere}: whether anything matched only in other organisations — a yes or no, no count, so the
 *       page can say that such people are not shown.</li>
 * </ul>
 * The organisation ids are integers the framework answered, inlined in the statement ({@link #intArray}); the text
 * stays bound as $1.
 *
 * <h3>Three statements, each big table read once</h3>
 *
 * <ol>
 *   <li>{@link #matchSql}, the only one bound to the text: the person rows and the bookings it matches, as ids, and
 *       {@code hiddenElsewhere}. It reads what the kind needs once: person and frontend_account for an email (no
 *       index on either; the bookings by document_person_email_lower_idx), person and document for a name (no index on
 *       abc_names), document for a reference (no index on ref).</li>
 *   <li>{@link #customersSql} and {@link #bookingsSql}: the reply's rows, from those ids (inlined: integers the
 *       database answered) by primary key and indexes — each still applying the organisation rule itself, since it is
 *       the statement that returns the names. Skipped when the match found none.</li>
 * </ol>
 * Before (19 Nov 2026, measured on a copy of staging): a reference search read document 3 or 4 times, an email or name
 * search person 2 or 3 times (once a walk of person_pkey from the newest row) and frontend_account twice.
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
 *       the sorted, lower-case, unaccented form: {@code public.abc_names($1)}, computed ONCE per statement (a
 *       materialised CTE read by scalar subqueries; it is plpgsql), against person.abc_names and
 *       document.person_abc_names.</li>
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

    // ===== SQL — the text is bound as $1; the organisations and the ids found are inlined ===========================

    /** Where the caller's organisations are written into a statement ({@link #withCovered}). */
    static final String COVERED = "{{covered}}";

    /** A booking {@code d} (its event {@code e} joined) of one of the caller's organisations. */
    static final String COVERED_BOOKING = "coalesce(coalesce(e.organization_id, d.organization_id) = any(" + COVERED + "), false)";

    /**
     * The person rows of one customer: the row {@code personId} (a column or a literal) and its linked copies on other
     * accounts — whose bookings are theirs too (anonymise_person() takes them with the person).
     */
    static String personRows(String personId) {
        return "(select " + personId + " union all select lc.id from person lc where lc.account_person_id = " + personId + ")";
    }

    /** Whether the customer {@code personId} has a booking or an application in one of {@code covered} (an int[] expression). */
    static String recordsHere(String personId, String covered) {
        return "(exists (select 1 from document d left join event e on e.id = d.event_id where d.person_id in " + personRows(personId)
            + " and coalesce(coalesce(e.organization_id, d.organization_id) = any(" + covered + "), false))"
            + " or exists (select 1 from volunteering_application va where va.person_id in " + personRows(personId)
            + " and coalesce(va.organization_id = any(" + covered + "), false)))";
    }

    /** Whether the customer {@code personId} has a booking or an application anywhere. */
    static String recordsAnywhere(String personId) {
        return "(exists (select 1 from document d where d.person_id in " + personRows(personId) + ")"
            + " or exists (select 1 from volunteering_application va where va.person_id in " + personRows(personId) + "))";
    }

    /**
     * Each customer of {@code people} (an int[] expression) not anonymised: whether they have a record here (a booking
     * or an application in a covered organisation, theirs or a linked copy's) and whether they have one anywhere —
     * EXISTS, not counts: a reference matches hundreds of people. The rule every DPO reply about a person follows (the
     * check's people, the preview's members and namesakes). By primary key and indexes only.
     */
    private static String customerFlags(String people) {
        return "f as materialized (select p0.id, " + recordsHere("p0.id", COVERED) + " as here, " + recordsAnywhere("p0.id")
            + " as anywhere from person p0 where p0.anonymised_date is null and p0.id = any(" + people + "))";
    }

    // ── Step 1 of 3: the match, the one read of the big tables ──

    /**
     * The person rows an email names: their address, or the login of the account they own. Neither column is indexed
     * (no new index: David, 17 Nov 2026), so person and frontend_account are each read once, here.
     */
    private static final String EMAIL_PEOPLE = "select q.id from person q where lower(trim(q.email)) = lower(trim($1::text))"
        + " union select q.id from frontend_account fa join person q on q.frontend_account_id = fa.id and q.owner"
        + " where lower(trim(fa.username)) = lower(trim($1::text))";

    /** The name's sorted form, {@code n.v}: abc_names() is plpgsql, computed once in a CTE, never once per row. */
    private static final String NAME_FORM = "n as materialized (select public.abc_names($1::text) as v), ";

    private static final String NAME_PEOPLE = "select q.id from person q where q.abc_names = (select n.v from n)";

    /** A reference names the bookings' people: read from the matched bookings, not from document again. */
    private static final String REF_PEOPLE = "select m.person_id from m";

    /**
     * lower(person_email), not lower(trim(…)): the form document_person_email_lower_idx indexes. With trim, the planner
     * walks every booking backwards by id looking for 51 matches: 17 s on staging (294k bookings) for an address that
     * has none, against 7 ms. Staging held no address stored with spaces around it (17 Nov 2026; anonymised, so no proof
     * for production); the check (ErasureResidueCheck) compares trimmed, so such a booking would still show there.
     */
    private static final String EMAIL_MATCH = "lower(d.person_email) = lower(trim($1::text))";
    private static final String NAME_MATCH = "d.person_abc_names = (select n.v from n)";
    private static final String REF_MATCH = "d.ref = $1::int";

    /**
     * Step 1: the ids of the person rows ({@code ids}) and of the bookings ({@code m}) the text matches, comma-separated,
     * and {@code hiddenElsewhere} — whether anything matched only in other organisations: a customer with records
     * elsewhere and none here, or a booking of another organisation (or of none). Each a MATERIALIZED CTE (the planner
     * cannot see through it), so each table is read once whatever the plan. A booking reference is numbered per event,
     * so it matches hundreds of people: their flags are EXISTS by index, never counts (counting the bookings of all of
     * them took 4.5 s on staging).
     */
    static String matchSql(Kind kind, Collection<Integer> covered) {
        String people = switch (kind) {
            case EMAIL -> EMAIL_PEOPLE;
            case REF -> REF_PEOPLE;
            case NAME -> NAME_PEOPLE;
        };
        String match = switch (kind) {
            case EMAIL -> EMAIL_MATCH;
            case REF -> REF_MATCH;
            case NAME -> NAME_MATCH;
        };
        return withCovered("with " + (kind == Kind.NAME ? NAME_FORM : "")
            + "m as materialized (select d.id, " + COVERED_BOOKING + " as here, d.person_id"
            + " from document d left join event e on e.id = d.event_id where " + match + "),"
            + " ids(id) as materialized (" + people + "),"
            + " " + customerFlags("array(select ids.id from ids)")
            + " select array_to_string(array(select distinct ids.id from ids where ids.id is not null order by 1), ','),"
            + " array_to_string(array(select m.id from m order by m.id), ','),"
            + " exists (select 1 from f where f.anywhere and not f.here) or exists (select 1 from m where not m.here)", covered);
    }

    // ── Steps 2 and 3: the reply's rows, from the ids found ──

    /**
     * Step 2: one customer of {@code people} (the ids step 1 found) per row: who, whether they own an account, the row
     * they are a linked copy of, and their bookings — only those with a record here or none anywhere, newest first, cut
     * to the rows answered before their bookings are counted. The cut is made on the flags, never by walking person in
     * id order (that read all of person_pkey for an address with two people: 22,335 pages, 19 Nov 2026).
     */
    static String customersSql(Collection<Integer> people, Collection<Integer> covered) {
        return withCovered("with " + customerFlags(intArray(people))
            + " select p.id, p.first_name, p.last_name, p.email, p.phone, (p.frontend_account_id is not null and p.owner),"
            + " p.account_person_id, p.removed, b.n, to_char(b.last_start, 'YYYY-MM-DD'), b.here"
            + " from (select f.id from f where f.here or not f.anywhere order by f.id desc limit " + (MAX_ROWS + 1) + ") s"
            + " join person p on p.id = s.id"
            + " cross join lateral (select count(*) as n, count(*) filter (where " + COVERED_BOOKING + ") as here,"
            + " max(e.start_date) filter (where " + COVERED_BOOKING + ") as last_start"
            + " from document d left join event e on e.id = d.event_id where d.person_id in " + personRows("p.id") + ") b"
            + " order by p.id desc", covered);
    }

    /**
     * Whether anonymise_booker() (V0163) can take a booking along with others with no tick: no customer record, an
     * address (an '@' after the first character, its v_address) and a name of two words or more (its v_name). Two such
     * bookings with the same address (trimmed, any case) and the same abc_names are one booker: its 'auto' rule.
     */
    private static final String SAME_BOOKER_ELIGIBLE =
        "(d.person_id is null and position('@' in coalesce(trim(d.person_email), '')) > 1"
        + " and position(' ' in trim(coalesce(d.person_abc_names, ''))) > 0)";

    /**
     * Step 3: one booking of {@code bookings} (the ids step 1 found) per row, of the caller's organisations only: its
     * event, who it is for, whether anonymise_booker() already took it (its own test), and which booker without an
     * account it belongs to ({@link #SAME_BOOKER_ELIGIBLE}): bookings with the same number are erased together. A window
     * over the rows found, before the limit, so a booker's bookings get one number. By primary key.
     */
    static String bookingsSql(Collection<Integer> bookings, Collection<Integer> covered) {
        return withCovered("select d.id, d.ref, e.name, to_char(e.start_date, 'YYYY-MM-DD'), to_char(e.end_date, 'YYYY-MM-DD'),"
            + " d.person_first_name, d.person_last_name, d.person_email, d.person_phone, d.cancelled, d.person_id,"
            + " coalesce(d.person_first_name = 'Anonymised' and d.person_last_name like '#%' and d.person_email is null, false),"
            + " case when " + SAME_BOOKER_ELIGIBLE + " then dense_rank() over (partition by " + SAME_BOOKER_ELIGIBLE
            + " order by lower(trim(d.person_email)), d.person_abc_names) end"
            + ", coalesce(e.organization_id, d.organization_id), o.name"
            + " from document d left join event e on e.id = d.event_id"
            + " left join organization o on o.id = coalesce(e.organization_id, d.organization_id)"
            + " where " + COVERED_BOOKING + " and d.id = any(" + intArray(bookings) + ")"
            + " order by d.id desc limit " + (MAX_ROWS + 1), covered);
    }

    /** The statement with the caller's organisations written in, as an int[] literal of the ids. */
    static String withCovered(String sql, Collection<Integer> covered) {
        return sql.replace(COVERED, intArray(covered));
    }

    /** {@code '{151,187}'::int[]} — sorted, de-duplicated, nulls left out; {@code '{}'::int[]} for none. Integers only. */
    static String intArray(Collection<Integer> ids) {
        StringBuilder sb = new StringBuilder("'{");
        for (Integer id : new java.util.TreeSet<>(ids.stream().filter(java.util.Objects::nonNull).toList()))
            sb.append(sb.length() > 2 ? "," : "").append(id.intValue());
        return sb.append("}'::int[]").toString();
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
        return EventErasureAccess.allOrganizationsHeld(caller).compose(covered ->
            EventErasureRunner.query(matchSql(query.kind(), covered), query.parameter()).compose(match -> {
                Set<Integer> people = matchedIds(match, 0), bookings = matchedIds(match, 1);
                if (people == null || bookings == null)
                    return Future.<String>failedFuture("The search's match did not answer ids");
                boolean hiddenElsewhere = rowCount(match) > 0 && DpoErasure.bool(match.getValue(0, 2));
                // Nothing found, nothing to read: the reply's lists are empty
                return (people.isEmpty() ? Future.<QueryResult>succeededFuture(null) : EventErasureRunner.query(customersSql(people, covered)))
                    .compose(customers -> (bookings.isEmpty() ? Future.<QueryResult>succeededFuture(null)
                        : EventErasureRunner.query(bookingsSql(bookings, covered)))
                    .map(bookingRows -> {
                        Console.log(DpoErasure.LOG_PREFIX + "find (" + query.kind().json + ") by person " + caller.personId()
                                    + " in " + covered.size() + " organisations: " + rowCount(customers) + " customers, "
                                    + rowCount(bookingRows) + " bookings" + (hiddenElsewhere ? ", some elsewhere" : ""));
                        return replyJson(query.kind(), customers, bookingRows, hiddenElsewhere);
                    }));
            }))
            .recover(cause -> DpoErasure.failure(cause, "find (" + query.kind().json + ")", caller.personId(),
                "The search could not be completed"));
    }

    /** Column {@code column} of the match's row: its comma-separated ids; empty when no row, null when not ids. */
    static Set<Integer> matchedIds(QueryResult match, int column) {
        return rowCount(match) == 0 ? Set.of() : PersonAnonymisationRunner.idSet(DpoErasure.text(match.getValue(0, column)));
    }

    private static int rowCount(QueryResult result) {
        return result == null ? 0 : result.getRowCount();
    }

    // ===== The reply ===============================================================================================

    /** {@code {kind, customers, bookings, truncated, hiddenElsewhere}}, each list cut at {@link #MAX_ROWS}. */
    static String replyJson(Kind kind, QueryResult customers, QueryResult bookings, boolean hiddenElsewhere) {
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
            .bool("hiddenElsewhere", hiddenElsewhere)
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
            .number("bookingsHere", DpoErasure.number(r.getValue(row, 10)))
            .number("bookingsElsewhere", elsewhere(r.getValue(row, 8), r.getValue(row, 10)))
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
            .number("organizationId", DpoErasure.number(r.getValue(row, 13)))
            .string("organizationName", DpoErasure.text(r.getValue(row, 14)))
            .toJson();
    }

    /** All bookings minus those here, or null when either is unknown. */
    static Number elsewhere(Object all, Object here) {
        return all instanceof Number a && here instanceof Number h ? (Number) (a.longValue() - h.longValue()) : null;
    }
}

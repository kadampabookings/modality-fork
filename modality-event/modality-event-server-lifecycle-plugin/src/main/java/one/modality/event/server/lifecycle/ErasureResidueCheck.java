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
 * Step 3 of erasing a person's data — did it work? — for {@link ErasureCheckEndpoint}: an independent look, after (or
 * before) an anonymisation, at whether KBS still holds the identifiers the Data Protection Officer captured at step 1.
 * Independent of the functions on purpose: it asks the tables, not the functions' own grids.
 *
 * <h3>The argument: {@code [emails[], phones[], personIds[], documentIds[], subject]}</h3>
 *
 * Each array may be null or absent, and holds at most {@link #MAX_ENTRIES} entries. An email is a string, trimmed, 3
 * to 200 characters, with an {@code @}, compared as {@code lower(trim(column)) = lower(trim(email))}. A phone is a
 * string of at most 50 characters, compared on its last 9 digits (digits only, as V0153 matches phones, so
 * {@code +44 7700 900123} and {@code 07700 900123} meet); a phone with fewer than 9 digits is IGNORED, not refused.
 * Ids are positive integers. {@code subject}, optional, is who was erased: {@code ["person", personId]} or
 * {@code ["booker", documentId]}, or null. Anything else, or nothing left to check, is refused
 * {@code [ErasureCheckError]}.
 *
 * <h3>Other organisations</h3>
 *
 * A Data Protection Officer erases what their organisations hold; another organisation's bookings are KEPT (V0175).
 * The areas count only what the caller's organisations hold — {@link EventErasureAccess#allOrganizationsHeld}, the
 * cached grants (the gate in front asked fresh) —: a booking of another organisation (or of none) is left out of
 * {@code bookings} and counted in {@code keptElsewhere} instead, which does not fail the verdict; so are, in
 * {@code emails} and {@code history}, the rows tied to such a booking, and another organisation's volunteering
 * applications. Rows tied to no booking are always counted.
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
 * <p>{@code people} / {@code bookings}: the given ids as they are now, for the page's before/after, each booking with
 * its organisation and whether the caller covers it. With a {@code subject}, {@code bookings} also holds every
 * booking anonymised under that subject — found by the last name the functions write on them
 * ({@link #BOOKINGS_SQL}) — so the page shows them all, the booker's other bookings included, which it never
 * named. Nothing is recorded: the check only answers. An id is no key to another organisation's data: a person comes
 * back only if they are the subject or somebody the caller could find ({@link #PEOPLE_SQL}), and another
 * organisation's booking with its id, ref and organisation only ({@link #bookingJson}).
 *
 * <h3>How it runs</h3>
 *
 * On a dedicated connection ({@link EventErasureRunner#connect()}) rather than the pool, because only a transaction
 * of its own can bound every statement with a {@code statement_timeout} ({@link #STATEMENT_TIMEOUT}): history and
 * recipient have no index on the columns read, and are the two biggest tables. One {@code REPEATABLE READ READ ONLY}
 * transaction, so every area describes the same moment; rolled back, then closed. A statement that runs out of time
 * fails the whole check ({@code [ErasureSqlError]}): a partial answer could read as "nothing left". Each table is
 * read once: person (customers, then the given people by primary key), frontend_account (logins), document (one
 * statement for the bookings, what other organisations keep and the subject's erased bookings; then the given ids by
 * primary key), recipient, history, magic_link and volunteering_application (their area each).
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

    /** {@link #parse}'s answer: the identifiers and who was erased (or null), or the refusal's text. */
    record Parsed(Identifiers identifiers, PersonAnonymisationRunner.Subject subject, String refusal) {
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
        + " $2::text[] as phones, $3::int[] as covered from (select array(select lower(trim(x)) from unnest($1::text[]) x) as e) s) ";

    /** Whether booking {@code d} (its event {@code e} joined) belongs to one of the covered organisations ($3). */
    private static final String COVERED_BOOKING = "coalesce(coalesce(e.organization_id, d.organization_id) = any(q.covered), false)";

    /** Whether the booking {@code documentId} (a column) is another organisation's, or one with no organisation. */
    private static String bookingElsewhere(String documentId) {
        return "exists (select 1 from document d left join event e on e.id = d.event_id where d.id = " + documentId
               + " and not " + COVERED_BOOKING + ")";
    }

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

    private static final String BOOKING_MATCH =
        "(lower(trim(d.person_email)) = any(q.emails) or (cardinality(q.phones) > 0 and " + lastNineDigits("d.person_phone") + " = any(q.phones)))";

    /**
     * Whether booking {@code d} is the covered organisations', its event's organisation read by a correlated subquery:
     * evaluated for the matched rows only. Joining event in the FROM made the bookings area scan 4 times slower on
     * staging (2.1 s against 0.55 s, 19 Nov 2026), the join being built for every booking.
     */
    private static final String COVERED_MATCHED_BOOKING =
        "coalesce(coalesce((select e.organization_id from event e where e.id = d.event_id), d.organization_id) = any(q.covered), false)";

    static final int MAX_ERASED_BOOKINGS = 50;

    /**
     * The bookings, in ONE read of document — no index serves a trimmed address, a phone's digits or a last name, and
     * three statements read it three times before (19 Nov 2026) — into a MATERIALIZED CTE of the rows that concern the
     * check, then three answers from it:
     * <ol>
     *   <li>the covered organisations' bookings still holding an identifier, capped at {@link #CAP} (the area);</li>
     *   <li>every other booking still holding one: another organisation's, kept on purpose (V0175) — counted, not a
     *       failure ({@code keptElsewhere}), capped too;</li>
     *   <li>the newest {@link #MAX_ERASED_BOOKINGS} anonymised under the subject, comma-separated ids, found by the last
     *       name the functions write on them ($4, {@link #erasedTag}; null with no subject, so none): '#B' + the seed
     *       booking for a booker (V0163), '#' + the person for a customer (V0153/V0164). None before the erasure ran.</li>
     * </ol>
     */
    static final String BOOKINGS_SQL = IDENTIFIERS_CTE
        + ", m as materialized (select d.id, " + BOOKING_MATCH + " as matched, " + COVERED_MATCHED_BOOKING + " as here,"
        + " d.person_last_name = $4::text as erased from document d, q where " + BOOKING_MATCH + " or d.person_last_name = $4::text)"
        + " select (select count(*) from (select 1 from m where m.matched and m.here limit " + CAP + ") t),"
        + " (select count(*) from (select 1 from m where m.matched and not m.here limit " + CAP + ") t),"
        + " array_to_string(array(select m.id from m where m.erased order by m.id desc limit " + MAX_ERASED_BOOKINGS + "), ',')";

    /** A mail's recipients, but those of another organisation's booking's mails (kept with it). */
    static final String EMAILS_SQL = capped("recipient r", "lower(trim(r.email)) = any(q.emails)"
        + " and not exists (select 1 from mail m where m.id = r.mail_id and " + bookingElsewhere("m.document_id") + ")");

    /**
     * A guest is recorded as 'guest:' + the first 58 characters of their address (64 in all); an SSO login as the
     * address. Another organisation's booking's history is kept with it.
     */
    static final String HISTORY_SQL = capped("history h", "lower(trim(h.username)) = any(q.usernames)"
        + " and not " + bookingElsewhere("h.document_id"));

    static final String MAGIC_LINKS_SQL = capped("magic_link ml",
        "lower(trim(ml.email)) = any(q.emails) or lower(trim(ml.old_email)) = any(q.emails)");

    /** The covered organisations' applications (another organisation's are kept, V0175). */
    static final String VOLUNTEERING_SQL = capped("volunteering_application va",
        "(lower(trim(va.email)) = any(q.emails) or (cardinality(q.phones) > 0 and " + lastNineDigits("va.phone") + " = any(q.phones)))"
        + " and coalesce(va.organization_id = any(q.covered), false)");

    /**
     * The given people as they are now — only the subject ($2, the person erased; 0 for a booker) and the people the
     * caller could find themselves ({@link ErasureFind}'s rule over the covered organisations, $3: a record in one of
     * them, or none anywhere, linked copies counted). Another organisation's customer named by id gets nothing back.
     */
    static final String PEOPLE_SQL =
        "select p.id, p.first_name, p.last_name, p.email, p.removed from person p where p.id = any($1::int[])"
        + " and (p.id = $2::int or " + ErasureFind.recordsHere("p.id", "$3::int[]") + " or not " + ErasureFind.recordsAnywhere("p.id") + ")"
        + " order by p.id";

    /** The last name an anonymisation writes on a subject's bookings. */
    static String erasedTag(PersonAnonymisationRunner.Subject subject) {
        return (subject.isBooker() ? "#B" : "#") + subject.id();
    }

    static final String DOCUMENTS_SQL =
        "select d.id, d.ref, d.person_first_name, d.person_last_name, d.person_email, nullif(trim(d.person_phone), '') is not null,"
        + " d.price_net, d.cancelled, coalesce(e.organization_id, d.organization_id), o.name"
        + " from document d left join event e on e.id = d.event_id"
        + " left join organization o on o.id = coalesce(e.organization_id, d.organization_id)"
        + " where d.id = any($1::int[]) order by d.id";

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

    static final Area BOOKINGS_AREA = new Area("bookings", BOOKINGS_SQL, true);

    /** In the reply's order. */
    static final List<Area> AREAS = List.of(
        new Area("customers", CUSTOMERS_SQL, true),
        new Area("logins", LOGINS_SQL, false),
        BOOKINGS_AREA,
        new Area("emails", EMAILS_SQL, false),
        new Area("history", HISTORY_SQL, false),
        new Area("magicLinks", MAGIC_LINKS_SQL, false),
        new Area("volunteering", VOLUNTEERING_SQL, true));

    // ===== The argument ============================================================================================

    static Parsed parse(Object argument) {
        Object[] arguments = EventErasureArguments.array(argument);
        if (arguments.length > 5)
            return Parsed.refused("Send at most four lists and a subject: emails, phones, person ids, booking ids");
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
        Object subjectValue = EventErasureArguments.at(arguments, 4);
        PersonAnonymisationRunner.Subject subject = subject(subjectValue);
        if (subjectValue != null && subject == null)
            return Parsed.refused("The subject must be [\"person\", id], [\"booker\", documentId] or null");
        List<Integer> personIds = ids(lists[2]);
        List<Integer> documentIds = ids(lists[3]);
        if (personIds == null || documentIds == null)
            return Parsed.refused("An id is malformed");
        Identifiers identifiers = new Identifiers(List.copyOf(emails.values()), List.copyOf(phones), personIds, documentIds);
        if (identifiers.isEmpty())
            return Parsed.refused("Nothing to check: give an email, a phone of 9 digits or more, or an id");
        return new Parsed(identifiers, subject, null);
    }

    /** {@code ["person", id]} or {@code ["booker", documentId]}; anything else null. */
    static PersonAnonymisationRunner.Subject subject(Object value) {
        if (!(value instanceof Object[] pair) || pair.length != 2)
            return null;
        Integer id = EventErasureArguments.positiveId(pair[1]);
        if (id == null)
            return null;
        return "person".equals(pair[0]) ? PersonAnonymisationRunner.Subject.person(id)
            : "booker".equals(pair[0]) ? PersonAnonymisationRunner.Subject.booker(id) : null;
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
        /** Other organisations' bookings still holding an identifier (capped); null when no area concerned them. */
        Long keptElsewhere;
    }

    static Future<String> check(Object argument, EventErasureAccess.Caller caller) {
        Parsed parsed = parse(argument);
        if (parsed.identifiers() == null)
            return DpoErasure.refusal(CHECK_KEY, parsed.refusal(), "check", caller.personId());
        Identifiers identifiers = parsed.identifiers();
        PersonAnonymisationRunner.Subject subject = parsed.subject();
        Findings findings = new Findings();
        long start = System.nanoTime();
        return EventErasureAccess.allOrganizationsHeld(caller).compose(covered -> {
            io.vertx.core.Future<Findings> done;
            try {
                done = EventErasureRunner.connect().compose(connection -> run(connection, identifiers, subject, covered, findings)
                    .eventually(connection::close));
            } catch (RuntimeException e) {
                done = io.vertx.core.Future.failedFuture(e);
            }
            return VertxAsync.toWebfxFuture(done).map(ignored -> {
                StringBuilder line = new StringBuilder(DpoErasure.LOG_PREFIX).append("check by person ").append(caller.personId())
                    .append(" (").append(identifiers.emails().size()).append(" emails, ").append(identifiers.phones().size())
                    .append(" phones, ").append(identifiers.personIds().size()).append(" people, ")
                    .append(identifiers.documentIds().size()).append(" bookings, ").append(covered.size())
                    .append(" organisations) in ").append((System.nanoTime() - start) / 1_000_000).append(" ms:");
                findings.found.forEach((area, n) -> line.append(' ').append(area).append('=').append(n));
                if (findings.keptElsewhere != null)
                    line.append(" keptElsewhere=").append(findings.keptElsewhere);
                Console.log(line.toString());
                return replyJson(findings, covered);
            });
        }).recover(cause -> {
            boolean timedOut = "57014".equals(EventErasureRunner.sqlStateOf(cause));
            return DpoErasure.failure(cause, "check (" + findings.area + ")", caller.personId(), timedOut
                ? "The check took too long, so nothing can be said yet: try again later"
                : "The check could not be completed");
        });
    }

    private static io.vertx.core.Future<Findings> run(PgConnection connection, Identifiers identifiers,
                                                      PersonAnonymisationRunner.Subject subject, Set<Integer> covered,
                                                      Findings findings) {
        Set<Integer> documentIds = new LinkedHashSet<>(identifiers.documentIds()); // + the subject's erased bookings, below
        connection.noticeHandler(notice -> { });
        String[] emails = identifiers.emails().toArray(new String[0]);
        String[] phones = identifiers.phones().toArray(new String[0]);
        Integer[] organizations = new java.util.TreeSet<>(covered).toArray(new Integer[0]);
        io.vertx.core.Future<?> chain = connection.query(BEGIN_SQL).execute()
            .compose(ignored -> connection.query(SETTINGS_SQL).execute());
        for (Area area : AREAS)
            chain = chain.compose(ignored -> {
                if (area == BOOKINGS_AREA)
                    return bookings(connection, identifiers, subject, emails, phones, organizations, findings, documentIds);
                // An area none of the identifiers concerns is NOT searched, and is left out of the reply: answering it
                // 0 would read as "nothing left" for what was never looked at (a check sent ids only, after a reload)
                if (!area.concerns(identifiers))
                    return io.vertx.core.Future.succeededFuture();
                findings.area = area.name();
                return connection.preparedQuery(area.sql()).execute(Tuple.tuple().addArrayOfString(emails).addArrayOfString(phones)
                        .addArrayOfInteger(organizations))
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
                    .execute(Tuple.tuple().addArrayOfInteger(identifiers.personIds().toArray(new Integer[0]))
                        .addValue(subject != null && !subject.isBooker() ? subject.id() : 0).addArrayOfInteger(organizations))
                    .map(rows -> {
                        for (Row row : rows)
                            findings.people.add(personJson(row.getInteger(0), row.getString(1), row.getString(2),
                                row.getString(3), Boolean.TRUE.equals(row.getBoolean(4))));
                        return null;
                    });
            })
            .compose(ignored -> {
                if (documentIds.isEmpty())
                    return io.vertx.core.Future.succeededFuture();
                findings.area = "bookings (ids)";
                return connection.preparedQuery(DOCUMENTS_SQL)
                    .execute(Tuple.tuple().addArrayOfInteger(documentIds.toArray(new Integer[0])))
                    .map(rows -> {
                        for (Row row : rows) {
                            Integer organization = row.getInteger(8);
                            findings.bookings.add(bookingJson(row.getInteger(0), row.getInteger(1), row.getString(2),
                                row.getString(3), row.getString(4), Boolean.TRUE.equals(row.getBoolean(5)),
                                row.getInteger(6), Boolean.TRUE.equals(row.getBoolean(7)), organization, row.getString(9),
                                organization != null && covered.contains(organization)));
                        }
                        return null;
                    });
            })
            .compose(ignored -> {
                findings.area = "rollback";
                return connection.query("ROLLBACK").execute();
            })
            .map(ignored -> findings);
    }

    /**
     * {@link #BOOKINGS_SQL}, in the bookings area's turn: the area and {@code keptElsewhere} when the identifiers
     * concern bookings (an email, or a phone), the subject's erased bookings when there is a subject — run when either
     * is wanted, its answers kept only for what was. With no email and no phone it matches nothing, and only finds the
     * erased bookings.
     */
    private static io.vertx.core.Future<Void> bookings(PgConnection connection, Identifiers identifiers,
                                                       PersonAnonymisationRunner.Subject subject, String[] emails,
                                                       String[] phones, Integer[] organizations, Findings findings,
                                                       Set<Integer> documentIds) {
        boolean concerned = BOOKINGS_AREA.concerns(identifiers);
        if (!concerned && subject == null)
            return io.vertx.core.Future.succeededFuture();
        findings.area = BOOKINGS_AREA.name();
        return connection.preparedQuery(BOOKINGS_SQL).execute(Tuple.tuple().addArrayOfString(emails).addArrayOfString(phones)
                .addArrayOfInteger(organizations).addString(subject == null ? null : erasedTag(subject)))
            .map(rows -> {
                Row row = rows.iterator().next();
                if (concerned) {
                    Long here = row.getLong(0), elsewhere = row.getLong(1);
                    findings.found.put(BOOKINGS_AREA.name(), Math.min(here == null ? 0 : here, CAP));
                    // The area's other half: what other organisations keep, a number and nothing more
                    findings.keptElsewhere = Math.min(elsewhere == null ? 0 : elsewhere, CAP);
                }
                if (subject != null) {
                    String erased = row.getString(2);
                    for (String id : erased == null ? new String[0] : erased.split(","))
                        if (!id.isBlank())
                            documentIds.add(Integer.valueOf(id.trim()));
                }
                return null;
            });
    }

    // ===== The reply ===============================================================================================

    /**
     * {@code {areas: [{area, found}], keptElsewhere, people: [...], bookings: [...]}}, the areas in {@link #AREAS}'
     * order — only those actually searched: a missing area means "not looked at", never "nothing found".
     * {@code keptElsewhere}: other organisations' bookings still holding an identifier (capped at {@link #CAP}), null
     * when the bookings were not looked at.
     */
    static String replyJson(Findings findings, Set<Integer> covered) {
        List<String> areas = new ArrayList<>();
        for (Area area : AREAS)
            if (findings.found.containsKey(area.name()))
                areas.add(new EventErasureJson.ObjectWriter()
                    .string("area", area.name())
                    .number("found", Math.min(findings.found.get(area.name()), CAP))
                    .toJson());
        return new EventErasureJson.ObjectWriter()
            .raw("areas", EventErasureJson.array(areas))
            .number("keptElsewhere", findings.keptElsewhere)
            .raw("people", EventErasureJson.array(findings.people))
            .raw("bookings", EventErasureJson.array(findings.bookings))
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

    /**
     * One booking as it is now. Another organisation's (or one with none) carries its id, ref, organisation and
     * {@code covered: false} only: no name, address, phone flag, price or state — a DPO learns that it exists, nothing
     * of who it is for.
     */
    static String bookingJson(Integer documentId, Integer ref, String firstName, String lastName, String email,
                              boolean hasPhone, Integer priceNet, boolean cancelled, Integer organizationId,
                              String organizationName, boolean covered) {
        if (!covered) {
            firstName = lastName = email = null;
            hasPhone = cancelled = false;
            priceNet = null;
        }
        return new EventErasureJson.ObjectWriter()
            .number("documentId", documentId)
            .number("ref", ref)
            .string("firstName", firstName)
            .string("lastName", lastName)
            .string("email", email)
            .bool("hasPhone", hasPhone)
            .number("priceNet", priceNet)
            .bool("cancelled", cancelled)
            .number("organizationId", organizationId)
            .string("organizationName", organizationName)
            .bool("covered", covered)
            .toJson();
    }
}

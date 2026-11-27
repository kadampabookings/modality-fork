package one.modality.crm.server.person;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.stack.db.query.QueryResult;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * How many people are present at a centre, arrive there and leave it, on each day of a window, per event — for
 * the back office's /registration-arrivals page (docs/design/registration-arrivals.md).
 *
 * <h3>Counts of every booking, deliberately</h3>
 *
 * <p>The counts include bookings the caller's read scope does not reach: a festival hosted at a centre belongs to
 * another organisation, and its bookings are under that organisation, so the host's staff may not read them —
 * but they do need to know how busy their own building will be. Bruno decided this on 2026-11-27. What crosses
 * the scope is a NUMBER per event and day, never a booking, an id or a name: the page reads the rows it may show
 * through ordinary DQL, which the read scope filters, and labels the difference per event as "at an event you
 * cannot open". Do not add a booking id, a person or a ref to this reply: that would turn a count into a read
 * the scope was built to refuse.
 *
 * <h3>The rule, and why each part of it is there (staging, 2026-11-27)</h3>
 *
 * <ul>
 *   <li><b>Present</b> on a day: a present attendance on that day, on a line that is not cancelled, whose item
 *       family is not {@code record} (KBS2's rule), on a booking that is not cancelled (cancelled bookings keep
 *       their attendance present: 718 of 3,370 meal rows in a fortnight at centre 2) and is in person (30% of
 *       bookings with attendance are online). <b>An accommodation night also makes the next day present</b>: a
 *       night D means leaving on D+1, and for 375 of 8,533 bookings with accommodation a night is the last
 *       thing booked.</li>
 *   <li><b>Arriving</b>: present, and not the day before. <b>Leaving</b>: present, and not the day after. A
 *       booking with gaps (an onsite class series) arrives and leaves once per run, as Bruno confirmed.</li>
 *   <li><b>At the centre</b>: an event whose venue (its own, or its repeated event's, as
 *       {@code event_letter_venue_id()} reads it) belongs to the organisation, or, without a venue, which belongs
 *       to it. KBS2 went by the owner alone, which hid the summer festival (organisation 1, held at 151) from
 *       its host.</li>
 * </ul>
 *
 * <p><b>The venue rule trusts its inputs to be written by their owners</b>: {@code event.venue_id},
 * {@code event.repeated_event_id} and {@code site.organization_id}. Whoever can point another organisation's event
 * at one of their own sites, or move another organisation's site to their own, widens these counts to that event's
 * bookings (numbers only, and a visible change). Event and Site writes have no owner check on the DQL write path yet
 * (security review, 2026-11-27): this endpoint depends on that work, it does not fix it.
 *
 * <h3>Why it is fast enough (measured on staging, 2026-11-27)</h3>
 *
 * <p>It starts from the centre's events, bounded by {@link #EVENT_END_SLACK_DAYS}, then their bookings and lines,
 * and reads attendance by index only ({@code attendance_document_line_date_present_idx}). Two weeks at the
 * festival's peak (3,304 people a day): 1.6 s with the pages cached, 37 s on staging's first run (reading the
 * 13,662 bookings and 31,332 lines of the centre's 83 events from disk); two weeks at centre 2: 1.2 s. Every shape that
 * started from attendance by date read 237,000 rows scattered over the heap and timed out at 60 s on staging.
 * Bounding events by their dates is NOT a substitute for the end-date slack: a long-running event's dates move
 * when it is rolled over (event 480 has 30,490 attendance rows before its current start).
 *
 * @author Claude Code
 */
final class ArrivalsByDay {

    private ArrivalsByDay() {
    }

    /** The longest window asked at once. The page asks one or two weeks; a month leaves room and bounds the cost. */
    static final int MAX_WINDOW_DAYS = 31;

    /**
     * How long after an event's end date its attendance may still run. Measured on staging over 12 months of
     * in-person attendance: 4,241 rows after their event's end, 24 more than 30 days after, none more than 90.
     * It bounds the events read, which is what makes the query affordable; if attendance ever runs longer, the
     * late days are missed rather than the query slowed, so measure before lowering it.
     */
    static final int EVENT_END_SLACK_DAYS = 90;

    /**
     * How far back and ahead of today a window may lie. The page pages around today: a year back compares with last
     * year's same weeks, two ahead covers what is booked. The bound is what keeps the read affordable: events are
     * chosen by {@code end_date >= from - EVENT_END_SLACK_DAYS} with no upper bound (rolled-over events), so a window
     * in 1900 would read every event the centre ever had — a cheap way for any /registration holder to load the
     * database (security review, 2026-11-27). Days, measured in the server's zone: a centre's today differs by a day
     * at most, well inside the margins.
     */
    static final int MAX_DAYS_BACK = 400;
    static final int MAX_DAYS_AHEAD = 730;

    /** What was asked: the centre and the first and last day of the window, both included. */
    record Window(int organizationId, LocalDate from, LocalDate to) {
    }

    /**
     * The window an argument asks for, or null when it is not one: {@code [organizationId, fromDate, toDate]}
     * exactly, a positive integer id, two dates (a {@code LocalDate}, as a Temporal date travels, or its ISO
     * text), the first not after the last, at most {@link #MAX_WINDOW_DAYS} days, starting no more than
     * {@link #MAX_DAYS_BACK} days before today and ending no more than {@link #MAX_DAYS_AHEAD} after it.
     */
    static Window windowOf(Object argument) {
        return windowOf(LocalDate.now(), argument);
    }

    /** {@link #windowOf(Object)} against a given today, so a check can hold it. */
    static Window windowOf(LocalDate today, Object argument) {
        if (!(argument instanceof Object[] arguments) || arguments.length != 3)
            return null;
        Integer organizationId = positiveIntOf(arguments[0]);
        LocalDate from = dateOf(arguments[1]);
        LocalDate to = dateOf(arguments[2]);
        if (organizationId == null || from == null || to == null || to.isBefore(from)
            || ChronoUnit.DAYS.between(from, to) + 1 > MAX_WINDOW_DAYS
            || from.isBefore(today.minusDays(MAX_DAYS_BACK)) || to.isAfter(today.plusDays(MAX_DAYS_AHEAD)))
            return null;
        return new Window(organizationId, from, to);
    }

    /** A positive integer that fits an id column, from whatever width of number the wire delivered. */
    static Integer positiveIntOf(Object value) {
        if (!(value instanceof Number number) || value instanceof Double || value instanceof Float)
            return null;
        long id = number.longValue();
        return id > 0 && id <= Integer.MAX_VALUE ? (int) id : null;
    }

    /**
     * A date from what the wire carries, with the year bounded as {@code PersonDetailsRules.firstNotADate} bounds
     * it: {@code LocalDate} parses years Postgres cannot store, and one would surface as raw Postgres text.
     */
    static LocalDate dateOf(Object value) {
        LocalDate day = null;
        if (value instanceof LocalDate already)
            day = already;
        else if (value instanceof CharSequence text) {
            try {
                day = LocalDate.parse(text.toString().trim());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return day == null || day.getYear() < 1900 || day.getYear() > 2200 ? null : day;
    }

    /**
     * The counts per day and event. Parameters: {@code $1} organisation, {@code $2} first day, {@code $3} last day.
     *
     * <p>Attendance is read from two days before the window (a night two days before makes the day before
     * present, which decides whether the first day is an arrival) to one day after (which decides whether the
     * last day is a departure). Days with nobody are not returned.
     *
     * <p>{@code l} is materialised so the lines are filtered (family, cancelled, dates) once each, before the
     * attendance join; left to itself the planner joined item and family per attendance row, 160,000 times. The
     * event's name is joined after grouping, once per event and day rather than once per person and day.
     */
    static final String COUNTS_SQL = """
        with ev as (
          select e.id from event e
            left join event r on r.id = e.repeated_event_id
            left join site s on s.id = coalesce(e.venue_id, r.venue_id)
          where coalesce(s.organization_id, e.organization_id) = $1::int
            and (e.end_date is null or e.end_date >= $2::date - %d)),
        l as materialized (
          select dl.id, d.id as doc, d.event_id as ev, f.code = 'acco' as acco
          from document d
            join document_line dl on dl.document_id = d.id
            join item i on i.id = dl.item_id
            join item_family f on f.id = i.family_id
          where d.event_id in (select id from ev) and f.code <> 'record'
            and not d.cancelled and not dl.cancelled and d.in_person
            and (dl.end_date is null or dl.end_date >= $2::date - 2)
            and (dl.start_date is null or dl.start_date <= $3::date + 1)),
        p as (
          select distinct l.doc, l.ev, x.date
          from l
            join attendance a on a.document_line_id = l.id and a.present
              and a.date between $2::date - 2 and $3::date + 1
            cross join lateral (values (a.date), (case when l.acco then a.date + 1 end)) as x(date)
          where x.date is not null),
        w as (
          select ev, date,
            lag(date) over (partition by doc order by date) as prev,
            lead(date) over (partition by doc order by date) as nxt
          from p),
        c as (
          select w.date, w.ev,
            count(*) as present,
            count(*) filter (where w.prev is distinct from w.date - 1) as arriving,
            count(*) filter (where w.nxt is distinct from w.date + 1) as leaving
          from w
          where w.date between $2::date and $3::date
          group by w.date, w.ev)
        select c.date, c.ev, e.name, c.present, c.arriving, c.leaving
        from c join event e on e.id = c.ev
        order by c.date, c.ev
        """.formatted(EVENT_END_SLACK_DAYS);

    /**
     * The rows as {@code {days: [{date, eventId, eventName, present, arriving, leaving}]}}, one entry per day and
     * event with anyone present. The date is ISO text; the page sums the events for the chart.
     *
     * <p>The event's name is sent because events are public (the front office lists them, and an anonymous caller
     * may read {@code Event}); it saves the page a read of events it may not otherwise be granted.
     */
    static Object toReply(QueryResult result) {
        AstArray days = AST.createArray();
        if (result != null)
            for (int row = 0; row < result.getRowCount(); row++) {
                AstObject day = AST.createObject();
                Object date = result.getValue(row, 0);
                day.setScalar("date", date == null ? null : date.toString());
                day.setScalar("eventId", result.getValue(row, 1));
                day.setScalar("eventName", result.getValue(row, 2));
                day.setScalar("present", result.getValue(row, 3));
                day.setScalar("arriving", result.getValue(row, 4));
                day.setScalar("leaving", result.getValue(row, 5));
                days.push(day);
            }
        AstObject reply = AST.createObject();
        reply.setArray("days", days);
        return reply;
    }
}

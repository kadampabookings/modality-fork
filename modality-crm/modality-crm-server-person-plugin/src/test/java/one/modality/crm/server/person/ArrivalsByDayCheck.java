package one.modality.crm.server.person;

import java.time.LocalDate;

/**
 * Check for what {@link ArrivalsByDayEndpoint} accepts before it asks anything, and for the parts of the query that
 * carry a decision. The query's results were measured on staging (docs/design/registration-arrivals.md); the route
 * fence and the read need a server and a database.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, following the other checks here.
 */
public class ArrivalsByDayCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** A fixed today, so the bound around it holds whatever day the check runs. */
    static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    public static void main(String[] args) {
        System.out.println("a window the page sends:");
        ArrivalsByDay.Window week = ArrivalsByDay.windowOf(TODAY, new Object[]{151, "2026-07-25", "2026-07-31"});
        check("a week, as ISO text", week != null && week.organizationId() == 151
            && week.from().equals(LocalDate.of(2026, 7, 25)) && week.to().equals(LocalDate.of(2026, 7, 31)));
        check("as LocalDate, the way a Temporal date travels",
            ArrivalsByDay.windowOf(TODAY, new Object[]{151L, LocalDate.of(2026, 7, 25), LocalDate.of(2026, 8, 7)}) != null);
        check("one day", ArrivalsByDay.windowOf(TODAY, new Object[]{2, "2026-11-27", "2026-11-27"}) != null);
        check("the longest window",
            ArrivalsByDay.windowOf(TODAY, new Object[]{2, "2026-11-01", "2026-12-01"}) != null);

        System.out.println("what is refused before the database is asked:");
        check("one day too long", ArrivalsByDay.windowOf(TODAY, new Object[]{2, "2026-11-01", "2026-12-02"}) == null);
        check("a window that ends before it starts",
            ArrivalsByDay.windowOf(TODAY, new Object[]{2, "2026-11-27", "2026-11-26"}) == null);
        check("no organisation", ArrivalsByDay.windowOf(TODAY, new Object[]{null, "2026-11-27", "2026-11-28"}) == null);
        check("an organisation as text", ArrivalsByDay.windowOf(TODAY, new Object[]{"151", "2026-11-27", "2026-11-28"}) == null);
        check("an organisation as an object, as a careless client sends an entity",
            ArrivalsByDay.windowOf(TODAY, new Object[]{new Object(), "2026-11-27", "2026-11-28"}) == null);
        check("a zero or negative id", ArrivalsByDay.windowOf(TODAY, new Object[]{0, "2026-11-27", "2026-11-28"}) == null
            && ArrivalsByDay.windowOf(TODAY, new Object[]{-1, "2026-11-27", "2026-11-28"}) == null);
        check("a fractional id", ArrivalsByDay.windowOf(TODAY, new Object[]{151.5, "2026-11-27", "2026-11-28"}) == null);
        check("an id too wide for the column",
            ArrivalsByDay.windowOf(TODAY, new Object[]{1L + Integer.MAX_VALUE, "2026-11-27", "2026-11-28"}) == null);
        check("a date that is not one", ArrivalsByDay.windowOf(TODAY, new Object[]{2, "tomorrow", "2026-11-28"}) == null);
        check("a date as a number", ArrivalsByDay.windowOf(TODAY, new Object[]{2, 20261127, "2026-11-28"}) == null);
        check("a year Postgres would answer with raw text",
            ArrivalsByDay.windowOf(TODAY, new Object[]{2, "+99999-01-01", "+99999-01-02"}) == null);
        check("two arguments", ArrivalsByDay.windowOf(TODAY, new Object[]{2, "2026-11-27"}) == null);
        check("four arguments", ArrivalsByDay.windowOf(TODAY, new Object[]{2, "2026-11-27", "2026-11-28", 1}) == null);
        check("a window more than 400 days back (the read would take every event since)",
            ArrivalsByDay.windowOf(TODAY, new Object[]{2, "1900-01-01", "1900-01-31"}) == null
                && ArrivalsByDay.windowOf(TODAY, new Object[]{2, "2025-10-22", "2025-10-28"}) == null);
        check("last year's same weeks are allowed",
            ArrivalsByDay.windowOf(TODAY, new Object[]{2, "2025-11-23", "2025-11-29"}) != null);
        check("a window ending more than two years ahead",
            ArrivalsByDay.windowOf(TODAY, new Object[]{2, "2028-11-25", "2028-11-28"}) == null);
        check("not an array", ArrivalsByDay.windowOf(TODAY, 151) == null && ArrivalsByDay.windowOf(TODAY, null) == null);

        System.out.println("the query's decisions, as written:");
        String sql = ArrivalsByDay.COUNTS_SQL;
        check("cancelled bookings and lines are left out",
            sql.contains("not d.cancelled") && sql.contains("not dl.cancelled"));
        check("online bookings are left out", sql.contains("d.in_person"));
        check("recordings do not make anyone present", sql.contains("f.code <> 'record'"));
        check("a night makes the next morning present", sql.contains("case when l.acco then a.date + 1 end"));
        check("hosted events count, by the venue or the repeated event's venue",
            sql.contains("coalesce(s.organization_id, e.organization_id) = $1::int")
                && sql.contains("coalesce(e.venue_id, r.venue_id)"));
        check("events held at a branch venue are left out, an unset flag counting as the centre",
            sql.contains("and s.branch is not true"));
        check("events are bounded by their end date and the measured slack",
            sql.contains("e.end_date >= $2::date - " + ArrivalsByDay.EVENT_END_SLACK_DAYS));
        check("attendance is read from two days before to one day after the window",
            sql.contains("a.date between $2::date - 2 and $3::date + 1"));
        // The reply's one promise: counts per event, nothing that identifies a booking. A select list that grew a
        // booking id, a ref or a person would hand the host's staff what the read scope refuses them.
        String selected = sql.substring(sql.lastIndexOf("select c.date"), sql.lastIndexOf("from c join"));
        check("the reply selects counts and the event, and nothing that identifies a booking",
            !selected.contains("doc") && !selected.contains("ref") && !selected.contains("person"));

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}

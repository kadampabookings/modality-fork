package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Data Protection Officer has done lately, from the two run ledgers: event removals
 * ({@code event_erasure_run}) and customer / booker anonymisations ({@code person_erasure_run}), the last 365 days,
 * newest first, at most 200 rows.
 *
 * <p><b>Nothing about the people concerned.</b> A row says what kind of run it was, its outcome, when, who ran it (a
 * staff person id) and, for an event removal, the event and its organisation. An anonymisation row names neither the
 * person nor the booking acted on — only, for a booker run, the organisation of that booking's event, so the overview
 * can file it. Previews of anonymisations are left out: they are looks, not acts, and a preview names its subject by
 * being run on them.
 *
 * @author Claude Code
 */
final class DataProtectionActivity {

    private DataProtectionActivity() {
    }

    private static final String LOG_PREFIX = "[data-protection] ";

    static final String ACTIVITY_SQL =
        "select * from ("
        + " select 'event' as kind, r.stage, r.kind as scope, r.mode, r.status, r.event_id, e.organization_id,"
        + "  r.requested_by_person_id, " + instant("r.started_at") + " as started_at, " + instant("r.finished_at") + ", r.started_at as sort_at"
        + "  from event_erasure_run r left join event e on e.id = r.event_id"
        + "  where r.started_at > now() - interval '365 days'"
        + " union all"
        + " select case when p.document_id is null then 'customer' else 'booker' end, null, null, p.mode, p.status, null, e.organization_id,"
        + "  p.requested_by_person_id, " + instant("p.started_at") + ", " + instant("p.finished_at") + ", p.started_at"
        + "  from person_erasure_run p left join document d on d.id = p.document_id left join event e on e.id = d.event_id"
        + "  where p.mode = 'commit' and p.started_at > now() - interval '365 days'"
        + ") a order by sort_at desc limit 200";

    private static String instant(String column) {
        return "to_char(" + column + " at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')";
    }

    static Future<String> json() {
        return EventErasureRunner.query(ACTIVITY_SQL)
            .compose(r -> StaffNames.lookup(staffIds(r)).map(staff -> json(r, staff)))
            .recover(cause -> {
                Console.log(LOG_PREFIX + "Reading the activity failed: " + (cause == null ? null : cause.getMessage()));
                return Future.failedFuture("[%s] The activity could not be read".formatted(EventErasureRunner.SQL_KEY));
            });
    }

    /** The staff ids the rows name (who ran each run), for {@link StaffNames}. */
    static List<Number> staffIds(QueryResult r) {
        List<Number> ids = new ArrayList<>();
        for (int row = 0; r != null && row < r.getRowCount(); row++)
            ids.add(number(r.getValue(row, 7)));
        return ids;
    }

    /** {@link #json(QueryResult)} with {@code "staff":{"<id>":"<name>"}} added, from {@link StaffNames}. */
    static String json(QueryResult r, String staffJson) {
        String activity = json(r);
        return activity.substring(0, activity.length() - 1) + ",\"staff\":" + (staffJson == null ? "{}" : staffJson) + "}";
    }

    /** {@code {"activity":[{kind, stage, scope, mode, status, eventId, organizationId, byPersonId, startedAt, finishedAt}]}}. */
    static String json(QueryResult r) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; r != null && row < r.getRowCount(); row++)
            rows.add(new EventErasureJson.ObjectWriter()
                .string("kind", text(r.getValue(row, 0)))
                .string("stage", text(r.getValue(row, 1)))
                .string("scope", text(r.getValue(row, 2)))
                .string("mode", text(r.getValue(row, 3)))
                .string("status", text(r.getValue(row, 4)))
                .number("eventId", number(r.getValue(row, 5)))
                .number("organizationId", number(r.getValue(row, 6)))
                .number("byPersonId", number(r.getValue(row, 7)))
                .string("startedAt", text(r.getValue(row, 8)))
                .string("finishedAt", text(r.getValue(row, 9)))
                .toJson());
        return new EventErasureJson.ObjectWriter().raw("activity", EventErasureJson.array(rows)).toJson();
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}

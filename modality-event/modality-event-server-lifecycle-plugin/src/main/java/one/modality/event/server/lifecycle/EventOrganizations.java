package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Which organization an event — or a removal run, through its event — belongs to, for the Data Protection Officer's
 * event powers, scoped to the event's organization (admin-tiers plan, 2026-11-18). Read as the server
 * ({@link EventErasureRunner#query}). A target that does not exist, or whose read fails, has no organization, and
 * {@link EventErasureAccess#whenHoldsInOrganization} refuses it.
 *
 * @author Claude Code
 */
final class EventOrganizations {

    private EventOrganizations() {
    }

    /** Event ids per read: a longer list is read in batches of this many, never cut short. */
    static final int MAX_IDS = 5000;

    static final String EVENT_SQL = "select organization_id from event where id = $1";
    static final String RUN_SQL = "select e.organization_id from event_erasure_run r join event e on e.id = r.event_id where r.id = $1";

    /** The organization of this event, or null. */
    static Future<Object> ofEvent(Object eventId) {
        // Parsed exactly as EventErasureRunner.start parses it, so the gate and the run name the same event by construction
        Integer id = EventErasureArguments.positiveId(eventId);
        return id == null ? Future.succeededFuture(null) : single(EVENT_SQL, id);
    }

    /** The organization of this removal run's event, or null. */
    static Future<Object> ofRun(Object runId) {
        Integer id = EventErasureArguments.positiveId(runId);
        return id == null ? Future.succeededFuture(null) : single(RUN_SQL, id);
    }

    private static Future<Object> single(String sql, Integer id) {
        return EventErasureRunner.query(sql, id)
            .map(r -> r == null || r.getRowCount() == 0 ? null : Numbers.toInteger(r.getValue(0, 0)))
            .<Object>map(organization -> organization)
            .otherwise((Object) null);
    }

    /**
     * Event id → organization id, for these events. The ids come from the database's own rows (a list being filtered),
     * never from the caller, and are written as numbers, as {@link StaffNames} does.
     */
    static Future<Map<Integer, Integer>> ofEvents(Collection<Integer> eventIds) {
        List<String> reads = eventsSqls(eventIds);
        if (reads.isEmpty())
            return Future.succeededFuture(Map.of());
        List<Future<QueryResult>> answers = new ArrayList<>();
        for (String sql : reads)
            answers.add(EventErasureRunner.query(sql));
        return Future.all(new ArrayList<>(answers)).map(all -> {
            Map<Integer, Integer> organizationByEvent = new HashMap<>();
            for (int i = 0; i < answers.size(); i++)
                organizationByEvent.putAll(map(all.resultAt(i)));
            return organizationByEvent;
        });
    }

    /**
     * The reads for {@link #ofEvents}: the distinct ids in batches of {@link #MAX_IDS} — every id read, none dropped
     * (a dropped id would hide its row from the DPO's list without a word). Pure, for the check.
     */
    static List<String> eventsSqls(Collection<Integer> eventIds) {
        List<Integer> distinct = new ArrayList<>(new LinkedHashSet<>(eventIds));
        distinct.remove(null);
        List<String> reads = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += MAX_IDS)
            reads.add(eventsSql(distinct.subList(from, Math.min(from + MAX_IDS, distinct.size()))));
        return reads;
    }

    /** One read for {@link #ofEvents}, or null when there is nothing to read. Pure, for the check. */
    static String eventsSql(Collection<Integer> eventIds) {
        Set<Integer> distinct = new LinkedHashSet<>();
        for (Integer id : eventIds)
            if (id != null)
                distinct.add(id);
        if (distinct.isEmpty())
            return null;
        return "select id, organization_id from event where id in ("
               + distinct.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";
    }

    static Map<Integer, Integer> map(QueryResult r) {
        Map<Integer, Integer> organizationByEvent = new HashMap<>();
        for (int row = 0; r != null && row < r.getRowCount(); row++) {
            Integer event = Numbers.toInteger(r.getValue(row, 0));
            Integer organization = Numbers.toInteger(r.getValue(row, 1));
            if (event != null && organization != null)
                organizationByEvent.put(event, organization);
        }
        return organizationByEvent;
    }

    /** The events whose organization is among {@code held}: what a filtered list keeps. An unknown event is left out. */
    static Predicate<Number> eventsIn(Map<Integer, Integer> organizationByEvent, Set<Integer> held) {
        return eventId -> {
            Integer organization = eventId == null ? null : organizationByEvent.get(Numbers.toInteger(eventId));
            return organization != null && held.contains(organization);
        };
    }
}

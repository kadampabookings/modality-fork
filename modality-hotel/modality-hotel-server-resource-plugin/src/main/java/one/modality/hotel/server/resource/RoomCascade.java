package one.modality.hotel.server.resource;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What has to go, and in what order, for a room to be deletable. Three things reference one with no
 * cascade: allocation rules (three different ways), its resource configurations, and the KBS2→KBS3
 * migration self-reference on ANOTHER resource — which is nulled rather than followed, because that other
 * room is not the one being deleted.
 *
 * <p>Nothing here reads before it writes: every statement is set-based, so no configuration or rule created
 * between a read and a write survives a cascade that reported success. One transaction for all of it.
 *
 * <p>Raw SQL rather than DQL, because these are set-based deletes with sub-selects. Allowed here and refused
 * from a client — {@code ClientSubmitGuard} refuses raw statements of client origin, and these run as the
 * server (see {@link #asServer}). Every statement is a constant; a caller supplies one id, bound as a
 * parameter. The same shape, and the same reasoning, as the teaching-session cascade in
 * {@code modality-event-server-media-plugin}; they are apart because their families are.
 *
 * @author Claude Code
 */
final class RoomCascade {

    private RoomCascade() {
    }

    /**
     * "No booking names this room, or any of its configurations" — repeated inside every destructive
     * statement, because the foreign keys do NOT refuse this one.
     *
     * <p>{@code document_line_resource_id_fkey} and {@code document_line_resource_configuration_id_fkey}
     * are both ON DELETE SET NULL: deleting a room or a configuration does not fail on a booking, it
     * quietly erases which room that booking had. Residents and volunteer applications DO have the usual
     * refusing keys, so for them the database is the backstop; for bookings there is none, and a check that
     * ran in an earlier transaction would let anything committed in between through. So the condition rides
     * with the writes, in their transaction, and the whole batch simply changes nothing when it is false.
     */
    private static final String NO_BOOKINGS =
        " and not exists (select 1 from document_line dl where dl.resource_id = $1" +
        "   or dl.resource_configuration_id in (select id from resource_configuration rc where rc.resource_id = $1))";

    private static final String[] STATEMENTS = {
        "delete from allocation_rule where (resource_id = $1" +
        " or resource_configuration_id in (select id from resource_configuration where resource_id = $1)" +
        " or if_resource_configuration_id in (select id from resource_configuration where resource_id = $1))" + NO_BOOKINGS,
        "delete from resource_configuration where resource_id = $1" + NO_BOOKINGS,
        "update resource set kbs2_to_kbs3_global_resource_id = null where kbs2_to_kbs3_global_resource_id = $1" + NO_BOOKINGS,
        "delete from resource where id = $1" + NO_BOOKINGS,
    };

    /** Whether the room is still there — asked after the batch, which is how "it changed nothing" is read. */
    private static final String STILL_THERE_SQL = "select count(*) from resource where id = $1";

    /** The organization that owns a room, for the grant check — and proof that the room exists at all. */
    private static final String ROOM_SCOPE_SQL =
        "select s.organization_id from resource r join site s on s.id = r.site_id where r.id = $1";

    /**
     * What makes a room undeletable, asked as one question before anything is removed.
     *
     * <p>A room with BOOKINGS cannot go, and that holds for a booking on any of its configurations — the
     * global one the organization set up, or one a registration manager added to override it for the length
     * of an event. Residents and volunteer applications name a room directly and stop it too.
     *
     * <p>Asked so that the refusal can say WHICH of them it is. It is not what enforces the rule: for
     * residents and volunteers the foreign keys refuse anyway, and for bookings they do not refuse at all
     * (see {@link #NO_BOOKINGS}), so that condition travels with the writes instead. This question runs in
     * its own transaction and can be out of date by the time the batch runs; the batch is what holds.
     */
    private static final String IN_USE_SQL =
        "select" +
        "  (select count(*) from document_line dl where dl.resource_id = $1" +
        "     or dl.resource_configuration_id in (select id from resource_configuration where resource_id = $1))," +
        "  (select count(*) from person where resident_room_id = $1)," +
        "  (select count(*) from volunteering_application where resource_id = $1 or room_id = $1)";

    /** The reasons, in the order the refusal reports them. */
    private static final String BOOKINGS_KEY = "RoomHasBookingsError";
    private static final String RESIDENTS_KEY = "RoomHasResidentsError";
    private static final String VOLUNTEERS_KEY = "RoomHasVolunteersError";

    /**
     * Deletes a room with its configurations and the rules that name either, unless it is still in use.
     *
     * <p>Three steps, in this order: find what the room belongs to (which is what the caller's grant is
     * checked against, and proof the room exists), ask why it might be undeletable so the refusal can say
     * which, then run the batch — which carries the booking condition itself and changes nothing if that
     * became false in the meantime.
     */
    static Future<Boolean> deleteRoom(Object resourceId, RoomScopeAuthorizer authorizer) {
        Object id = Numbers.toLong(resourceId);
        if (id == null)
            return RouteAccessGuard.refused();
        return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(ROOM_SCOPE_SQL)
                .setParameters(id)
                .build()))
            .compose(scope -> {
                if (scope == null || scope.getRowCount() == 0) // no such room, or none with a site
                    return RouteAccessGuard.refused();
                Object organizationId = scope.getValue(0, 0);
                // A room belongs to an organization, never to one event — an event-scoped grant does not
                // reach the global room list, which is what /registration-rooming is for
                return authorizer.authorize(organizationId, null, () -> deleteUnusedRoom(id));
            });
    }

    /** How the endpoint hands its authorization back in, once the room's organization is known. */
    @FunctionalInterface
    interface RoomScopeAuthorizer {
        Future<Boolean> authorize(Object organizationId, Object eventId, Supplier<Future<Boolean>> work);
    }

    /** Which of the three is true, or null when the room is free to go. */
    private static String firstReasonInUse(QueryResult result) {
        if (result == null || result.getRowCount() == 0)
            return BOOKINGS_KEY; // no answer is not permission: fail closed
        if (countAt(result, 0) > 0)
            return BOOKINGS_KEY;
        if (countAt(result, 1) > 0)
            return RESIDENTS_KEY;
        if (countAt(result, 2) > 0)
            return VOLUNTEERS_KEY;
        return null;
    }

    private static long countAt(QueryResult result, int columnIndex) {
        Object value = result.getValue(0, columnIndex);
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static Future<Boolean> deleteUnusedRoom(Object id) {
        return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(IN_USE_SQL)
                .setParameters(id)
                .build()))
            .compose(result -> {
                String reason = firstReasonInUse(result);
                if (reason != null)
                    // Named, so the screen can say it in the reader's language rather than showing a
                    // constraint name. Nothing has been deleted at this point.
                    return Future.failedFuture("[%s] This room is still in use".formatted(reason));
                return runCascade(id);
            });
    }

    private static Future<Boolean> runCascade(Object id) {
        List<SubmitArgument> arguments = new ArrayList<>(STATEMENTS.length);
        for (String statement : STATEMENTS)
            arguments.add(new SubmitArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(statement) // a constant; no caller input is ever concatenated in
                .setParameters(id)
                .build());
        // Residents and volunteer applications have refusing foreign keys, so one of those appearing in the
        // meantime rolls the batch back and the caller is told. A booking does not refuse (see NO_BOOKINGS),
        // so the statements carry that condition themselves and land nothing when it is false — which is
        // why the room is read back afterwards rather than assumed gone.
        return asServer(() -> SubmitService.executeSubmitBatch(new Batch<>(arguments.toArray(new SubmitArgument[0]))))
            .compose(ignored -> asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(STILL_THERE_SQL)
                .setParameters(id)
                .build())))
            .compose(after -> {
                if (after == null || countAt(after, 0) > 0) {
                    // A booking arrived between the question and the batch: nothing was changed, and this
                    // is the one outcome that must not be reported as a delete
                    Console.log("🛡 Room (resource " + id + ") was NOT deleted: a booking arrived while it was being removed");
                    return Future.failedFuture("[%s] This room is still in use".formatted(BOOKINGS_KEY));
                }
                Console.log("🗑 Room (resource " + id + ") deleted with its configurations and rules");
                return Future.succeededFuture(true);
            });
    }

    /**
     * Runs the batch with the caller's state removed, so it is the server writing and not them — the rule
     * {@code ClientSubmitGuard} enforces, and the reason this endpoint exists.
     *
     * <p>Read on THIS thread, before the call is handed to the async queue, like every other reader of
     * {@link ThreadLocalStateHolder}.
     */
    private static <T> T asServer(Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
    }
}

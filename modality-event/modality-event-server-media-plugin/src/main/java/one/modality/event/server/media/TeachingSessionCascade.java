package one.modality.event.server.media;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.knownitems.KnownItemFamily;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What has to go, and in what order, for a teaching session to be deletable. No foreign key to
 * {@code scheduled_item} cascades, so everything pointing at the session or at its audio/video children has
 * to be removed first.
 *
 * <p>Nothing here reads before it writes. Every statement is set-based — "everything referencing this
 * session", not "these fourteen ids I looked up a moment ago" — which is the difference between this and the
 * client code it replaces: there is no window in which a row appears between the read and the write and
 * survives a cascade that reported success. It is also fewer round trips, in one transaction.
 *
 * <p>Raw SQL rather than DQL, because these are set-based deletes with sub-selects, which the DQL layer does
 * not express. Allowed HERE and refused from a client: {@code ClientSubmitGuard} refuses raw statements of
 * client origin, and these run as the server (see {@link #asServer}) — which is also why this class must
 * never be handed a statement built from caller input. Every statement below is a constant, and the only
 * thing a caller supplies is one id, bound as a parameter.
 *
 * @author Claude Code
 */
final class TeachingSessionCascade {

    private TeachingSessionCascade() {
    }

    // Attendance and Mail are DETACHED rather than deleted: they record what happened, and a session being
    // removed from the programme does not unmake somebody's attendance.
    private static final String[] STATEMENTS = {
        // 1. The history that is kept, pointed away from the session
        "update attendance set scheduled_item_id = null where scheduled_item_id = $1",
        "update mail set scheduled_item_id = null where scheduled_item_id = $1",
        // 2. Consumption of the children's media, then of the children, then of the session itself
        "delete from media_consumption where media_id in (select id from media where scheduled_item_id in" +
        " (select id from scheduled_item where program_scheduled_item_id = $1))",
        "delete from media_consumption where scheduled_item_id in" +
        " (select id from scheduled_item where program_scheduled_item_id = $1)",
        "delete from media_consumption where scheduled_item_id = $1",
        // 3. The media of the children AND of the session itself, then the children, then the session
        "delete from media where scheduled_item_id in (select id from scheduled_item where program_scheduled_item_id = $1)",
        "delete from media where scheduled_item_id = $1",
        "delete from scheduled_item where program_scheduled_item_id = $1",
        "delete from scheduled_item where id = $1",
    };

    /**
     * What the id is, before anything is done with it: a PROGRAMME SESSION, and the event and organization
     * it belongs to.
     *
     * <p>Both halves matter. The scope is what the caller's grant is checked against — a grant is held per
     * organization, sometimes per event — and the kind is what stops this endpoint being a way to delete
     * any scheduled item at all. Its first statement detaches {@code attendance} rows, whose column is
     * nullable, so a meal or accommodation day passed here would have every booking's attendance of that
     * day quietly pointed at nothing before the row was removed. A session is a teaching item that is
     * nobody's child; nothing else may be named here.
     */
    private static final String SESSION_SCOPE_SQL =
        "select si.event_id, e.organization_id from scheduled_item si" +
        " join event e on e.id = si.event_id" +
        " join item i on i.id = si.item_id" +
        " join item_family f on f.id = i.family_id" +
        " where si.id = $1 and f.code = $2 and si.program_scheduled_item_id is null";

    /** Deletes a teaching session with its children, their media, and every consumption of either. */
    static Future<Boolean> deleteTeachingSession(Object scheduledItemId, SessionScopeAuthorizer authorizer) {
        Object id = Numbers.toLong(scheduledItemId);
        if (id == null)
            return RouteAccessGuard.refused();
        return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(SESSION_SCOPE_SQL)
                .setParameters(id, KnownItemFamily.TEACHING.getCode())
                .build()))
            .compose(result -> {
                if (result == null || result.getRowCount() == 0) // not a programme session: refused, not attempted
                    return RouteAccessGuard.refused();
                Object eventId = result.getValue(0, 0);
                Object organizationId = result.getValue(0, 1);
                return authorizer.authorize(organizationId, eventId, () -> deleteSessionRows(id));
            });
    }

    /** How the endpoint hands its authorization back in, once the target's scope is known. */
    @FunctionalInterface
    interface SessionScopeAuthorizer {
        Future<Boolean> authorize(Object organizationId, Object eventId, java.util.function.Supplier<Future<Boolean>> work);
    }

    private static Future<Boolean> deleteSessionRows(Object id) {
        List<SubmitArgument> arguments = new ArrayList<>(STATEMENTS.length);
        for (String statement : STATEMENTS)
            arguments.add(new SubmitArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(statement) // a constant; no caller input is ever concatenated in
                .setParameters(id)
                .build());
        // One transaction for the whole cascade: it lands, or it leaves nothing behind. A row that cannot go
        // — something still naming the session that this does not know about — makes the database refuse,
        // the batch rolls back, and the caller is told.
        return asServer(() -> SubmitService.executeSubmitBatch(new Batch<>(arguments.toArray(new SubmitArgument[0]))))
            .map(ignored -> true)
            .onSuccess(ignored -> Console.log("🗑 Teaching session " + id + " deleted with its media and consumption"));
    }

    /**
     * Runs the batch with the caller's state removed, so it is the server writing and not them.
     *
     * <p>Necessary, not cosmetic: {@code ClientSubmitGuard} refuses raw statements that arrive with a client
     * origin on them, which is the rule this endpoint exists to satisfy. The endpoint has already decided
     * that this caller may do this; what runs afterwards is the server's own statement.
     *
     * <p>Read on THIS thread, before the call is handed to the async queue — the submit provider reads the
     * caller's state there, and {@link ThreadLocalStateHolder} is restored once the synchronous part returns.
     */
    private static <T> T asServer(Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
    }
}

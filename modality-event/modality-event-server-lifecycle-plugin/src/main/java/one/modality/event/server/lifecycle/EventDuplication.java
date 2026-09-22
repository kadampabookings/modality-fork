package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Booleans;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.Strings;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitResult;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

/**
 * What duplicating an event means, and what it refuses.
 *
 * <p>Three steps, in this order: find what the source event belongs to (which is what the caller's grant is
 * checked against, and proof the source exists and is a KBS3 event at all), then — once allowed — run
 * {@code duplicate_event()}, which copies the row and its programme in one transaction and answers the new
 * event's id.
 *
 * <p>Raw SQL rather than DQL, because the work is a PL/pgSQL function and the shape of the copy lives in
 * the database beside the other {@code copy_*} functions. Allowed here and refused from a client —
 * {@code ClientSubmitGuard} refuses raw statements of client origin, and this runs as the server (see
 * {@link #asServer}). Every statement is a constant; the caller's three values are bound as parameters.
 *
 * <p>The failures a back office can do something about are named, so the screen can say which in the
 * reader's language:
 *
 * <ul>
 *   <li>{@code EventProgramCopyError} — the row was fine, the programme was not. In practice this is the
 *       venue's own scheduled items (meals, and anything else not bound to an event) not existing on the
 *       new dates yet, which the copy refuses to guess at. Nothing was created.</li>
 *   <li>{@code InvalidEventNameError} / {@code InvalidEventDateError} — the name is empty or longer than
 *       the column, or the date is not a date. Checked here as well as in the screen, because the screen
 *       is not what enforces it, and only AFTER the caller has been allowed -- a refusal must not become
 *       a way to learn what this server would have said about the arguments.</li>
 * </ul>
 *
 * <p>Anything else comes back as one generic failure with the server's own wording: the database's message
 * is logged here and not sent on, so a refusal never becomes a description of the schema.
 *
 * @author Claude Code
 */
final class EventDuplication {

    private EventDuplication() {
    }

    /** The organization owning the source event, for the grant check — and proof the source is duplicable. */
    private static final String SOURCE_SQL = "select organization_id, kbs3 from event where id = $1";

    /**
     * The whole duplication: the new event row with the source's shape, then its programme, in one
     * transaction. Answers the new event's id — read as the "generated key", which for a statement
     * returning one column of one row is exactly that value (VertxSqlUtil.toWebFxSubmitResult).
     */
    private static final String DUPLICATE_SQL = "select duplicate_event($1, $2, $3)";

    /** The marker {@code duplicate_event()} re-raises the programme copy's own failure with. */
    private static final String PROGRAM_COPY_MARKER = "PROGRAM_COPY_FAILED";

    private static final String FAILED_KEY = "DuplicateEventError";
    private static final String PROGRAM_COPY_KEY = "EventProgramCopyError";
    private static final String INVALID_NAME_KEY = "InvalidEventNameError";
    private static final String INVALID_DATE_KEY = "InvalidEventDateError";

    /** The `event.name` column's width — a name that would not fit is refused rather than truncated. */
    private static final int MAX_NAME_LENGTH = 64;

    /** How the endpoint hands its authorization back in, once the source event's organization is known. */
    @FunctionalInterface
    interface EventScopeAuthorizer {
        Future<Integer> authorize(Object organizationId, Supplier<Future<Integer>> work);
    }

    /**
     * Duplicates {@code sourceEventId} into a new event called {@code name}, starting on
     * {@code startDate} ({@code yyyy-MM-dd}) and running for the source event's own length.
     *
     * @return the new event's id
     */
    static Future<Integer> duplicateEvent(Object sourceEventId, String name, String startDate,
                                          EventScopeAuthorizer authorizer) {
        Object id = Numbers.toInteger(sourceEventId);
        if (id == null)
            return refused();

        // Who asked, read while their state is still on this thread — the event table carries no history
        // trigger, so this log line is the only record of who created the event.
        Object personId = callerPersonId();

        return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(SOURCE_SQL)
                .setParameters(id)
                .build()))
            .compose(source -> {
                if (source == null || source.getRowCount() == 0) // no such event
                    return refused();
                if (!Booleans.isTrue(source.getValue(0, 1))) // a KBS2 event: its programme is not this shape
                    return refused();
                Object organizationId = source.getValue(0, 0);
                // Asked organization-WIDE (no event scope): the new event belongs to the organization, not
                // to the one it was copied from. The name and the date are judged INSIDE the guard: a
                // caller who may not do this at all learns only that, and not which of their arguments
                // this server would otherwise have objected to.
                return authorizer.authorize(organizationId,
                    () -> validateAndDuplicate(id, name, startDate, personId));
            });
    }

    /** The caller's values, judged once they are allowed to have them judged at all. */
    private static Future<Integer> validateAndDuplicate(Object sourceEventId, String name, String startDate,
                                                        Object personId) {
        String trimmedName = Strings.trim(name);
        if (Strings.isEmpty(trimmedName))
            return Future.failedFuture("[%s] The new event needs a name".formatted(INVALID_NAME_KEY));
        if (trimmedName.length() > MAX_NAME_LENGTH)
            return Future.failedFuture("[%s] The new event's name is too long".formatted(INVALID_NAME_KEY));

        LocalDate newStartDate;
        try {
            newStartDate = LocalDate.parse(startDate);
        } catch (NullPointerException | DateTimeParseException e) {
            return Future.failedFuture("[%s] The new event needs a start date".formatted(INVALID_DATE_KEY));
        }

        return duplicate(sourceEventId, trimmedName, newStartDate, personId);
    }

    private static Future<Integer> duplicate(Object sourceEventId, String name, LocalDate startDate, Object personId) {
        return asServer(() -> SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(DUPLICATE_SQL) // a constant; the caller's values are bound below
                .setParameters(sourceEventId, name, startDate)
                .setReturnGeneratedKeys(true)
                .build()))
            .compose(EventDuplication::newEventId)
            .onSuccess(newEventId -> Console.log("🆕 Event " + newEventId + " created by duplicating event "
                + sourceEventId + " (person " + personId + ")"))
            .recover(cause -> {
                String message = Strings.toString(cause == null ? null : cause.getMessage());
                // Logged whole, answered narrowly: the caller learns which half failed, not what the
                // database said about its own tables
                Console.log("🛡 Duplicating event " + sourceEventId + " failed: " + message);
                if (Strings.contains(message, PROGRAM_COPY_MARKER))
                    return Future.failedFuture("[%s] The event's programme could not be copied to those dates"
                        .formatted(PROGRAM_COPY_KEY));
                return failed();
            });
    }

    /** The new event's id, or a failure when the function answered nothing — which it cannot normally do. */
    private static Future<Integer> newEventId(SubmitResult result) {
        Object[] generatedKeys = result == null ? null : result.getGeneratedKeys();
        Integer newEventId = generatedKeys == null || generatedKeys.length == 0
            ? null : Numbers.toInteger(generatedKeys[0]);
        return newEventId == null ? failed() : Future.succeededFuture(newEventId);
    }

    /** A refusal, in RouteAccessGuard's words: the caller learns that it was refused, and no more. */
    private static Future<Integer> refused() {
        return RouteAccessGuard.refused();
    }

    /** Not a refusal but a failure — whatever the database said stays in the log above. */
    private static Future<Integer> failed() {
        return Future.failedFuture("[%s] The event could not be duplicated".formatted(FAILED_KEY));
    }

    /** The asking person, or null when the state no longer carries one. */
    private static Object callerPersonId() {
        Object userId = StateAccessor.getUserId(ThreadLocalStateHolder.getThreadLocalState());
        return userId == null ? null : userId.toString();
    }

    /**
     * Runs the statement with the caller's state removed, so it is the server writing and not them.
     *
     * <p>Not what stops a client sending this statement itself: {@code ClientSubmitGuard} refuses raw
     * statements, which is why this endpoint exists at all, but it is called from the two bus endpoints
     * through which a client's writes arrive and NOT from {@code SubmitService}, so server code never
     * passes through it whatever is on the thread. This is hygiene of the same kind — the write is the
     * server's, and nothing downstream that reads the caller's state (a restricted principal, an audit
     * actor, the back-office flag) reads one here and attributes the write to somebody who only asked
     * for it.
     *
     * <p>Read on THIS thread, before the call is handed to the async queue, like every other reader of
     * {@link ThreadLocalStateHolder}.
     */
    private static <T> T asServer(Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
    }
}

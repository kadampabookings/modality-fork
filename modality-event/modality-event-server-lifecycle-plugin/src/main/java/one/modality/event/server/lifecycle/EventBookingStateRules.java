package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.session.state.StateAccessor;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Putting an event's bookings on hold, closing them and reopening them — what the Registration › Event status page
 * asks of {@link SetEventBookingStateEndpoint}, and everything that decides the answer.
 *
 * <p><b>Why a server move.</b> {@code event.state} is otherwise written through the generic ChangeSet path, which
 * checks no operation for {@code Event} (finding R5 in the aggregate repo's docs/design/event-states-review.md): the
 * screen's route grant is the only fence, and the browser enforces it. These three moves decide whether guests can
 * book and pay — {@code OPEN} switches payments to live — so the Registration page makes them here, behind an
 * operation of their own, {@link #MANAGE_EVENT_BOOKINGS} (V0151), scoped to the event's own organisation and event.
 * The generic path stays open for the event form, Recurring Events, the lifecycle page and Reconcile events: R5 is
 * narrowed, not closed.
 *
 * <p><b>The steps, in order</b> ({@link #setBookingState}):
 * <ol>
 *   <li><b>Who</b>, before anything is read: {@link RouteAccessGuard#backOfficeCallerPersonId} — a registered person
 *       on a session this server established, opened for the back office, not a support view. Anybody else is
 *       refused with {@link RouteAccessGuard#refused()} and learns nothing about their arguments.</li>
 *   <li><b>What</b>: {@code [eventId, expected, target]}, the id a positive {@code int}, the two states exact names
 *       from {@link BookingState} ({@link #MALFORMED} otherwise), and the pair one of {@link #isAllowedMove}'s six
 *       ({@link #MOVE_NOT_ALLOWED} otherwise) — the same table as the React page's {@code bookingMoves}.</li>
 *   <li><b>The row</b>, read as the server ({@link #EVENT_SQL}). A missing event is refused exactly like a forbidden
 *       one, so the endpoint cannot be used to learn which ids exist.</li>
 *   <li><b>May</b>: {@link #MANAGE_EVENT_BOOKINGS} held in the organisation AND for the event read from that row —
 *       never from the request, which would let a caller name an organisation they hold the grant in while acting on
 *       another's event. Super administrators pass, as through every RouteAccessGuard check.</li>
 *   <li><b>Still as shown?</b> A removed event answers {@code deleted}, a state other than {@code expected} answers
 *       {@code changed}; nothing is written.</li>
 *   <li><b>The write</b>: one compare-and-set UPDATE ({@link #compareAndSetSql}), assembled from the {@link BookingState}
 *       literal only — the caller's strings never reach SQL; {@code expected} is bound as the enum's own name. It
 *       runs as the server acting for the caller (MoneyTransferVerification's pattern): the client origin dropped,
 *       the principal kept for the audit note.</li>
 *   <li><b>The outcome</b> is read back ({@link #outcomeAfterWrite}): a submit's row count counts row sets, not
 *       rows, so it cannot say whether the UPDATE matched.</li>
 *   <li><b>Refusals the database makes</b> — V0147's six markers, {@link #STATE_GUARD_MARKERS} — reach the page as
 *       {@code [EventStateGuardError] <MARKER>}. Any other database text stays in the log
 *       ({@link #NOT_SAVED} is what the caller reads).</li>
 *   <li><b>The log</b> names ids only: {@code [event-bookings] Event 123 OPEN → ON_HOLD by person 456}. The
 *       {@code event} table keeps no history, so this line is the only record of who moved an event's bookings.</li>
 * </ol>
 *
 * <p>The database is reached through {@link Database}, so a check can run every step without one.
 *
 * @author Claude Code
 */
final class EventBookingStateRules {

    private EventBookingStateRules() {
    }

    /** Seeded by V0151 with no grant route: it gates three buttons, not a page (V0106's precedent). */
    static final String MANAGE_EVENT_BOOKINGS = "ManageEventBookings";

    /**
     * The key of both refusals the arguments can earn — a request that is not {@code [eventId, from, to]}, and a move
     * outside the table. Both are judged before anything is read, and the page reads either as "not allowed".
     */
    static final String MOVE_ERROR_KEY = "EventBookingStateMoveError";

    static final String MALFORMED = "[" + MOVE_ERROR_KEY + "] Malformed request";

    static final String MOVE_NOT_ALLOWED = "[" + MOVE_ERROR_KEY + "] This move is not allowed";

    /** Any failure the page cannot do anything specific about: the database's own words stay in the log. */
    static final String NOT_SAVED = "[EventBookingStateError] The bookings state could not be saved";

    static final String STATE_GUARD_ERROR_KEY = "EventStateGuardError";

    /**
     * The refusals V0147's {@code trigger_event_state_guards()} raises, passed through by name. Only two can be met
     * by these moves today — {@code EVENT_DATA_REMOVED_STAYS_CLOSED} (reopening an event whose data a removal erased)
     * and {@code EVENT_REMOVED_STAYS_CLOSED} — but all six are known, so a rule that changes does not turn into a
     * generic failure.
     */
    static final List<String> STATE_GUARD_MARKERS = List.of(
        "EVENT_FINALISED_BY_REMOVAL_ONLY",
        "EVENT_ARCHIVED_FROM_FINALISED_ONLY",
        "EVENT_STATE_SEALED",
        "EVENT_RECONCILED_CLOSED_ONLY",
        "EVENT_REMOVED_STAYS_CLOSED",
        "EVENT_DATA_REMOVED_STAYS_CLOSED");

    /**
     * Each marker as a whole word: {@code EVENT_REMOVED_STAYS_CLOSED} must not be found inside some longer name, and
     * the six are told apart by their boundaries rather than by the order they are tried in.
     */
    private static final List<Pattern> MARKER_PATTERNS = STATE_GUARD_MARKERS.stream()
        .map(marker -> Pattern.compile("(?<![A-Za-z0-9_])" + marker + "(?![A-Za-z0-9_])"))
        .toList();

    private static final String LOG_PREFIX = "[event-bookings] ";

    /**
     * The states the bookings control knows. Their names ARE the {@code event_state} enum literals, which is what
     * lets {@link #compareAndSetSql} write one into SQL: nothing else can reach that statement.
     */
    enum BookingState {
        OPEN, ON_HOLD, CLOSED, RESTRICTED;

        /** The state whose name is exactly {@code value}, or null — for anything that is not one of the four. */
        static BookingState named(Object value) {
            if (!(value instanceof String name))
                return null;
            for (BookingState state : values())
                if (state.name().equals(name))
                    return state;
            return null;
        }
    }

    /** What the page is told happened. */
    enum Outcome {
        /** The bookings are now in the target state. */
        MOVED("moved"),
        /** The event was not in the state the page showed (before or after the write); nothing of ours applied. */
        CHANGED("changed"),
        /** The event has been deleted (soft or hard) since the page read it. */
        DELETED("deleted");

        final String json;

        Outcome(String json) {
            this.json = json;
        }
    }

    /** A request that has passed the shape check: the caller's strings are gone, only enum constants remain. */
    record Request(int eventId, BookingState expected, BookingState target) {
    }

    /** What the server read about the event: its owner, its state as text (null for a legacy event), removed. */
    record EventRow(Object organizationId, String state, boolean removed) {
    }

    /** How the steps reach the database; the endpoint's implementation runs every statement as the server. */
    interface Database {

        /** {@link #EVENT_SQL} for this event, as {@link #eventRowOf} reads it: null when there is no such event. */
        Future<EventRow> readEvent(int eventId);

        /**
         * {@code RouteAccessGuard.whenCallerHoldsOperation(callerState, MANAGE_EVENT_BOOKINGS, organizationId,
         * eventId, work)}: {@code work} runs only when allowed, and a refusal is {@link RouteAccessGuard#refused()}.
         */
        <T> Future<T> whenCallerHoldsOperation(Object callerState, Object organizationId, int eventId,
                                               Supplier<Future<T>> work);

        /**
         * Runs the compare-and-set statement with {@code $1 = eventId} and {@code $2 = expectedState}, as the server
         * acting for {@code callerUserId}.
         */
        Future<Void> compareAndSet(Object callerUserId, String sql, int eventId, String expectedState);
    }

    // ── The statements ─────────────────────────────────────────────────────────────────────────────────────────

    /** The event's owner, its state and whether it is deleted. {@code $1} is the event id. */
    static final String EVENT_SQL = "select organization_id, state::text, removed from event where id = $1";

    /**
     * The compare-and-set: the event moves to {@code target} only if it is still in the expected state ({@code $2})
     * and not deleted. The literal is the enum constant's own name — the method takes no string — and the event id
     * and the expected state are bound. Raw SQL, run as the server: the client may not write raw statements, and
     * this one is the server's, made on the caller's behalf.
     */
    static String compareAndSetSql(BookingState target) {
        return "update event set state = '" + target.name() + "'"
               + " where id = $1 and state::text is not distinct from $2 and removed is not true";
    }

    // ── The decision ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The six moves of the Registration page's bookings control: on hold and back, close from any open state,
     * and reopen. No {@code CLOSED → ON_HOLD}: a closed event is reopened first. No move INTO {@code RESTRICTED}.
     */
    static boolean isAllowedMove(BookingState from, BookingState to) {
        if (from == null || to == null)
            return false;
        return switch (from) {
            case OPEN -> to == BookingState.ON_HOLD || to == BookingState.CLOSED;
            case ON_HOLD -> to == BookingState.OPEN || to == BookingState.CLOSED;
            case RESTRICTED -> to == BookingState.CLOSED;
            case CLOSED -> to == BookingState.OPEN;
        };
    }

    /**
     * {@code [eventId, expected, target]}, or null when it is not exactly that: three elements, a positive
     * {@code int} id (from any integral number the bus decoded, or a digits-only string —
     * {@link EventErasureArguments#positiveId}), and two exact state names. Whether the pair is a move the page may
     * make is {@link #isAllowedMove}'s question, asked separately so the two answers differ.
     */
    static Request parse(Object argument) {
        if (!(argument instanceof Object[] arguments) || arguments.length != 3)
            return null;
        Integer eventId = EventErasureArguments.positiveId(arguments[0]);
        BookingState expected = BookingState.named(arguments[1]);
        BookingState target = BookingState.named(arguments[2]);
        if (eventId == null || expected == null || target == null)
            return null;
        return new Request(eventId, expected, target);
    }

    /** The row {@link #EVENT_SQL} returned, or null when it returned none. */
    static EventRow eventRowOf(QueryResult result) {
        if (result == null || result.getRowCount() == 0)
            return null;
        // Through Object locals: getValue is generic, and an inferred type there is a cast that can fail
        Object organizationId = result.getValue(0, 0);
        Object state = result.getValue(0, 1);
        Object removed = result.getValue(0, 2);
        return new EventRow(organizationId, state == null ? null : state.toString(), Boolean.TRUE.equals(removed));
    }

    /** Step 5: why the move is not even tried, or null when the event is still as the page showed it. */
    static Outcome refusalBeforeWrite(EventRow row, BookingState expected) {
        if (row.removed())
            return Outcome.DELETED;
        if (!expected.name().equals(row.state()))
            return Outcome.CHANGED;
        return null;
    }

    /** Step 7: what the row read back after the write says happened. Null is an event deleted since. */
    static Outcome outcomeAfterWrite(EventRow row, BookingState target) {
        if (row == null || row.removed())
            return Outcome.DELETED;
        return target.name().equals(row.state()) ? Outcome.MOVED : Outcome.CHANGED;
    }

    /** {@code {"outcome":"moved"|"changed"|"deleted","state":string|null}} — the event's state as now read. */
    static String replyJson(Outcome outcome, String state) {
        return new EventErasureJson.ObjectWriter()
            .string("outcome", outcome.json)
            .string("state", state)
            .toJson();
    }

    /** What the caller reads about a failed write: a V0147 marker by name, or {@link #NOT_SAVED}. */
    static String failureMessage(Throwable failure) {
        String marker = stateGuardMarkerIn(failure == null ? null : failure.getMessage());
        return marker == null ? NOT_SAVED : "[" + STATE_GUARD_ERROR_KEY + "] " + marker;
    }

    /** The V0147 marker {@code message} carries as a whole word, or null. */
    static String stateGuardMarkerIn(String message) {
        if (message == null)
            return null;
        for (int i = 0; i < MARKER_PATTERNS.size(); i++)
            if (MARKER_PATTERNS.get(i).matcher(message).find())
                return STATE_GUARD_MARKERS.get(i);
        return null;
    }

    // ── The steps ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Moves an event's bookings as the page asked, and answers {@link #replyJson}.
     *
     * @param callerState the caller's state, read on the caller's thread before any async step — by the time the
     *                    event row has been read, the thread-local has been restored and there is nobody to read
     * @param argument    {@code [eventId, expected, target]} as the bus decoded it
     */
    static Future<String> setBookingState(Object callerState, Object argument, Database database) {
        // 1. Who — before anything is read, and before the arguments are judged.
        Object personId = RouteAccessGuard.backOfficeCallerPersonId(callerState);
        if (personId == null) {
            Console.log(LOG_PREFIX + "Refused: not a verified back-office caller");
            return RouteAccessGuard.refused();
        }
        // 2. What — refused, never defaulted.
        Request request = parse(argument);
        if (request == null)
            return Future.failedFuture(MALFORMED);
        String move = "Event " + request.eventId() + " " + request.expected() + " → " + request.target();
        if (!isAllowedMove(request.expected(), request.target())) {
            Console.log(LOG_PREFIX + move + " refused for person " + personId + ": not a bookings move");
            return Future.failedFuture(MOVE_NOT_ALLOWED);
        }
        // Read now, while the state is in hand: the write is made in this principal's name.
        Object callerUserId = StateAccessor.getUserId(callerState);
        // 3. The row, as the server.
        return attempt(() -> database.readEvent(request.eventId())).compose(row -> {
            if (row == null) {
                Console.log(LOG_PREFIX + move + " refused for person " + personId + ": no such event");
                return RouteAccessGuard.refused();
            }
            // 4. May — in the organisation and for the event the ROW names.
            AtomicBoolean allowed = new AtomicBoolean();
            return EventBookingStateRules.<String>attempt(() -> database.whenCallerHoldsOperation(callerState,
                row.organizationId(), request.eventId(), () -> {
                    allowed.set(true);
                    return moveIfStillAsShown(request, row, callerUserId, personId, database, move);
                })).recover(cause -> {
                    if (allowed.get()) // the move's own answer, already framed and logged
                        return Future.failedFuture(cause);
                    String message = cause == null ? null : cause.getMessage();
                    if (isRefusal(message)) {
                        Console.log(LOG_PREFIX + move + " refused for person " + personId + ": " + MANAGE_EVENT_BOOKINGS
                                    + " is not held for this event");
                        return Future.failedFuture(cause);
                    }
                    // The grant lookup itself failed: not a yes, and not the database's words either
                    Console.log(LOG_PREFIX + move + ": the " + MANAGE_EVENT_BOOKINGS + " check failed for person "
                                + personId + " (" + message + ")");
                    return Future.failedFuture(NOT_SAVED);
                });
        }, cause -> {
            Console.log(LOG_PREFIX + move + ": reading the event failed (" + (cause == null ? null : cause.getMessage()) + ")");
            return Future.failedFuture(NOT_SAVED);
        });
    }

    /** Steps 5 to 9, once the caller has been allowed. */
    private static Future<String> moveIfStillAsShown(Request request, EventRow row, Object callerUserId, Object personId,
                                                     Database database, String move) {
        // 5. Still as the page showed it?
        Outcome early = refusalBeforeWrite(row, request.expected());
        if (early != null) {
            Console.log(LOG_PREFIX + move + " not made for person " + personId + ": the event is "
                        + (early == Outcome.DELETED ? "deleted" : "now " + stateForLog(row.state())));
            return Future.succeededFuture(replyJson(early, row.state()));
        }
        // 6. The compare-and-set, in the caller's name. The expected state is bound as the enum's own name.
        return EventBookingStateRules.<Void>attempt(() -> database.compareAndSet(callerUserId,
                compareAndSetSql(request.target()), request.eventId(), request.expected().name()))
            .compose(written ->
                // 7. Read back: the outcome is the row's, not the submit's
                EventBookingStateRules.<EventRow>attempt(() -> database.readEvent(request.eventId())).compose(after -> {
                    Outcome outcome = outcomeAfterWrite(after, request.target());
                    String state = after == null ? null : after.state();
                    // 9. The audit line: ids and states only
                    Console.log(LOG_PREFIX + (outcome == Outcome.MOVED ? move + " by person " + personId
                        : move + " not made for person " + personId + ": the event is "
                          + (outcome == Outcome.DELETED ? "deleted" : "now " + stateForLog(state))));
                    return Future.succeededFuture(replyJson(outcome, state));
                }, cause -> {
                    Console.log(LOG_PREFIX + move + " written for person " + personId + ", but reading it back failed ("
                                + (cause == null ? null : cause.getMessage()) + ")");
                    return Future.failedFuture(NOT_SAVED);
                }),
                // 8. A refusal by name; anything else stays in the log
                cause -> {
                    String message = cause == null ? null : cause.getMessage();
                    String marker = stateGuardMarkerIn(message);
                    Console.log(LOG_PREFIX + move + (marker != null
                        ? " refused by the database for person " + personId + ": " + marker
                        : " failed for person " + personId + " (" + message + ")"));
                    return Future.failedFuture(failureMessage(cause));
                });
    }

    /** A state for the log: its enum name, or what a missing one means. */
    private static String stateForLog(String state) {
        return state == null ? "without a state (a legacy event)" : state;
    }

    /** Whether a failure is RouteAccessGuard's refusal, which passes through as it is. */
    private static boolean isRefusal(String message) {
        return RouteAccessGuard.refused().cause().getMessage().equals(message);
    }

    /** A call that throws instead of failing its future becomes a failed future, so every step can recover. */
    private static <T> Future<T> attempt(Supplier<Future<T>> call) {
        try {
            Future<T> future = call.get();
            return future == null ? Future.failedFuture("No answer") : future;
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }
}

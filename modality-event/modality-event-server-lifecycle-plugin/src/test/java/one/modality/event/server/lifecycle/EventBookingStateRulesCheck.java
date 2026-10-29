package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.session.state.StateAccessor;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.event.server.lifecycle.EventBookingStateRules.BookingState;
import one.modality.event.server.lifecycle.EventBookingStateRules.EventRow;
import one.modality.event.server.lifecycle.EventBookingStateRules.Outcome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check for {@link EventBookingStateRules} — the bookings moves of Registration › Event status.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero on failure,
 * following MoneyTransferVerificationCheck. The database is a fake that records every call, which is what lets the
 * check say "refused BEFORE anything was read" rather than only "refused". Run with the module directory as the
 * working directory: the marker list is checked against V0147's own source.
 *
 * <p>The SQL itself (the compare-and-set against the V0147 trigger, the read) is rehearsed on a disposable postgres:17
 * — see docs/design/event-booking-state-endpoint.md in the aggregate repo.
 */
public class EventBookingStateRulesCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    private static final String REFUSED = "This operation is not available to you";

    private static final ModalityUserPrincipal PRINCIPAL = new ModalityUserPrincipal(456, 7);

    /** A caller who gets all the way through the fence: back-office audience, established session, real principal. */
    private static Object allowedState() {
        Object state = StateAccessor.createEmptyState();
        state = StateAccessor.setUserId(state, PRINCIPAL);
        state = StateAccessor.setSessionFamilyId(state, "family-1");
        return StateAccessor.setVerifiedBackoffice(state, Boolean.TRUE);
    }

    /** A database that answers from fields and records every call. */
    static class FakeDatabase implements EventBookingStateRules.Database {
        final List<Future<EventRow>> reads = new ArrayList<>();
        int readCount, guardCount, writeCount;
        boolean grant = true;
        Future<Void> writeAnswer = Future.succeededFuture();
        Future<Object> guardFailure; // when set, the guard fails with it instead of deciding
        Object guardState, guardOrganization; int guardEvent;
        Object writeUserId; String writeSql; int writeEvent; String writeExpected;

        FakeDatabase reading(EventRow... rows) {
            for (EventRow row : rows)
                reads.add(Future.succeededFuture(row));
            return this;
        }

        FakeDatabase readingFailure(String message) {
            reads.add(Future.failedFuture(message));
            return this;
        }

        @Override
        public Future<EventRow> readEvent(int eventId) {
            return reads.get(readCount++);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Future<T> whenCallerHoldsOperation(Object callerState, Object organizationId, int eventId,
                                                      Supplier<Future<T>> work) {
            guardCount++;
            guardState = callerState;
            guardOrganization = organizationId;
            guardEvent = eventId;
            if (guardFailure != null)
                return (Future<T>) guardFailure;
            return grant ? work.get() : Future.failedFuture(REFUSED);
        }

        @Override
        public Future<Void> compareAndSet(Object callerUserId, String sql, int eventId, String expectedState) {
            writeCount++;
            writeUserId = callerUserId;
            writeSql = sql;
            writeEvent = eventId;
            writeExpected = expectedState;
            return writeAnswer;
        }
    }

    private static Future<String> run(Object state, Object argument, FakeDatabase database) {
        return EventBookingStateRules.setBookingState(state, argument, database);
    }

    private static Object[] move(Object eventId, Object expected, Object target) {
        return new Object[] { eventId, expected, target };
    }

    private static boolean failedWith(Future<String> future, String message) {
        return future.failed() && message.equals(future.cause().getMessage());
    }

    private static boolean answered(Future<String> future, String json) {
        return future.succeeded() && json.equals(future.result());
    }

    private static boolean untouched(FakeDatabase database) {
        return database.readCount == 0 && database.guardCount == 0 && database.writeCount == 0;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("EventBookingStateRulesCheck");

        // ── Refused before anything is read ──
        // Each refused state differs from the allowed one by one attribute, and each sends a VALID move, so the only
        // reason it can be refused is the fence. The fake database must not have been touched.
        Object[] valid = move(123, "OPEN", "ON_HOLD");
        Object anonymous = StateAccessor.createEmptyState();
        Object claimedOnly = StateAccessor.setBackoffice(StateAccessor.createEmptyState(), true);
        Object noFamily = StateAccessor.setVerifiedBackoffice(StateAccessor.setUserId(StateAccessor.createEmptyState(), PRINCIPAL), true);
        Object noAudience = StateAccessor.setSessionFamilyId(StateAccessor.setUserId(StateAccessor.createEmptyState(), PRINCIPAL), "family-1");
        Object frontOffice = StateAccessor.setVerifiedBackoffice(noAudience, Boolean.FALSE);
        Object supportView = StateAccessor.setVerifiedBackoffice(StateAccessor.setSessionFamilyId(StateAccessor.setUserId(
            StateAccessor.createEmptyState(), new ModalityUserPrincipal(99, 8, 456)), "family-1"), true);
        Object guestLike = StateAccessor.setVerifiedBackoffice(StateAccessor.setSessionFamilyId(StateAccessor.setUserId(
            StateAccessor.createEmptyState(), "guest:someone"), "family-1"), true);
        Object[][] refusedStates = {
            { "no state at all (read after an async hop)", null }, { "an anonymous state", anonymous },
            { "a back office the client only claimed", claimedOnly }, { "no established session", noFamily },
            { "a token that verified no audience", noAudience }, { "a front-office session", frontOffice },
            { "a support view", supportView }, { "a principal that is not a person's", guestLike } };
        for (Object[] refused : refusedStates) {
            FakeDatabase database = new FakeDatabase().reading(new EventRow(1, "OPEN", false));
            check(refused[0] + " is refused, and nothing is read",
                failedWith(run(refused[1], valid, database), REFUSED) && untouched(database));
        }
        FakeDatabase garbage = new FakeDatabase();
        check("a refused caller with a malformed request learns only that it was refused",
            failedWith(run(anonymous, "drop table event", garbage), REFUSED) && untouched(garbage));
        check("the positive control: the same move from an allowed caller gets through",
            answered(run(allowedState(), valid, new FakeDatabase().reading(new EventRow(1, "OPEN", false),
                new EventRow(1, "ON_HOLD", false))), "{\"outcome\":\"moved\",\"state\":\"ON_HOLD\"}"));

        // ── The shape: refused, never defaulted ──
        EventBookingStateRules.Request parsed = EventBookingStateRules.parse(move(123, "OPEN", "ON_HOLD"));
        check("a well-formed move parses", parsed != null && parsed.eventId() == 123
            && parsed.expected() == BookingState.OPEN && parsed.target() == BookingState.ON_HOLD);
        check("the id may arrive as a Long", EventBookingStateRules.parse(move(123L, "OPEN", "CLOSED")).eventId() == 123);
        check("the id may arrive as a whole Double", EventBookingStateRules.parse(move(123.0, "OPEN", "CLOSED")).eventId() == 123);
        check("the id may arrive as a Byte", EventBookingStateRules.parse(move((byte) 9, "OPEN", "CLOSED")).eventId() == 9);
        check("the id may arrive as a digit string", EventBookingStateRules.parse(move("123", "OPEN", "CLOSED")).eventId() == 123);
        Object[][] malformed = {
            { "no argument", null }, { "a bare id", 123 }, { "a string", "123,OPEN,ON_HOLD" },
            { "two elements", new Object[] { 123, "OPEN" } }, { "four elements", new Object[] { 123, "OPEN", "ON_HOLD", "x" } },
            { "an empty array", new Object[0] }, { "a zero id", move(0, "OPEN", "ON_HOLD") },
            { "a negative id", move(-5, "OPEN", "ON_HOLD") }, { "a fractional id", move(2.5, "OPEN", "ON_HOLD") },
            { "an id past int", move(3_000_000_000L, "OPEN", "ON_HOLD") }, { "a NaN id", move(Double.NaN, "OPEN", "ON_HOLD") },
            { "a word for an id", move("abc", "OPEN", "ON_HOLD") }, { "a boolean id", move(true, "OPEN", "ON_HOLD") },
            { "a signed digit string", move("-12", "OPEN", "ON_HOLD") }, { "a padded digit string", move(" 12", "OPEN", "ON_HOLD") },
            { "a zero digit string", move("0", "OPEN", "ON_HOLD") }, { "a digit string past int", move("99999999999", "OPEN", "ON_HOLD") },
            { "a null id", move(null, "OPEN", "ON_HOLD") }, { "a lower-case state", move(123, "open", "ON_HOLD") },
            { "a padded state", move(123, " OPEN", "ON_HOLD") }, { "a state that is not a bookings state", move(123, "DRAFT", "OPEN") },
            { "a sealed state", move(123, "FINALISED", "OPEN") }, { "a reconciled state", move(123, "OPEN", "RECONCILED") },
            { "a null state", move(123, null, "ON_HOLD") }, { "a number for a state", move(123, 5, "ON_HOLD") },
            { "SQL for a state", move(123, "OPEN", "OPEN'; drop table event; --") } };
        for (Object[] bad : malformed) {
            FakeDatabase database = new FakeDatabase();
            check(bad[0] + " is malformed, and nothing is read",
                EventBookingStateRules.parse(bad[1]) == null
                && failedWith(run(allowedState(), bad[1], database), EventBookingStateRules.MALFORMED) && untouched(database));
        }

        // ── The moves: exactly the six of the contract (and of the React page's bookingMoves) ──
        Set<String> allowed = new TreeSet<>();
        for (BookingState from : BookingState.values())
            for (BookingState to : BookingState.values())
                if (EventBookingStateRules.isAllowedMove(from, to))
                    allowed.add(from + "→" + to);
        check("exactly the six moves are allowed: " + allowed, allowed.equals(new TreeSet<>(List.of(
            "OPEN→ON_HOLD", "OPEN→CLOSED", "ON_HOLD→OPEN", "ON_HOLD→CLOSED", "RESTRICTED→CLOSED", "CLOSED→OPEN"))));
        check("a move from or to nothing is not allowed",
            !EventBookingStateRules.isAllowedMove(null, BookingState.OPEN) && !EventBookingStateRules.isAllowedMove(BookingState.OPEN, null));
        Object[][] notAllowed = { { "CLOSED", "ON_HOLD" }, { "OPEN", "OPEN" }, { "OPEN", "RESTRICTED" }, { "RESTRICTED", "OPEN" },
            { "RESTRICTED", "ON_HOLD" }, { "ON_HOLD", "ON_HOLD" }, { "CLOSED", "CLOSED" } };
        for (Object[] pair : notAllowed) {
            FakeDatabase database = new FakeDatabase();
            check(pair[0] + " → " + pair[1] + " is not allowed, and nothing is read",
                failedWith(run(allowedState(), move(123, pair[0], pair[1]), database), EventBookingStateRules.MOVE_NOT_ALLOWED)
                && untouched(database));
        }

        // ── The statements ──
        check("the read is a constant with the event id bound",
            "select organization_id, state::text, removed from event where id = $1".equals(EventBookingStateRules.EVENT_SQL));
        Pattern casShape = Pattern.compile("update event set state = '([A-Z_]+)' where id = \\$1 and state::text is not"
                                           + " distinct from \\$2 and removed is not true");
        for (BookingState target : BookingState.values()) {
            String sql = EventBookingStateRules.compareAndSetSql(target);
            Matcher m = casShape.matcher(sql);
            check("the compare-and-set to " + target + " is the fixed shape with that enum literal",
                m.matches() && m.group(1).equals(target.name()));
        }
        check("the statements read and write no personal data",
            !(EventBookingStateRules.EVENT_SQL + EventBookingStateRules.compareAndSetSql(BookingState.OPEN))
                .matches("(?s).*(person|name|email|document).*"));
        // The write binds the ENUM's name, not the caller's string object: identical text, but not the same object.
        FakeDatabase bound = new FakeDatabase().reading(new EventRow(77, "OPEN", false), new EventRow(77, "CLOSED", false));
        String callerText = new String("OPEN");
        Future<String> boundMove = run(allowedState(), move(123, callerText, "CLOSED"), bound);
        check("the write is the compare-and-set to the target",
            boundMove.succeeded() && EventBookingStateRules.compareAndSetSql(BookingState.CLOSED).equals(bound.writeSql));
        check("the expected state is bound as the server's enum literal, not the caller's string",
            bound.writeExpected == BookingState.OPEN.name() && bound.writeExpected != callerText);
        check("the event id is bound as the parsed int", bound.writeEvent == 123);

        // ── Who decides, and in whose name ──
        Object state = allowedState();
        FakeDatabase scoped = new FakeDatabase().reading(new EventRow(77, "OPEN", false), new EventRow(77, "ON_HOLD", false));
        run(state, move(123, "OPEN", "ON_HOLD"), scoped);
        check("the grant is asked of the caller's own state", scoped.guardState == state);
        check("the grant is asked in the organisation the ROW names", Integer.valueOf(77).equals(scoped.guardOrganization));
        check("and for the event itself", scoped.guardEvent == 123);
        check("the write is made in the caller's name (the principal, for the audit note)", scoped.writeUserId == PRINCIPAL);

        // ── Outcomes ──
        FakeDatabase missing = new FakeDatabase().reading((EventRow) null);
        check("a missing event is refused exactly like a forbidden one, before the grant is asked",
            failedWith(run(allowedState(), valid, missing), REFUSED) && missing.guardCount == 0 && missing.writeCount == 0);
        FakeDatabase forbidden = new FakeDatabase().reading(new EventRow(77, "OPEN", false));
        forbidden.grant = false;
        check("a caller without the operation there is refused, and nothing is written",
            failedWith(run(allowedState(), valid, forbidden), REFUSED) && forbidden.writeCount == 0);
        FakeDatabase guardBroken = new FakeDatabase().reading(new EventRow(77, "OPEN", false));
        guardBroken.guardFailure = Future.failedFuture("ERROR: relation \"authorization_organization_user_access\" does not exist");
        Future<String> guardFailed = run(allowedState(), valid, guardBroken);
        check("a grant lookup that fails is not a yes, and its database text is not echoed",
            failedWith(guardFailed, EventBookingStateRules.NOT_SAVED) && guardBroken.writeCount == 0);
        FakeDatabase removed = new FakeDatabase().reading(new EventRow(77, "CLOSED", true));
        check("a removed event answers deleted, with its state, and nothing is written",
            answered(run(allowedState(), move(123, "CLOSED", "OPEN"), removed), "{\"outcome\":\"deleted\",\"state\":\"CLOSED\"}")
            && removed.writeCount == 0);
        FakeDatabase moved = new FakeDatabase().reading(new EventRow(77, "CLOSED", false));
        check("an event no longer as shown answers changed, with its state now, and nothing is written",
            answered(run(allowedState(), valid, moved), "{\"outcome\":\"changed\",\"state\":\"CLOSED\"}") && moved.writeCount == 0);
        FakeDatabase legacy = new FakeDatabase().reading(new EventRow(77, null, false));
        check("a legacy event (no state) answers changed, with a null state",
            answered(run(allowedState(), valid, legacy), "{\"outcome\":\"changed\",\"state\":null}") && legacy.writeCount == 0);
        check("a write the row confirms answers moved",
            answered(run(allowedState(), move(123, "RESTRICTED", "CLOSED"), new FakeDatabase().reading(
                new EventRow(77, "RESTRICTED", false), new EventRow(77, "CLOSED", false))), "{\"outcome\":\"moved\",\"state\":\"CLOSED\"}"));
        check("a write overtaken by another writer answers changed, with what the row now says",
            answered(run(allowedState(), valid, new FakeDatabase().reading(
                new EventRow(77, "OPEN", false), new EventRow(77, "CLOSED", false))), "{\"outcome\":\"changed\",\"state\":\"CLOSED\"}"));
        check("an event deleted during the write answers deleted",
            answered(run(allowedState(), valid, new FakeDatabase().reading(
                new EventRow(77, "OPEN", false), new EventRow(77, "CLOSED", true))), "{\"outcome\":\"deleted\",\"state\":\"CLOSED\"}"));
        check("an event gone altogether after the write answers deleted, with no state",
            answered(run(allowedState(), valid, new FakeDatabase().reading(
                new EventRow(77, "OPEN", false), null)), "{\"outcome\":\"deleted\",\"state\":null}"));
        check("the outcome rules: removed before state",
            EventBookingStateRules.refusalBeforeWrite(new EventRow(1, "OPEN", true), BookingState.OPEN) == Outcome.DELETED
            && EventBookingStateRules.refusalBeforeWrite(new EventRow(1, "OPEN", false), BookingState.OPEN) == null);

        // ── The database's refusals: by name, and nothing else ──
        try {
            Path v0147 = Path.of("../../modality-base/modality-base-server-dbmigration-plugin/src/main/resources/one/modality/base/server/services/dbmigration/scripts/V0147__event_state_guards.sql");
            Set<String> raised = new TreeSet<>();
            Matcher raise = Pattern.compile("RAISE EXCEPTION '([A-Z_]+)'").matcher(Files.readString(v0147));
            while (raise.find())
                raised.add(raise.group(1));
            check("the six markers are exactly the ones V0147 raises: " + raised,
                raised.equals(new TreeSet<>(EventBookingStateRules.STATE_GUARD_MARKERS)));
        } catch (java.io.IOException e) {
            check("V0147's source is readable from the module directory (" + e.getMessage() + ")", false);
        }
        for (String marker : EventBookingStateRules.STATE_GUARD_MARKERS) {
            FakeDatabase refusing = new FakeDatabase().reading(new EventRow(77, "CLOSED", false));
            refusing.writeAnswer = Future.failedFuture("ERROR: " + marker + " (P0001)");
            check(marker + " reaches the page by name",
                failedWith(run(allowedState(), move(123, "CLOSED", "OPEN"), refusing), "[EventStateGuardError] " + marker)
                && refusing.readCount == 1);
        }
        check("the data-removed marker is not mistaken for the removed one",
            "EVENT_DATA_REMOVED_STAYS_CLOSED".equals(EventBookingStateRules.stateGuardMarkerIn("ERROR: EVENT_DATA_REMOVED_STAYS_CLOSED")));
        check("a marker inside a longer name is not a marker",
            EventBookingStateRules.stateGuardMarkerIn("ERROR: XEVENT_STATE_SEALED_2") == null);
        String secret = "ERROR: duplicate key value violates unique constraint \"event_pkey\" DETAIL: Key (name)=(Ana's Retreat)";
        FakeDatabase leaking = new FakeDatabase().reading(new EventRow(77, "OPEN", false));
        leaking.writeAnswer = Future.failedFuture(secret);
        Future<String> leaked = run(allowedState(), valid, leaking);
        check("any other database failure is the generic one, its text not echoed",
            failedWith(leaked, EventBookingStateRules.NOT_SAVED) && !leaked.cause().getMessage().contains("event_pkey"));
        check("a failure with no message is the generic one", EventBookingStateRules.NOT_SAVED.equals(
            EventBookingStateRules.failureMessage(new RuntimeException())) && EventBookingStateRules.NOT_SAVED.equals(
            EventBookingStateRules.failureMessage(null)));
        check("a read that fails is the generic failure, its text not echoed",
            failedWith(run(allowedState(), valid, new FakeDatabase().readingFailure(secret)), EventBookingStateRules.NOT_SAVED));
        FakeDatabase readBackFails = new FakeDatabase().reading(new EventRow(77, "OPEN", false)).readingFailure(secret);
        check("a read-back that fails is the generic failure, its text not echoed",
            failedWith(run(allowedState(), valid, readBackFails), EventBookingStateRules.NOT_SAVED) && readBackFails.writeCount == 1);
        FakeDatabase throwing = new FakeDatabase() {
            @Override
            public Future<Void> compareAndSet(Object callerUserId, String sql, int eventId, String expectedState) {
                throw new IllegalStateException(secret);
            }
        }.reading(new EventRow(77, "OPEN", false));
        check("a write that throws instead of failing is the generic failure too",
            failedWith(run(allowedState(), valid, throwing), EventBookingStateRules.NOT_SAVED));

        // ── Reading the row ──
        EventRow row = EventBookingStateRules.eventRowOf(new QueryResult(1, 3, new Object[] { 77, "ON_HOLD", Boolean.FALSE }, null));
        check("a row is read as organisation, state, removed",
            row != null && Integer.valueOf(77).equals(row.organizationId()) && "ON_HOLD".equals(row.state()) && !row.removed());
        EventRow legacyRow = EventBookingStateRules.eventRowOf(new QueryResult(1, 3, new Object[] { 77, null, null }, null));
        check("a null state stays null, and a null removed reads as not removed",
            legacyRow != null && legacyRow.state() == null && !legacyRow.removed());
        check("no row is no event", EventBookingStateRules.eventRowOf(new QueryResult(0, 3, new Object[0], null)) == null
                                    && EventBookingStateRules.eventRowOf(null) == null);

        // ── The reply ──
        check("the reply is the contract's two keys, in order",
            "{\"outcome\":\"moved\",\"state\":\"OPEN\"}".equals(EventBookingStateRules.replyJson(Outcome.MOVED, "OPEN")));
        check("a state is escaped as JSON, whatever it holds",
            "{\"outcome\":\"changed\",\"state\":\"A\\\"B\"}".equals(EventBookingStateRules.replyJson(Outcome.CHANGED, "A\"B")));
        check("the address is the contract's",
            "modality/service/event/setBookingState".equals(SetEventBookingStateEndpoint.SET_EVENT_BOOKING_STATE_ADDRESS));
        check("the operation is the one V0151 seeds", "ManageEventBookings".equals(EventBookingStateRules.MANAGE_EVENT_BOOKINGS));

        // ── The error texts the client reads (event-status contract §1) ──
        check("a malformed request is a move error, which the page reads as 'not allowed'",
            EventBookingStateRules.MALFORMED.startsWith("[EventBookingStateMoveError] "));
        check("a move outside the table is a move error too",
            EventBookingStateRules.MOVE_NOT_ALLOWED.startsWith("[EventBookingStateMoveError] "));
        check("any other failure is the contract's generic text",
            "[EventBookingStateError] The bookings state could not be saved".equals(EventBookingStateRules.NOT_SAVED));
        check("a refusal is RouteAccessGuard's text, word for word", REFUSED.equals(
            one.modality.crm.server.authn.gateway.shared.RouteAccessGuard.refused().cause().getMessage()));
        check("a database refusal is the contract's key and the bare marker",
            "[EventStateGuardError] EVENT_DATA_REMOVED_STAYS_CLOSED".equals(EventBookingStateRules.failureMessage(
                new RuntimeException("ERROR: EVENT_DATA_REMOVED_STAYS_CLOSED (P0001) CONTEXT: PL/pgSQL function trigger_event_state_guards()"))));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

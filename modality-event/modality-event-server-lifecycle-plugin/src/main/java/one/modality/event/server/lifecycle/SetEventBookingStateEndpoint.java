package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.function.Supplier;

/**
 * Puts an event's bookings on hold, closes them or reopens them, for the Registration › Event status page.
 *
 * <p>The argument is {@code [eventId, expected, target]}: the state the page showed, and the one it asks for. The reply
 * is a JSON string, {@code {"outcome":"moved"|"changed"|"deleted","state":string|null}}. Everything that decides it —
 * who may, which moves, the compare-and-set, the errors — is {@link EventBookingStateRules}; this class only binds it
 * to the bus and to the database.
 *
 * <p>The caller's state is read on THIS thread, synchronously, before the first asynchronous step: the grant can only
 * be checked once the event's organisation has been read, and by then {@link ThreadLocalStateHolder} has been
 * restored.
 *
 * @author Claude Code
 */
public final class SetEventBookingStateEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.SET_EVENT_BOOKING_STATE. */
    public static final String SET_EVENT_BOOKING_STATE_ADDRESS = "modality/service/event/setBookingState";

    public SetEventBookingStateEndpoint() {
        super(SET_EVENT_BOOKING_STATE_ADDRESS, argument -> EventBookingStateRules.setBookingState(
            ThreadLocalStateHolder.getThreadLocalState(), argument, ServerDatabase.INSTANCE));
    }

    /** The real database: every statement the server's, the write in the caller's name. */
    private static final class ServerDatabase implements EventBookingStateRules.Database {

        static final ServerDatabase INSTANCE = new ServerDatabase();

        @Override
        public Future<EventBookingStateRules.EventRow> readEvent(int eventId) {
            return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                    .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                    .setStatement(EventBookingStateRules.EVENT_SQL)
                    .setParameters(eventId)
                    .build()))
                .map(EventBookingStateRules::eventRowOf);
        }

        @Override
        public <T> Future<T> whenCallerHoldsOperation(Object callerState, Object organizationId, int eventId,
                                                      Supplier<Future<T>> work) {
            return RouteAccessGuard.whenCallerHoldsOperation(callerState, EventBookingStateRules.MANAGE_EVENT_BOOKINGS,
                organizationId, eventId, work);
        }

        /**
         * A batch of one rather than {@code executeSubmit}: only the batch path stamps the transaction's audit note
         * and {@code kbs.audit_person_id} from the principal (MoneyTransferVerification does the same). The
         * {@code event} table has no audit trigger today, so this costs nothing and keeps the write attributable the
         * day one is added.
         */
        @Override
        public Future<Void> compareAndSet(Object callerUserId, String sql, int eventId, String expectedState) {
            SubmitArgument update = new SubmitArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(sql)
                .setParameters(eventId, expectedState)
                .build();
            return asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { update })))
                .mapEmpty();
        }

        /** For reads, which have nothing to audit and no reason to name anybody. */
        private static <T> T asServer(Supplier<T> call) {
            return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
        }

        /**
         * For the write: the server's origin, the caller's name — as {@code ServerWrite.asServerActingFor} in the
         * person plugin and {@code MoneyTransferVerification} do.
         */
        private static <T> T asServerActingFor(Object callerUserId, Supplier<T> call) {
            return ThreadLocalStateHolder.runWithState(
                StateAccessor.setUserId(StateAccessor.createEmptyState(), callerUserId), call);
        }
    }
}

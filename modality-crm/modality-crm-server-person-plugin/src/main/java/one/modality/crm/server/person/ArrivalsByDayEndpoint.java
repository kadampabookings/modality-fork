package one.modality.crm.server.person;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

/**
 * Arrivals, departures and people present at a centre, per day and event — the counts behind the back office's
 * /registration-arrivals page. The rule and the reason the counts cross the read scope are in
 * {@link ArrivalsByDay}.
 *
 * <p>Argument: {@code [organizationId, fromDate, toDate]}, at most {@link ArrivalsByDay#MAX_WINDOW_DAYS} days,
 * both included; anything else is refused before the database is asked. Reply:
 * {@code {days: [{date, eventId, eventName, present, arriving, leaving}]}}.
 *
 * <p>Fenced on the {@code /registration} route FOR THAT ORGANISATION ({@link RouteAccessGuard#whenCallerMayReach}):
 * the id comes from the client, so it is the target the grant is checked against, not a value trusted. A route
 * grant scoped to one event does not reach a whole centre, and is refused here. The guard reads the caller from
 * the thread, so it is called synchronously, before the first async step.
 *
 * <p>The read itself runs as the server ({@link ServerWrite#asServer}): it counts bookings the caller may not
 * read, on purpose, and returns nothing that identifies one.
 *
 * @author Claude Code
 */
public final class ArrivalsByDayEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React back office's registration-arrivals feature. */
    public static final String ARRIVALS_BY_DAY_ADDRESS = "modality/service/registration/arrivalsByDay";

    public ArrivalsByDayEndpoint() {
        super(ARRIVALS_BY_DAY_ADDRESS, argument -> {
            ArrivalsByDay.Window window = ArrivalsByDay.windowOf(argument);
            if (window == null)
                return RouteAccessGuard.refused();
            return RouteAccessGuard.whenCallerMayReach("/registration", window.organizationId(), null, () ->
                ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                    .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                    .setStatement(ArrivalsByDay.COUNTS_SQL)
                    .setParameters(window.organizationId(), window.from(), window.to())
                    .build())
                ).map(ArrivalsByDay::toReply));
        });
    }
}

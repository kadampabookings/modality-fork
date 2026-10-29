package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * What the Event Lifecycle page needs to know about removals: whether this server may commit one, the retention
 * period it enforces, and for every event that has had a run, its latest run and its last successful commit —
 * with {@code changedSince}: whether the event's bookings changed after that commit, so the page can show it as due
 * again.
 *
 * <p>The argument is ignored ({@code []} or null). The reply is a JSON string (RunsJson in the shared contract,
 * see {@link EventErasureLedger}): summaries only, no grids. Super administrators only, re-checked on every call
 * ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class EventErasureRunsEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.EVENT_ERASURE_RUNS. */
    public static final String EVENT_ERASURE_RUNS_ADDRESS = "modality/service/event/erasure/runs";

    public EventErasureRunsEndpoint() {
        super(EVENT_ERASURE_RUNS_ADDRESS, argument -> EventErasureAccess.whenSuperAdmin("runs",
            caller -> EventErasureLedger.runsJson()));
        EventErasureConfig.listen();
    }
}

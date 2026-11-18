package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * One removal run in full — its status while it runs, and once it has ended, the grid of counts
 * {@code erase_past_event()} returned, or the key of why it failed.
 *
 * <p>The argument is the run id, bare or as {@code [runId]}. The reply is a JSON string (RunJson in the shared
 * contract, see {@link EventErasureLedger}, which also says why it carries neither the requesting person's id nor
 * the error text); an unknown or malformed id is {@code [EventErasureNotFoundError]}. Polled by the screen every few
 * seconds while the run is {@code running}.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}). The grid holds counts and
 * event facts, never guest data.
 *
 * @author Claude Code
 */
public final class EventErasureRunEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.EVENT_ERASURE_RUN. */
    public static final String EVENT_ERASURE_RUN_ADDRESS = "modality/service/event/erasure/run";

    public EventErasureRunEndpoint() {
        super(EVENT_ERASURE_RUN_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficerReadingStatus("run",
            caller -> {
                Integer runId = EventErasureArguments.positiveId(
                    argument instanceof Object[] array ? EventErasureArguments.at(array, 0) : argument);
                // A DPO of the run's EVENT's organization; cached, as the gate in front (a polled status)
                return EventErasureAccess.whenHoldsInOrganization("run", caller, EventOrganizations.ofRun(runId), false,
                    () -> EventErasureLedger.runJson(runId));
            }));
    }
}

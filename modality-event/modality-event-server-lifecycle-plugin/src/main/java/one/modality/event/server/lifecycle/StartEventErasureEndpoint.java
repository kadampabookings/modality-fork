package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Starts removing the personal data of one past event's bookings — a dry run, or the real thing.
 *
 * <p>The argument is {@code [eventId, mode, confirmPhrase, previewRunId]}: mode is {@code preview} or
 * {@code commit}; a preview ignores the other two; a commit needs the phrase {@code ERASE EVENT <eventId>},
 * typed, and the id of a successful dry run of the same event from the last 24 hours. The reply is the new run's
 * id, sent as soon as its ledger row exists — the run itself takes minutes, far past the bus's reply timeout, so
 * it continues on the server and the screen polls {@link EventErasureRunEndpoint} until it ends.
 *
 * <p>Super administrators only, re-checked on every call ({@link EventErasureAccess}); the arguments are judged
 * only once that has passed. Refusals the screen can explain come back as {@code [EventErasure…Error] text}; see
 * {@link EventErasureRunner} for each, and for what a run does.
 *
 * @author Claude Code
 */
public final class StartEventErasureEndpoint extends AsyncFunctionBusCallEndpoint<Object, Integer> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.EVENT_ERASURE_START. */
    public static final String START_EVENT_ERASURE_ADDRESS = "modality/service/event/erasure/start";

    public StartEventErasureEndpoint() {
        super(START_EVENT_ERASURE_ADDRESS, argument -> EventErasureAccess.whenSuperAdmin("start",
            caller -> EventErasureRunner.start(EventErasureArguments.array(argument), caller)));
        // Endpoints are instantiated at boot, so the commit switch's state is in the boot log from the start
        EventErasureConfig.listen();
    }
}

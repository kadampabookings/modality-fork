package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Who is it? — step 1 of the Data Protection Officer's "Erase personal data" page: the customers and bookings an
 * email address, a booking reference or a full name points at, in the organisations the DPO covers.
 *
 * <p>The argument is {@code [query]}, the text the DPO typed. The reply is a JSON string {@code {kind, customers,
 * bookings, truncated, hiddenElsewhere}}; the rules and the shapes are {@link ErasureFind}'s. A refusal comes back as
 * {@code [ErasureQueryError] text}, a failure as {@code [ErasureSqlError] text}. The reply names people: it is never
 * logged or stored.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class ErasureFindEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the back office's DPO_ADDRESSES (features/data-protection). */
    public static final String ERASURE_FIND_ADDRESS = "modality/service/dpo/erasure/find";

    public ErasureFindEndpoint() {
        super(ERASURE_FIND_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("erasure find",
            caller -> ErasureFind.find(argument, caller)));
    }
}

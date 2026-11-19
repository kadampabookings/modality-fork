package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Did it work? — step 3 of the Data Protection Officer's "Erase personal data" page: whether KBS still holds the
 * emails and phones captured at step 1, area by area, in the organisations the DPO covers (another organisation's
 * kept bookings are counted apart), and the given people and bookings as they are now.
 *
 * <p>The argument is {@code [emails[], phones[], personIds[], documentIds[], subject]}, at most 10 entries each;
 * {@code subject} is {@code ["person", id]}, {@code ["booker", documentId]} or null. The reply is a JSON string
 * {@code {areas: [{area, found}], keptElsewhere, people, bookings}}; the rules and the shapes are
 * {@link ErasureResidueCheck}'s. A refusal comes back as {@code [ErasureCheckError] text}, a failure (a statement that
 * ran out of time included) as {@code [ErasureSqlError] text}. The reply names people: it is never logged or stored.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class ErasureCheckEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the back office's DPO_ADDRESSES (features/data-protection). */
    public static final String ERASURE_CHECK_ADDRESS = "modality/service/dpo/erasure/check";

    public ErasureCheckEndpoint() {
        super(ERASURE_CHECK_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("erasure check",
            caller -> ErasureResidueCheck.check(argument, caller)));
    }
}

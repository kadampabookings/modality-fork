package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Creates or updates one erasure request ({@code erasure_request}, V0165) for the Data Protection Officer's "Erasure
 * requests" page.
 *
 * <p>The argument is {@code [request]}: one JSON object (as a string), checked field by field by
 * {@link ErasureRequests#parseSave} and {@link ErasureRequests#dateRefusal} — the browser's checks count for nothing.
 * Who created or closed the request is the caller of the session, never a value sent. The reply is the saved request
 * as a JSON string, the shape {@link ErasureRequestsEndpoint} lists. A refusal comes back as
 * {@code [ErasureRequestError] text} or {@code [ErasureRequestNotFoundError] text}, a failure as
 * {@code [ErasureSqlError] text}.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class SaveErasureRequestEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the back office's DPO_ADDRESSES (features/data-protection). */
    public static final String SAVE_ERASURE_REQUEST_ADDRESS = "modality/service/dpo/erasure/requests/save";

    public SaveErasureRequestEndpoint() {
        super(SAVE_ERASURE_REQUEST_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("erasure request save",
            caller -> ErasureRequests.save(argument, caller)));
    }
}

package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * The register of erasure requests ({@code erasure_request}, V0165), for the Data Protection Officer's "Erasure
 * requests" page: the latest 200, newest received first, each with its one-month deadline.
 *
 * <p>The argument is ignored ({@code []}). The reply is a JSON string {@code {requests: [...]}}; the shape is
 * {@link ErasureRequests#requestJson}'s — dates, codes, ids and booleans, no personal data. A failure comes back as
 * {@code [ErasureSqlError] text}.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class ErasureRequestsEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the back office's DPO_ADDRESSES (features/data-protection). */
    public static final String ERASURE_REQUESTS_ADDRESS = "modality/service/dpo/erasure/requests";

    public ErasureRequestsEndpoint() {
        super(ERASURE_REQUESTS_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("erasure requests",
            ErasureRequests::list));
    }
}

package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Whether the caller is a Data Protection Officer: {@code {"dpo":true|false}}. The back office draws its Data
 * protection area, and the Customers anonymise action, from this answer rather than from the grants it was pushed —
 * there, a super administrator's {@code operation:*} would pass a check that the server refuses (strictly DPO, V0163).
 *
 * <p>Never a refusal: anybody who is not a DPO, or not on a back-office session, is told no. The endpoints that act ask
 * {@link EventErasureAccess#whenDataProtectionOfficer} themselves; this one only saves the page from drawing what they
 * would refuse. The argument is ignored.
 *
 * @author Claude Code
 */
public final class DataProtectionAccessEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the back office's DPO_ADDRESSES.ACCESS (features/data-protection). */
    public static final String DPO_ACCESS_ADDRESS = "modality/service/dpo/access";

    public DataProtectionAccessEndpoint() {
        // Whether the caller is a DPO, and the organizations where they hold it for the whole organization: what the
        // Data protection pages show (admin-tiers plan, "DPO pages scoped"). Every action re-checks on the server.
        super(DPO_ACCESS_ADDRESS, argument -> EventErasureAccess.dataProtectionAccess().map(EventErasureAccess.DpoAccess::toJson));
    }
}

package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * The retention policy for the Data protection › Retention policy page: {@code {"policy":PolicyJson,"history":[…]}}
 * ({@link RetentionPolicy#pageJson}) — the current periods per stage, organisation and event, and every change, newest
 * first, with the staff person ids who made them (never names). The argument is ignored.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class RetentionPolicyEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.DPO_RETENTION. */
    public static final String DPO_RETENTION_ADDRESS = "modality/service/dpo/retention";

    public RetentionPolicyEndpoint() {
        super(DPO_RETENTION_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("retention",
            RetentionPolicy::pageJson));
    }
}

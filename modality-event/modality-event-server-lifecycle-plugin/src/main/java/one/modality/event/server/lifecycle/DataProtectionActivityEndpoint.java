package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Recent personal-data activity for the Data protection overview: {@code {"activity":[…]}}
 * ({@link DataProtectionActivity}). The argument is ignored.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}).
 *
 * @author Claude Code
 */
public final class DataProtectionActivityEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.DPO_ACTIVITY. */
    public static final String DPO_ACTIVITY_ADDRESS = "modality/service/dpo/activity";

    public DataProtectionActivityEndpoint() {
        super(DPO_ACTIVITY_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("activity",
            caller -> DataProtectionActivity.json(caller)));
    }
}

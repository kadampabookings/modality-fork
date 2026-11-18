package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Sets, changes or removes one retention period: {@code [stage, scope, targetId, months, reason]} — stage
 * {@code finalise|archive}, scope {@code default|organization|event}, targetId null for the default, months 1–240 or
 * null to remove an override (a confirmed default can be changed, not removed), reason optional (≤ 500 characters).
 * The reply is the page's JSON after the change ({@link RetentionPolicy#pageJson}). Append-only: see
 * {@link RetentionPolicy}.
 *
 * <p>Data Protection Officers only, re-checked on every call ({@link EventErasureAccess}); the arguments are judged
 * only once that has passed.
 *
 * @author Claude Code
 */
public final class SetRetentionPolicyEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.DPO_RETENTION_SET. */
    public static final String DPO_RETENTION_SET_ADDRESS = "modality/service/dpo/retention/set";

    public SetRetentionPolicyEndpoint() {
        super(DPO_RETENTION_SET_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("retention set",
            caller -> RetentionPolicy.set(EventErasureArguments.array(argument), caller)));
    }
}

package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Approves or declines, for every organisation of the caller's that was asked, erasing a person everywhere:
 * {@code [proposalRunId, "approve"|"decline"]}. An approval re-runs the proposal's preview first and is refused if
 * the data changed. The reply is the proposal's approval state.
 *
 * <p>Data Protection Officers only, re-checked on every call from fresh grants ({@link EventErasureAccess}); the rules
 * and the reply shapes are {@link ErasureApprovals}'. A refusal comes back as {@code [ErasureApproval…Error] text} or
 * {@code [DpoNotYourOrganizationError] text}, a failure as {@code [ErasureSqlError] text}. The reply names people (the
 * subject, the staff who asked and decided): it is never logged or stored.
 *
 * @author Claude Code
 */
public final class DecideErasureApprovalEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the back office's DPO_ADDRESSES (features/data-protection). */
    public static final String ERASURE_APPROVAL_DECIDE_ADDRESS = "modality/service/dpo/erasure/approval/decide";

    public DecideErasureApprovalEndpoint() {
        super(ERASURE_APPROVAL_DECIDE_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("erasure approval decide",
            caller -> ErasureApprovals.decide(EventErasureArguments.array(argument), caller)));
    }
}

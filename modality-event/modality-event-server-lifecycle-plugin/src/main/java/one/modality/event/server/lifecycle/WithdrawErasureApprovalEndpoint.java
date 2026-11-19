package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Withdraws the caller's own erase-everywhere proposal: {@code [proposalRunId]}; its pending approvals become
 * withdrawn. The reply is the proposal's approval state.
 *
 * <p>Data Protection Officers only, re-checked on every call from fresh grants ({@link EventErasureAccess}); the rules
 * and the reply shapes are {@link ErasureApprovals}'. A refusal comes back as {@code [ErasureApproval…Error] text} or
 * {@code [DpoNotYourOrganizationError] text}, a failure as {@code [ErasureSqlError] text}. The reply names people (the
 * subject, the staff who asked and decided): it is never logged or stored.
 *
 * @author Claude Code
 */
public final class WithdrawErasureApprovalEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the back office's DPO_ADDRESSES (features/data-protection). */
    public static final String ERASURE_APPROVAL_WITHDRAW_ADDRESS = "modality/service/dpo/erasure/approval/withdraw";

    public WithdrawErasureApprovalEndpoint() {
        super(ERASURE_APPROVAL_WITHDRAW_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("erasure approval withdraw",
            caller -> ErasureApprovals.withdraw(EventErasureArguments.array(argument), caller)));
    }
}

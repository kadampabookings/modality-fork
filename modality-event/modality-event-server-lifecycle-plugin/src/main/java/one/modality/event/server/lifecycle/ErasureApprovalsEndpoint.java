package one.modality.event.server.lifecycle;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * The erase-everywhere proposals that concern the caller: {@code {"waiting": […], "mine": […]}} — those waiting for
 * their organisations' approval, and their own still open. The argument is ignored.
 *
 * <p>Data Protection Officers only, re-checked on every call from fresh grants ({@link EventErasureAccess}); the rules
 * and the reply shapes are {@link ErasureApprovals}'. A refusal comes back as {@code [ErasureApproval…Error] text} or
 * {@code [DpoNotYourOrganizationError] text}, a failure as {@code [ErasureSqlError] text}. The reply names people (the
 * subject, the staff who asked and decided): it is never logged or stored.
 *
 * @author Claude Code
 */
public final class ErasureApprovalsEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** The bus address — mirrored in the back office's DPO_ADDRESSES (features/data-protection). */
    public static final String ERASURE_APPROVALS_ADDRESS = "modality/service/dpo/erasure/approvals";

    public ErasureApprovalsEndpoint() {
        super(ERASURE_APPROVALS_ADDRESS, argument -> EventErasureAccess.whenDataProtectionOfficer("erasure approvals",
            caller -> ErasureApprovals.list(caller)));
    }
}

package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.Set;

/**
 * Stops a client granting itself anything: only a super admin's client may write the tables that decide what anybody
 * may do.
 *
 * <h3>Why this cannot wait for the switch</h3>
 *
 * <p>{@link ProtectedEntityWritesJob} already names these tables, but only observes. So while it observes, one inserted
 * {@code AuthorizationSuperAdmin} row naming the caller's own person hands them {@code operation:*} at the next push,
 * and with it support views of any customer, back-office views of any staff member, the passkey approval queue and
 * second-factor resets. Matching grants on the session's person id rather than an email closed the other road to the
 * same place; this closes this one.
 *
 * <h3>The rule, for client writes of any verb</h3>
 *
 * <p>Every privilege-bearing entity (the Authorization* tables, Operation and OperationGroup — the job's list) is
 * written by a super admin or not at all. Deletes included: removing somebody's grant changes who may do what as much
 * as adding one. Membership is asked of the database on every write, not of the cached grant set, so a revoked super
 * admin stops at once. A caller with no person of their own (anonymous, a guest) is refused without a lookup, and so
 * is a support view, whose principal is the customer being looked at.
 *
 * <p>Why not the delegable {@code ManageAuthorizations} the job names: a role's grants are pushed under an
 * {@code organizationId} context, so a context-free question never matches them, and only super admins could pass
 * that check anyway. Letting organization-level staff manage their own organization's roles would need the row's
 * organization as the context — a later rule, if anyone turns out to need it.
 *
 * <p>Enforced on the client-write guard, which only client writes reach: server code writing these tables is not
 * affected, and neither is KBS2, which writes to the database itself.
 *
 * @author Claude Code
 */
final class GrantTableWritePolicy implements ClientSubmitGuard.WritePolicy {

    static final String REFUSED = "Only a super admin can change authorizations";
    static final String RULES_OFF_REFUSED = "Authorization rules are switched off: they can be deleted, not created or changed";

    private static final Set<String> GRANT_TABLES = ProtectedEntityWritesJob.privilegeBearingEntities();

    /** Whether this write is on a table that decides what anybody may do. */
    static boolean isGrantTableWrite(ProtectedEntityWriteRegistry.WriteRequest write) {
        String entity = write == null ? null : write.entityName();
        return entity != null && GRANT_TABLES.contains(entity);
    }

    /**
     * The refusal decided without a lookup, or null when the write is not on a grant table or membership must decide.
     * Package-private so scripts/checks/client-write-guard can check it without a running server.
     */
    static String refusalWithoutLookup(ProtectedEntityWriteRegistry.WriteRequest write, Object userId) {
        if (!isGrantTableWrite(write))
            return null;
        if (callerPersonId(userId) == null)
            return REFUSED;
        return writesSwitchedOffRule(write) ? RULES_OFF_REFUSED : null;
    }

    /**
     * Whether this write creates or changes an authorization rule while rules are switched off - for anybody, super
     * admins included: a rule written now would grant nothing, look as if it did, and all come back to life the day
     * rules were switched on again. Deleting one is allowed, and so is unassigning one from its role (role=null),
     * which deleting a role does first.
     */
    static boolean writesSwitchedOffRule(ProtectedEntityWriteRegistry.WriteRequest write) {
        if (ModalityAuthorizationServerServiceProvider.RULE_GRANTS_APPLIED || !"AuthorizationRule".equals(write.entityName()))
            return false;
        if (write.verb() == ProtectedEntityWriteRegistry.WriteVerb.DELETE)
            return false;
        boolean unassignsOnly = write.verb() == ProtectedEntityWriteRegistry.WriteVerb.UPDATE
            && write.writtenFields() != null && write.writtenFields().length == 1 && "role".equals(write.writtenFields()[0])
            && write.writtenValues() != null && write.writtenValues().get("role") == null;
        return !unassignsOnly;
    }

    /** The person a caller acts as, or null for one who has none of their own to act as: anonymous, guest, support view. */
    static Object callerPersonId(Object userId) {
        if (userId instanceof ModalityUserPrincipal principal && principal.isSupportView())
            return null;
        return ModalityUserPrincipal.getUserPersonId(userId);
    }

    @Override
    public Future<String> refusalReason(ProtectedEntityWriteRegistry.WriteRequest write) {
        // Read on the caller's thread: the guard asks synchronously, and the state is gone after the first async hop
        Object userId = ThreadLocalStateHolder.getUserId();
        if (!isGrantTableWrite(write))
            return Future.succeededFuture(null);
        String refusal = refusalWithoutLookup(write, userId);
        if (refusal != null)
            return Future.succeededFuture(refusal);
        return SuperAdminMembership.isSuperAdminPerson(callerPersonId(userId), EntityStore.create(DataSourceModelService.getDefaultDataSourceModel()))
            .map(superAdmin -> Boolean.TRUE.equals(superAdmin) ? null : REFUSED);
    }
}

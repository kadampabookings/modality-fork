package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;

/**
 * "Does the caller hold this operation?", answered by the authorization framework for modules that do not depend on
 * it.
 *
 * <p>A seam rather than a dependency. The framework's server API ({@code AuthorizationServerService}) lives in
 * webfx-stack-authz-server; a module that wants to ask it would otherwise need that dependency added to its generated
 * pom and module-info, which means a repo-wide {@code webfx update}. Every server module that guards an endpoint
 * already requires this one, and the authz plugin — which requires both — registers the framework-backed answer at
 * boot ({@code ProtectedEntityWritesJob}). Same shape as {@code ModalityCallerParameterResolver}.
 *
 * <p><b>Fails closed.</b> With nothing registered (the authz plugin missing, or failing at boot) every question is
 * answered no. An unanswered authorization question has not been answered yes.
 *
 * <p>Like the framework, an authority reads the caller from the thread-local state: call it on the caller's thread,
 * before the first asynchronous step.
 *
 * @author Claude Code
 */
public final class OperationAuthority {

    private OperationAuthority() {
    }

    /** What the authz plugin provides. */
    public interface Authority {
        /**
         * Whether the caller holds the operation for a WHOLE organization, in some organization: a grant scoped to one
         * event or event type does not count, and neither does a wildcard for an explicit-only operation (V0166).
         *
         * @param fresh answer from grants recomputed for this one question rather than from the caches — for
         *              irreversible actions, where a role revoked a moment ago must not still answer
         */
        Future<Boolean> holdsInSomeOrganization(String operationCode, boolean fresh);

        /**
         * Whether the caller holds the operation for the whole of THIS organization: an organization-wide grant there
         * (a grant scoped to one event or event type of it does not count), or a context-free one — never a wildcard
         * for an explicit-only operation. For an action on something one organization owns (an event, its period).
         * Defaults to no, so an authority that has not implemented it refuses.
         */
        default Future<Boolean> holdsInOrganization(String operationCode, Object organizationId, boolean fresh) {
            return Future.succeededFuture(false);
        }
    }

    private static volatile Authority authority;

    /** Registers the authority. Called once at boot by the authz plugin; a later call replaces it. */
    public static void register(Authority authority) {
        OperationAuthority.authority = authority;
    }

    /** Whether an authority is registered — for a boot check that wants to say so loudly. */
    public static boolean isRegistered() {
        return authority != null;
    }

    /** @see Authority#holdsInOrganization — no organization is a no. */
    public static Future<Boolean> holdsInOrganization(String operationCode, Object organizationId, boolean fresh) {
        Authority a = authority;
        if (a == null || operationCode == null || organizationId == null)
            return Future.succeededFuture(false);
        try {
            return a.holdsInOrganization(operationCode, organizationId, fresh).otherwise(false);
        } catch (RuntimeException e) {
            return Future.succeededFuture(false);
        }
    }

    /** @see Authority#holdsInSomeOrganization */
    public static Future<Boolean> holdsInSomeOrganization(String operationCode, boolean fresh) {
        Authority a = authority;
        if (a == null || operationCode == null)
            return Future.succeededFuture(false);
        try {
            return a.holdsInSomeOrganization(operationCode, fresh).otherwise(false);
        } catch (RuntimeException e) {
            return Future.succeededFuture(false);
        }
    }
}

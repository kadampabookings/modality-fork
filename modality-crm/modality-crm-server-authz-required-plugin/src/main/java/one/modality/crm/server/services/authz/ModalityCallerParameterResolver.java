package one.modality.crm.server.services.authz;

import dev.webfx.stack.db.query.ServerCallerParameters;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

/**
 * Answers {@code CALLER_PERSON}, {@code CALLER_ACCOUNT} and the caller's granted scope from the principal on
 * the thread.
 *
 * <p>The framework compiles those terms to bound parameters and knows nothing about a person or an account,
 * so this is the half that does. It is asked on the endpoint thread, before the first async hop, which is the
 * only place the principal is still there — see {@link ServerCallerParameters}.
 *
 * <p><b>Null is an answer, not a failure.</b> An anonymous booker has no person and no account, and a
 * predicate comparing a column against null matches no rows — which is the safe direction for a term whose
 * whole purpose is to narrow a read to its caller. The plan's open question 2 asks whether an anonymous
 * caller should instead be refused outright; until that is decided, matching nothing is the answer that
 * cannot leak, and the anonymous booking form keeps working because it uses no caller term.
 *
 * <p>The support view is deliberately NOT special-cased here. A support agent acting as someone else reads as
 * that someone else, which is what the principal already says and what the write policies already assume;
 * making this the one place that disagrees would put the two out of step.
 *
 * @author Claude Code
 */
public final class ModalityCallerParameterResolver implements ServerCallerParameters.Resolver {

    public static void register() {
        ServerCallerParameters.registerResolver(new ModalityCallerParameterResolver());
    }

    @Override
    public Object resolveCallerValue(String reservedName) {
        Object userId = ThreadLocalStateHolder.getUserId();
        switch (reservedName) {
            case "caller.person":  return ModalityUserPrincipal.getUserPersonId(userId);
            case "caller.account": return ModalityUserPrincipal.getUserAccountId(userId);
            // The caller's READ SCOPE, flattened by CallerReadScope when their grants were computed. Looked up
            // and never computed here: this runs before the first async hop, so there is no query to await.
            case "caller.grantedEvents":
                return grantedScopeOf(userId, true);
            case "caller.grantedOrganizations":
                return grantedScopeOf(userId, false);
            // A name the framework attaches and this does not know. Null rather than a throw: the binding
            // seam refuses a statement whose server term nothing resolved, by name, which is a better place
            // to fail than inside a resolver that cannot say which statement asked.
            default: return null;
        }
    }

    /**
     * One array of the caller's flattened scope, or null when none has been computed for them.
     *
     * <p>Null rather than an empty array, deliberately. An empty array is a real answer — a signed-in user
     * holding no grants reads nothing — and binding it for "not computed yet" would turn a cold cache into a
     * screen that is silently empty instead of one that fails. Null reaches the predicate as a comparison
     * against null, which also matches nothing, but it is counted and logged by {@link CallerReadScope} so a
     * systematic miss is visible rather than mistaken for a user without grants.
     */
    private static Object grantedScopeOf(Object userId, boolean events) {
        CallerReadScope.Scope scope = CallerReadScope.peek(ModalityUserPrincipal.getUserPersonId(userId));
        return scope == null ? null : events ? scope.eventIds() : scope.organizationIds();
    }
}

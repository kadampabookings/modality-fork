package one.modality.crm.server.services.authz;

import dev.webfx.extras.operation.HasOperationCode;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.authz.server.AuthorizationServerService;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

/**
 * Says which entities carry privilege, and who may write them.
 *
 * <p>The authorization tables are where privilege escalation lives: a caller able to write them can
 * grant themselves anything, at which point every other control in the system is decorative. So they
 * are the first entities the server refuses to change on a caller's say-so, and the reason item 6
 * starts here rather than with the data that merely matters.
 *
 * <p><b>The operation code is satisfied by super admins with no data migration.</b> A super admin's
 * grants are the literal string {@code grant operation:*}, and the wildcard matches any code that does
 * not begin with {@code RouteTo} — so no Operation row has to exist for this to work today, and adding
 * one later is how the right becomes delegable to somebody who is not a super admin. Which is the right
 * default: /operations and /authorizations sit under the Super Admin tile precisely because
 * administering privilege is not something a route grant should be able to hand out.
 *
 * <p>Note this deliberately does NOT check the route the back office uses to open that screen. Route
 * rules are a client concern — they decide which screen opens — and the server does not register their
 * parser at all. Asking the server whether a screen may open would be answering a different question
 * from the one that matters here, which is whether these rows may change.
 */
public final class ProtectedAuthorizationWritesJob implements ApplicationJob {

    /**
     * The entities whose rows decide what anyone may do. Named as they appear in DQL, because that is
     * what the registry matches against.
     */
    private static final String[] PRIVILEGE_BEARING_ENTITIES = {
        "AuthorizationRule",
        "AuthorizationRole",
        "AuthorizationRoleOperation",
        "AuthorizationOrganizationAdmin",
        "AuthorizationOrganizationUserAccess",
        "AuthorizationSuperAdmin",
        "Operation",
        "OperationGroup",
    };

    private static final String MANAGE_AUTHORIZATIONS = "ManageAuthorizations";

    /**
     * OBSERVE FIRST. While this is false the decision is computed and logged but never acted on, so a
     * deploy cannot lock anyone out of the screen they would need to fix a lockout — the failure mode
     * that makes authorization administration the worst possible place to enforce something untested.
     * Flip it once the log has been quiet on a real environment for long enough to believe it.
     *
     * <p>Not a config value yet, deliberately: a switch that can be flipped without a code review is
     * one that can be flipped without anyone reading the log it depends on.
     */
    private static final boolean ENFORCING = false;

    @Override
    public void onInit() {
        ProtectedEntityWriteRegistry.registerWriteAuthorizer(this::isWriteAuthorized, PRIVILEGE_BEARING_ENTITIES);
        Console.log("🛡 Authorization-table writes require " + MANAGE_AUTHORIZATIONS
                    + (ENFORCING ? " — ENFORCING" : " — observing only, nothing is refused yet"));
    }

    private Future<Boolean> isWriteAuthorized(String entityName, ProtectedEntityWriteRegistry.WriteVerb verb) {
        // Read on THIS thread, while the request's state is still in place: past the first async hop
        // there is no principal left to log, and a check that reports "unknown" for every caller would
        // make the observation phase useless.
        Object userId = ThreadLocalStateHolder.getUserId();
        return AuthorizationServerService.isAuthorized(new ManageAuthorizationsRequest())
            .otherwise(false)
            .map(authorized -> {
                if (Boolean.TRUE.equals(authorized))
                    return true;
                // The principal is logged because this line exists to be acted on: knowing that SOMETHING
                // was refused is not enough to tell whether a real administrator is about to be locked
                // out. It is an account identity, not personal data about a data subject.
                Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " " + verb + " on "
                            + entityName + " by " + userId + " (no " + MANAGE_AUTHORIZATIONS + " grant)");
                return !ENFORCING;
            });
    }

    /** The question put to the rule registry. Only {@link HasOperationCode} requests match operation rules. */
    private static final class ManageAuthorizationsRequest implements HasOperationCode {
        @Override
        public Object getOperationCode() {
            return MANAGE_AUTHORIZATIONS;
        }
    }
}

package one.modality.crm.server.services.authz;

import dev.webfx.extras.operation.HasOperationCode;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.authz.server.AuthorizationServerService;
import dev.webfx.stack.authz.server.spi.AuthorizationServerServiceProvider;
import dev.webfx.stack.authz.server.spi.impl.AuthorizationServerServiceProviderBase;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Map;

/**
 * Says which entities the server will not write on a caller's say-so, and what authorizes each.
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
public final class ProtectedEntityWritesJob implements ApplicationJob {

    private static final String MANAGE_AUTHORIZATIONS = "ManageAuthorizations";

    /**
     * Which operation codes authorize writing each protected entity — ANY of them is enough.
     *
     * <p>Any-of, not all-of, because this seam sees an entity and a verb but not which FIELDS a
     * statement writes. A letter's content and its properties are gated separately in the back office,
     * and both live on the same row: demanding one code would refuse somebody who legitimately holds
     * only the other. Any-of never denies a legitimate holder, while still refusing the caller who
     * holds neither — which is the escalation this is here to stop. Telling the two apart needs
     * field-level policy, which the spec sequences later; the loss is recorded here rather than left
     * to be discovered.
     *
     * <p>Named as they appear in DQL, because that is what the registry matches against.
     */
    private static final Map<String, String[]> REQUIRED_OPERATIONS = Map.ofEntries(
        // Privilege-bearing: a caller able to write these can grant themselves anything, at which point
        // every other control is decorative. Operation and OperationGroup are included because
        // rewriting an operation's definition is the same power reached by a different road.
        Map.entry("AuthorizationRule",                  new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationRole",                  new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationRoleOperation",         new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationOrganizationAdmin",     new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationOrganizationUserAccess",new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationSuperAdmin",            new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("Operation",                          new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("OperationGroup",                     new String[] { MANAGE_AUTHORIZATIONS }),
        // Content surfaces whose back-office gates currently read `hasPermission(...) || true` — the
        // operation is NAMED and then unconditionally allowed, so today they are decoration. The server
        // check is what makes the name mean something; removing the `|| true` afterwards is what makes
        // the UI agree with it, and must not happen before the grants exist or the buttons vanish for
        // everyone.
        Map.entry("Letter",       new String[] { "EditLetterContent", "EditLetterProperties" }),
        Map.entry("PassTemplate", new String[] { "EditPassTemplate" }),
        // Administrative entities no ordinary member writes: zero front-office write sites, so an
        // operation code is the right shape of control here. Contrast Person (17 front-office write
        // sites), Document, DocumentLine and Attendance, where a member's right to write rests on the
        // row being THEIRS — an operation check there would either refuse every member or grant every
        // member, and neither is a boundary. Those need target-id resolution, not a longer list.
        //
        // NOT the RouteTo codes the back office uses to open these screens: `operation:*` deliberately
        // does not match a code beginning with RouteTo, so requiring RouteToOrganizations here would
        // refuse even a super admin. A code that means "may change these rows" is the right question
        // anyway — opening a screen and writing through it are not the same right.
        Map.entry("Organization", new String[] { "ManageOrganizations" }),
        Map.entry("MoneyAccount", new String[] { "ManageMoneyAccounts" })
    );

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
        ProtectedEntityWriteRegistry.registerWriteAuthorizer(this::isWriteAuthorized,
            REQUIRED_OPERATIONS.keySet().toArray(String[]::new));
        ProtectedEntityWriteRegistry.registerWriteObserver(ProtectedEntityWritesJob::onProtectedWriteSucceeded);
        Console.log("🛡 Write authorization active on " + REQUIRED_OPERATIONS.size() + " entities"
                    + (ENFORCING ? " — ENFORCING" : " — observing only, nothing is refused yet"));
    }

    /**
     * Throws away the cached grants when the rows they were computed from change.
     *
     * <p>Only the privilege-bearing entities matter here: editing a letter does not change what anyone
     * may do, so flushing on that would empty the cache constantly for nothing.
     *
     * <p>This is the fast path, not the guarantee. It fixes the instance that served the write; another
     * instance behind the same load balancer saw nothing and still holds its own copy, which is why the
     * registry cache also has a TTL. Without both, a revocation was not slow — it was indefinite,
     * lasting until the process restarted.
     */
    private static void onProtectedWriteSucceeded(String entityName, ProtectedEntityWriteRegistry.WriteVerb verb) {
        if (!entityName.startsWith("Authorization") && !entityName.startsWith("Operation"))
            return;
        AuthorizationServerServiceProvider provider = AuthorizationServerService.getProvider();
        if (provider instanceof AuthorizationServerServiceProviderBase base) {
            base.invalidateAllRuleRegistries();
            Console.log("🛡 " + verb + " on " + entityName + " — cached authorizations discarded on this instance");
        }
    }

    private Future<Boolean> isWriteAuthorized(String entityName, ProtectedEntityWriteRegistry.WriteVerb verb) {
        String[] codes = REQUIRED_OPERATIONS.get(entityName);
        if (codes == null) // the textual pre-filter matched a name this policy does not actually cover
            return Future.succeededFuture(true);
        // Read on THIS thread, while the request's state is still in place: past the first async hop
        // there is no principal left to log, and a check that reports "unknown" for every caller would
        // make the observation phase useless.
        Object userId = ThreadLocalStateHolder.getUserId();
        return holdsAnyOf(codes)
            .map(authorized -> {
                if (Boolean.TRUE.equals(authorized))
                    return true;
                // The principal is logged because this line exists to be acted on: knowing that SOMETHING
                // was refused is not enough to tell whether a real administrator is about to be locked
                // out. It is an account identity, not personal data about a data subject.
                Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " " + verb + " on "
                            + entityName + " by " + userId + " (holds none of " + String.join(", ", codes) + ")");
                return !ENFORCING;
            });
    }

    /** True as soon as one code authorizes; asked one at a time so a granted caller stops at the first. */
    private Future<Boolean> holdsAnyOf(String[] codes) {
        Future<Boolean> result = Future.succeededFuture(false);
        for (String code : codes)
            result = result.compose(alreadyAuthorized -> Boolean.TRUE.equals(alreadyAuthorized)
                ? Future.succeededFuture(true)
                : AuthorizationServerService.isAuthorized(new OperationRequest(code)).otherwise(false));
        return result;
    }

    /** The question put to the rule registry. Only {@link HasOperationCode} requests match operation rules. */
    private record OperationRequest(String operationCode) implements HasOperationCode {
        @Override
        public Object getOperationCode() {
            return operationCode;
        }
    }
}

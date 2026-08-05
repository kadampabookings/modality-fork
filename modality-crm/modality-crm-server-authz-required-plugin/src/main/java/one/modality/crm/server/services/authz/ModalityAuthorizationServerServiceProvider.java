package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.CompositeFuture;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Strings;
import dev.webfx.stack.authn.AuthenticationService;
import dev.webfx.stack.authn.UserClaims;
import dev.webfx.stack.authz.server.spi.AuthorizationServerServiceProvider;
import dev.webfx.stack.com.bus.DeliveryOptions;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityList;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.push.server.PushServerService;
import dev.webfx.stack.session.state.LogoutUserId;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Bruno Salmon
 */
public final class ModalityAuthorizationServerServiceProvider implements AuthorizationServerServiceProvider {

    // TODO: Share these constants with the client counterpart (ModalityInMemoryUserAuthorizationChecker).
    private final static String CLIENT_AUTHZ_SERVICE_ADDRESS = "modality/service/authz";

    // On a deploy every client reconnects within seconds and each reconnection triggers a push, so the grants
    // computation (1 query for the public set, 4+ queries per logged-in user) is cached for a short period.
    // A pending future doubles as in-flight dedup; grants change rarely, and a fresh login always computes
    // fresh (no cache entry yet), so the TTL only bounds how long admin grant edits take to reach clients
    // that are already connected.
    private static final long AUTHZ_CACHE_TTL_MILLIS = 120_000;
    // Above this size, expired entries are swept on lookup (the map otherwise only replaces entries in place).
    private static final int AUTHZ_CACHE_PRUNE_SIZE = 100;

    // The public principal has no userId; this sentinel keys its 2 cache entries (frontoffice & backoffice)
    private static final Object PUBLIC_PRINCIPAL = "PUBLIC";

    private record AuthzCacheKey(Object principal, boolean backoffice) {}
    private record CachedAuthz(Future<String> future, long computedAtMillis) {}

    // Single-threaded access (Vert.x event loop — see AuthorizationServerJob), so a plain HashMap is safe
    private final Map<AuthzCacheKey, CachedAuthz> authzCache = new HashMap<>();

    @Override
    public Future<Void> pushAuthorizations() {
        // Capturing userId and runId from the thread local state holder (won't be present on later async callbacks)
        Object userId = ThreadLocalStateHolder.getUserId();
        String runId = ThreadLocalStateHolder.getRunId();
        boolean backoffice = ThreadLocalStateHolder.isBackoffice();
        return getOrComputeAuthorizations(userId, backoffice)
            .compose(pushObject -> pushAuthorizationsObject(pushObject, runId));
    }

    private Future<String> getOrComputeAuthorizations(Object userId, boolean backoffice) {
        boolean isPublic = LogoutUserId.isLogoutUserIdOrNull(userId);
        AuthzCacheKey key = new AuthzCacheKey(isPublic ? PUBLIC_PRINCIPAL : userId, backoffice);
        CachedAuthz cached = authzCache.get(key);
        long now = System.currentTimeMillis();
        // Reusable while still in-flight (dedup) or completed successfully and younger than the TTL
        if (cached != null && (!cached.future.isComplete() || cached.future.succeeded() && now - cached.computedAtMillis < AUTHZ_CACHE_TTL_MILLIS))
            return cached.future;
        if (authzCache.size() > AUTHZ_CACHE_PRUNE_SIZE)
            authzCache.values().removeIf(c -> c.future.isComplete() && now - c.computedAtMillis >= AUTHZ_CACHE_TTL_MILLIS);
        Future<String> future = computeAuthorizations(userId, isPublic, backoffice);
        authzCache.put(key, new CachedAuthz(future, now));
        // A failed computation must not be served to subsequent callers
        future.onFailure(e -> {
            CachedAuthz current = authzCache.get(key);
            if (current != null && current.future == future)
                authzCache.remove(key);
        });
        return future;
    }

    private Future<String> computeAuthorizations(Object userId, boolean isPublic, boolean backoffice) {
        // Returning the public operations only when the user is logged out or null (i.e., not logged in)
        if (isPublic) {
            EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
            return entityStore.<Operation>executeQuery("select operationCode, grantRoute from Operation op where ($1 and backoffice or !$1 and frontoffice) and public", backoffice)
                .map(operations -> grantOperations(operations, new StringBuilder("logout\n")).toString());
        }
        // Otherwise reading the authorizations from the database:
        // Step 1: we ask the user claims, so we can identify the user by his email
        return AuthenticationService.getUserClaims()
            // Step 2: we load the user authorizations from the database
            .compose(userClaims ->
                loadUserAuthorizations(userClaims, backoffice)
            );
    }

    private Future<String> loadUserAuthorizations(UserClaims userClaims, boolean backoffice) {
        String userEmail = userClaims.email();
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return Future.all(
            // Public and guest operations are automatically granted to all logged-in users (registered or guest users).
            // Non-public/guest operations require explicit grant access from administrators.
            entityStore.<Operation>executeQuery("select operationCode, grantRoute from Operation op where ($1 and backoffice or !$1 and frontoffice) and (public or guest)", backoffice)
                .map(operations -> grantOperations(operations, new StringBuilder()).toString()),
            // Loading operations and rules granted to the user
            entityStore.<AuthorizationOrganizationUserAccess>executeQuery(
                    "select organization.id,event.id,role.id from AuthorizationOrganizationUserAccess where user.email=$1 order by organization.id,event?.id", userEmail)
                .compose(userAccesses -> {
                    int n = userAccesses.size();
                    return new Batch<>(userAccesses.toArray(new AuthorizationOrganizationUserAccess[n]))
                        .executeParallel(CompositeFuture[]::new, userAccess ->
                            Future.all(
                                entityStore.executeQuery("select operationCode, grantRoute from Operation op where ($1 and backoffice or !$1 and frontoffice) and exists(select AuthorizationRoleOperation ro where ro.role=$2 and (ro.operation = op or ro.operationGroup = op.group))", backoffice, userAccess.getRole()),
                                entityStore.executeQuery("select rule from AuthorizationRule where role=$1", userAccess.getRole())
                            )
                        ).map(batch -> {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < n; i++) {
                                AuthorizationOrganizationUserAccess userAccess = userAccesses.get(i);
                                EntityList<Operation> operations = batch.get(i).resultAt(0);
                                EntityList<AuthorizationRule> rules = batch.get(i).resultAt(1);
                                sb.append("context:organizationId=").append(Entities.getPrimaryKey(userAccess.getOrganizationId()));
                                Object eventId = Entities.getPrimaryKey(userAccess.getEventId());
                                if (eventId != null)
                                    sb.append(",eventId=").append(eventId);
                                sb.append("\n");
                                grantOperations(operations, sb);
                                rules.forEach(rule -> sb.append(rule.getRule()).append("\n"));
                            }
                            return sb.toString();
                        });
                }),
            // If the user is also the admin of an organization, we grant him the permissions to manage the organization
            entityStore.<AuthorizationOrganizationAdmin>executeQuery("select organization.id from AuthorizationOrganizationAdmin where admin.email=$1 order by organization.id", userEmail)
                .map(organizationAdmins -> {
                    StringBuilder sb = new StringBuilder();
                    organizationAdmins.forEach(organizationAdmin -> {
                        sb.append("context:organizationId=").append(Entities.getPrimaryKey(organizationAdmin.getOrganizationId())).append("\n");
                        sb.append("grant operation:RouteToAdmin\n");
                        sb.append("grant route:/admin*\n");
                    });
                    return sb.toString();
                }),
            // If the user is a super admin, we grant him universal permissions
            entityStore.<AuthorizationSuperAdmin>executeQuery("select AuthorizationSuperAdmin where superAdmin.email=$1 limit 1", userEmail)
                .map(superAdmins -> {
                    if (superAdmins.isEmpty())
                        return "";
                    return """
                        context:any
                        grant operation:*
                        grant route:*
                        """;
                })
        ).map(compositeFuture -> {
            String loggedInGrants   = (String) compositeFuture.list().get(0);
            String userGrants       = (String) compositeFuture.list().get(1);
            String adminGrants      = (String) compositeFuture.list().get(2);
            String superAdminGrants = (String) compositeFuture.list().get(3);
            return superAdminGrants.isEmpty() ? loggedInGrants + userGrants + adminGrants : superAdminGrants;
        });
    }

    private StringBuilder grantOperations(List<Operation> operations, StringBuilder sb) {
        operations.forEach(operation -> {
            sb.append("grant operation:").append(operation.getOperationCode()).append("\n");
            String grantRoute = operation.getGrantRoute();
            if (!Strings.isEmpty(grantRoute))
                sb.append("grant route:").append(grantRoute).append("\n");
        });
        return sb;
    }

    private <T> Future<T> pushAuthorizationsObject(Object pushObject, Object runId) {
        return PushServerService.push(
            CLIENT_AUTHZ_SERVICE_ADDRESS,
            pushObject,
            new DeliveryOptions(),
            runId);
    }
}

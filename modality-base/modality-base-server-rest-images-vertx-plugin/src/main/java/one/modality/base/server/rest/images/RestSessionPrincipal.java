package one.modality.base.server.rest.images;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.orm.entity.EntityList;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.Session;
import dev.webfx.stack.session.SessionService;
import dev.webfx.stack.session.state.LogoutUserId;
import dev.webfx.stack.session.state.RestrictedPrincipalRegistry;
import dev.webfx.stack.session.state.SessionAccessor;
import io.vertx.ext.web.RoutingContext;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the caller of an HTTP request on {@code /rest/images/*} to a principal.
 *
 * <p>This is the first authenticated HTTP surface in the codebase. Everything else that needs
 * a user identity runs over the event bus, where {@code ThreadLocalStateHolder} carries it —
 * that holder is <b>not</b> populated on the HTTP path and would not survive a
 * {@code .compose()} hop anyway, so identity here is resolved explicitly and passed along as a
 * value.
 *
 * <p>The mechanism follows the one the OAuth login gateways already use: the client sends the
 * server-assigned session id it received when the bus connected, and the server looks it up in
 * the same session store the bus writes to. The id travels in the {@value #SESSION_HEADER}
 * header rather than a cookie, which has a useful side effect: a browser will not attach a
 * custom header to a cross-site request without a CORS preflight we do not answer, so these
 * endpoints are not reachable by cross-site request forgery.
 *
 * <p>"Staff" is not taken from the session's {@code backoffice} flag alone. That flag is set
 * from client-supplied state, so it says which app is connected, not what the person is allowed
 * to do; on its own it would be trivially spoofable. It is paired with a database check that the
 * person actually holds a back-office role or super-admin rights.
 */
final class RestSessionPrincipal {

    /** Header carrying the server-assigned session id. Mirrored in the React client helper. */
    static final String SESSION_HEADER = "X-KBS-Session-Id";

    /** How long a positive staff answer is trusted before being re-checked against the database. */
    private static final long STAFF_CACHE_TTL_MILLIS = 120_000;

    /** personId -> epoch millis after which the cached "is staff" answer expires. */
    private static final Map<Object, Long> STAFF_CACHE = new HashMap<>();

    /** The resolved caller. */
    static final class Caller {
        private final Object userId;
        private final boolean staff;

        private Caller(Object userId, boolean staff) {
            this.userId = userId;
            this.staff = staff;
        }

        /** True when a registered user is behind this request. */
        boolean isRegisteredUser() {
            return userId instanceof ModalityUserPrincipal;
        }

        /**
         * True when this session may read but not write.
         *
         * <p>Today that means a support view: a member of staff looking at somebody else's front
         * office. Those sessions are blocked from writing at the SQL submit layer, which every
         * database write passes through — but an image upload goes to the CDN, not the database,
         * so it would slip past that guarantee entirely unless refused here. The registry is the
         * same one the submit layer consults, so the two answers cannot drift apart.
         */
        boolean isReadOnly() {
            // Two sources on purpose. The registry is the same one the SQL submit layer consults,
            // so the two answers cannot drift; the direct check does not depend on the gateway
            // module having registered its predicate before this request arrived.
            return (userId instanceof ModalityUserPrincipal mup && mup.isSupportView())
                || RestrictedPrincipalRegistry.isRestricted(userId);
        }

        /** True when the caller holds a back-office role or super-admin rights (database-verified). */
        boolean isStaff() {
            return staff;
        }

        /**
         * The caller's person id, or null when the caller is not a registered user.
         *
         * <p>A support view (a staff member looking at somebody else's front office) reports the
         * account holder's person id here, which is correct: it is that person's data on screen.
         */
        Object getPersonId() {
            return ModalityUserPrincipal.getUserPersonId(userId);
        }
    }

    private RestSessionPrincipal() {}

    /**
     * Resolve the caller of a request.
     *
     * @return a succeeded future holding the caller, or a failed future when the header is
     *         missing, the session is unknown, or nobody is logged in on it. Callers translate
     *         that failure into 401 — the distinction between the three cases is deliberately
     *         not reported back, so the endpoint cannot be used to probe session ids.
     */
    static Future<Caller> resolve(RoutingContext ctx) {
        String sessionId = ctx.request().getHeader(SESSION_HEADER);
        if (sessionId == null || sessionId.isEmpty())
            return Future.failedFuture("No session header");
        return SessionService.getSessionStore().get(sessionId)
            .compose(session -> {
                if (session == null)
                    return Future.failedFuture("Unknown session");
                // Capture out of the session before the async staff probe: nothing about the
                // ambient request context survives the compose below.
                Object userId = SessionAccessor.getUserId(session);
                if (LogoutUserId.isLogoutUserIdOrNull(userId))
                    return Future.failedFuture("Not logged in");
                boolean clientClaimsBackoffice = Boolean.TRUE.equals(SessionAccessor.isBackoffice(session));
                // A support view must never be treated as staff. Its person id is the *viewed*
                // account's, so asking the database whether that person holds a back-office role
                // would answer about the wrong human — and answer yes whenever a staff member's
                // own account is the one being viewed.
                if (!clientClaimsBackoffice || !(userId instanceof ModalityUserPrincipal mup)
                        || mup.isSupportView() || RestrictedPrincipalRegistry.isRestricted(userId))
                    return Future.succeededFuture(new Caller(userId, false));
                return isStaff(mup.getUserPersonId())
                    .map(staff -> new Caller(userId, staff));
            })
            .recover(error -> Future.failedFuture("Session not resolvable"));
    }

    /**
     * Does any of these role grants carry write rights?
     *
     * <p>{@code readOnly} is an explicit choice an administrator makes when assigning a role, so a
     * person can legitimately hold rows that grant them nothing but the ability to look. Counting
     * those as staff would let a read-only account delete pictures.
     *
     * <p>The filter is applied here rather than in the query on purpose: in DQL a
     * {@code readOnly != true} condition also discards rows where the column is NULL, and NULL is
     * how an ordinary read-write grant is stored — so the database-side version of this test would
     * silently deny the very people it should admit.
     */
    private static boolean hasWritableRole(EntityList<?> roles) {
        if (roles == null)
            return false;
        for (Object row : roles) {
            if (row instanceof dev.webfx.stack.orm.entity.Entity entity
                    && !Boolean.TRUE.equals(entity.getBooleanFieldValue("readOnly")))
                return true;
        }
        return false;
    }

    /**
     * Does this person hold a back-office role that permits writing, or super-admin rights?
     *
     * <p>Two cheap indexed look-ups, cached briefly so a back-office user clicking through a
     * gallery does not re-run them on every thumbnail. Only positive answers are cached: a
     * revoked role should take effect on the next request, not two minutes later.
     *
     * <p>Scope caveat, deliberate and recorded: this answers "may this person write in the back
     * office at all", not "may they write for THIS organisation or event". A role at one centre
     * therefore admits picture writes for another centre's events. That is the same breadth the
     * rest of the server's write paths currently enforce, and narrowing it needs the caller's
     * organisation threaded through to the per-key checks — the documented follow-up.
     */
    private static Future<Boolean> isStaff(Object personId) {
        if (personId == null)
            return Future.succeededFuture(false);
        Long cachedUntil = STAFF_CACHE.get(personId);
        long now = System.currentTimeMillis();
        if (cachedUntil != null && cachedUntil > now)
            return Future.succeededFuture(true);
        if (cachedUntil != null)
            STAFF_CACHE.remove(personId);
        return EntityStore.create().executeQueryBatch(
                new dev.webfx.stack.orm.entity.EntityStoreQuery(
                    "select id, readOnly from AuthorizationOrganizationUserAccess where user=$1", new Object[]{personId}),
                new dev.webfx.stack.orm.entity.EntityStoreQuery(
                    "select id from AuthorizationSuperAdmin where superAdmin=$1 limit 1", new Object[]{personId}))
            .map(results -> {
                EntityList<?> roles = results[0];
                EntityList<?> superAdmin = results[1];
                boolean staff = hasWritableRole(roles) || (superAdmin != null && !superAdmin.isEmpty());
                if (staff)
                    STAFF_CACHE.put(personId, System.currentTimeMillis() + STAFF_CACHE_TTL_MILLIS);
                return staff;
            })
            .recover(error -> {
                // A database hiccup must not silently promote somebody to staff.
                Console.log("[IMAGES] staff check failed for person " + personId + ": " + error.getMessage());
                return Future.succeededFuture(false);
            });
    }
}

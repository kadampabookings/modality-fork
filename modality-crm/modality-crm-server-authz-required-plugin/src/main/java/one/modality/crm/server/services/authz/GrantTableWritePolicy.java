package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    static final String PLATFORM_ONLY = "Only a platform super admin can change this";
    static final String OUTSIDE_SCOPE = "A super admin of an organization can change only that organization's access";
    static final String EXPLICIT_ROLE = "Only a platform super admin can assign or remove this role";
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
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return SuperAdminMembership.scopeOf(callerPersonId(userId), entityStore)
            .compose(scope -> scope.platform() ? Future.succeededFuture((String) null)
                : !scope.isSuperAdmin() ? Future.succeededFuture(REFUSED)
                : scopedRefusal(write, scope, DATABASE_LOOKUP))
            .recover(e -> Future.succeededFuture(REFUSED));
    }

    // ── A super administrator scoped to organizations (V0167) ──────────────────────────────────────────────────────

    /** An access row's stored owner and role: ids, any of which may be null. */
    record AccessRow(Object organizationId, Object eventId, Object eventTypeId, Object roleId) {}

    /** The reads the scoped rule needs. An interface so the check can answer them without a database. */
    interface AccessRowLookup {
        /** The stored access row, or a future of null when there is none. */
        Future<AccessRow> storedRow(Object id);
        /** The organization an event belongs to, or a future of null when there is no such event. */
        Future<Object> eventOrganization(Object eventId);
        /** Whether this event type exists, and the organization it belongs to (null for a type shared by all). */
        Future<java.util.Optional<Object>> eventTypeOrganization(Object eventTypeId);
        /** Whether the role grants an explicit-only operation (V0166), directly or through an operation group. */
        Future<Boolean> roleGrantsExplicitOnly(Object roleId);
    }

    /**
     * What a SCOPED super administrator may write: access rows of their own organizations, and nothing else.
     *
     * <ul>
     *   <li>Only {@code AuthorizationOrganizationUserAccess}. Roles, operations and the admin tables belong to no
     *       organization (a role is shared by all), so they are a platform super administrator's.</li>
     *   <li>Every organization the row is in, before and after the write, is one they are super administrator of.</li>
     *   <li>An event on the row, before and after, belongs to that row's organization: an access row naming another
     *       organization's event would reach that event's data through the read scope. An event type is either shared
     *       by all organizations or the row's own.</li>
     *   <li>The role, before and after, grants no explicit-only operation: assigning (or removing) a Data Protection
     *       Officer is a platform super administrator's.</li>
     *   <li>Every value is a plain id. A same-batch reference or an expression cannot be checked, so it is refused —
     *       as is a write with no bound on its rows.</li>
     * </ul>
     *
     * Returns null to allow, or the refusal. Package-private and lookup-driven so a check can run it without a database.
     */
    static Future<String> scopedRefusal(ProtectedEntityWriteRegistry.WriteRequest write, SuperAdminMembership.Scope scope,
                                        AccessRowLookup lookup) {
        if (!"AuthorizationOrganizationUserAccess".equals(write.entityName()))
            return Future.succeededFuture(PLATFORM_ONLY);
        if (write.unbounded())
            return Future.succeededFuture(OUTSIDE_SCOPE);
        java.util.Map<String, Object> written = write.writtenValues() == null ? java.util.Map.of() : write.writtenValues();
        for (String field : new String[] { "organization", "event", "eventType", "role" })
            if (written.containsKey(field) && written.get(field) != null && plainId(written.get(field)) == null)
                return Future.succeededFuture(OUTSIDE_SCOPE);
        boolean insert = write.verb() == ProtectedEntityWriteRegistry.WriteVerb.INSERT;
        Object targetKey = insert ? null : plainId(write.targetId());
        if (!insert && targetKey == null)
            return Future.succeededFuture(OUTSIDE_SCOPE);
        Future<AccessRow> storedFuture = insert ? Future.succeededFuture(null) : lookup.storedRow(targetKey);
        return storedFuture.compose(stored -> {
            if (!insert && stored == null)
                return Future.succeededFuture(OUTSIDE_SCOPE); // no such row: nothing of theirs to change
            Object organization = written.containsKey("organization") ? plainId(written.get("organization")) : stored == null ? null : stored.organizationId();
            Object event = written.containsKey("event") ? plainId(written.get("event")) : stored == null ? null : stored.eventId();
            Object eventType = written.containsKey("eventType") ? plainId(written.get("eventType")) : stored == null ? null : stored.eventTypeId();
            Object role = written.containsKey("role") ? plainId(written.get("role")) : stored == null ? null : stored.roleId();
            // The organizations, before and after
            if (organization == null || !scope.covers(organization)
                || stored != null && !scope.covers(stored.organizationId()))
                return Future.succeededFuture(OUTSIDE_SCOPE);
            // The events, before and after: each in the row's own organization
            List<Future<Boolean>> checks = new ArrayList<>();
            if (event != null)
                checks.add(lookup.eventOrganization(event).map(owner -> sameId(owner, organization)));
            if (stored != null && stored.eventId() != null)
                checks.add(lookup.eventOrganization(stored.eventId()).map(owner -> sameId(owner, stored.organizationId())));
            if (eventType != null)
                checks.add(lookup.eventTypeOrganization(eventType).map(owner -> owner != null && (owner.isEmpty() || sameId(owner.get(), organization))));
            if (stored != null && stored.eventTypeId() != null)
                checks.add(lookup.eventTypeOrganization(stored.eventTypeId()).map(owner -> owner != null && (owner.isEmpty() || sameId(owner.get(), stored.organizationId()))));
            Future<Boolean> eventsInPlace = Future.all(new ArrayList<>(checks)).map(all -> {
                for (int i = 0; i < checks.size(); i++)
                    if (!Boolean.TRUE.equals(all.resultAt(i)))
                        return false;
                return true;
            });
            // The roles, before and after
            List<Future<Boolean>> roleChecks = new ArrayList<>();
            if (role != null)
                roleChecks.add(lookup.roleGrantsExplicitOnly(role));
            if (stored != null && stored.roleId() != null && !sameId(stored.roleId(), role))
                roleChecks.add(lookup.roleGrantsExplicitOnly(stored.roleId()));
            Future<Boolean> explicitRole = Future.all(new ArrayList<>(roleChecks)).map(all -> {
                for (int i = 0; i < roleChecks.size(); i++)
                    if (!Boolean.FALSE.equals(all.resultAt(i)))
                        return true; // grants one, or could not be told: refuse
                return false;
            });
            return Future.all(eventsInPlace, explicitRole).map(both ->
                !Boolean.TRUE.equals(both.resultAt(0)) ? OUTSIDE_SCOPE
                : !Boolean.FALSE.equals(both.resultAt(1)) ? EXPLICIT_ROLE
                : null);
        });
    }

    /** A plain id (a number, or a numeric string), normalised; null for anything else — a same-batch reference, an expression. */
    static Object plainId(Object value) {
        Object key = Entities.getPrimaryKey(value);
        if (key instanceof String text)
            key = ProtectedEntityWritesJob.primaryKeyOf(text);
        return key instanceof Number number ? Numbers.toInteger(number) : null;
    }

    private static boolean sameId(Object a, Object b) {
        Object x = plainId(a), y = plainId(b);
        return x != null && Objects.equals(x, y);
    }

    /** The production lookup: reads by primary key, made as the server. */
    static final AccessRowLookup DATABASE_LOOKUP = new AccessRowLookup() {
        @Override
        public Future<AccessRow> storedRow(Object id) {
            return ProtectedEntityWritesJob.asServer(() -> store()
                .<Entity>executeQuery("select organization,event,eventType,role from AuthorizationOrganizationUserAccess where id=$1", id)
                .map(rows -> rows.isEmpty() ? null : new AccessRow(
                    Entities.getPrimaryKey(rows.get(0).getForeignEntityId("organization")),
                    Entities.getPrimaryKey(rows.get(0).getForeignEntityId("event")),
                    Entities.getPrimaryKey(rows.get(0).getForeignEntityId("eventType")),
                    Entities.getPrimaryKey(rows.get(0).getForeignEntityId("role")))));
        }

        @Override
        public Future<Object> eventOrganization(Object eventId) {
            return ProtectedEntityWritesJob.asServer(() -> ProtectedEntityWritesJob.DATABASE_LOOKUP.eventOrganization(eventId));
        }

        @Override
        public Future<java.util.Optional<Object>> eventTypeOrganization(Object eventTypeId) {
            return ProtectedEntityWritesJob.asServer(() -> store()
                .<Entity>executeQuery("select organization from EventType where id=$1", eventTypeId)
                .map(rows -> rows.isEmpty() ? null
                    : java.util.Optional.ofNullable(Entities.getPrimaryKey(rows.get(0).getForeignEntityId("organization")))));
        }

        @Override
        public Future<Boolean> roleGrantsExplicitOnly(Object roleId) {
            return ProtectedEntityWritesJob.asServer(() -> store()
                .executeQuery("select id from AuthorizationRoleOperation where role=$1 and (operation in (select id from Operation where explicitOnly)"
                              + " or operationGroup in (select o.group from Operation o where o.explicitOnly)) limit 1", roleId)
                .map(rows -> !rows.isEmpty()));
        }

        private EntityStore store() {
            return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        }
    };
}

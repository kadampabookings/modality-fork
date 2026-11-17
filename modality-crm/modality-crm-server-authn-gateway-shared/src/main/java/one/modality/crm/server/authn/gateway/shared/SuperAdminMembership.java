package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.AuthorizationSuperAdmin;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Answers "is the caller a super administrator — and of what?" for the checks that gate on it.
 *
 * <p>Membership is the only key — it is NOT an operation code, so nothing in the role/operation
 * grant tables can delegate it (the same stance {@code ModalityMagicLinkAuthenticationGateway}
 * takes for back-office views). It is keyed on the PERSON that {@code AuthorizationSuperAdmin.superAdmin}
 * points at, matched by id — the same key {@code ModalityAuthorizationServerServiceProvider} grants
 * the {@code operation:*} wildcard on.
 *
 * <p>It used to be keyed on {@code Person.email}. That made membership something a client could
 * claim: a client can write a person's email (its own, once it has cleared its owner flag), and
 * naming a super admin's address was then enough to pass. An id is minted into the principal by the
 * server at sign-in, and a client cannot change it.
 *
 * <p>Resolved from the PRINCIPAL's person id, never from anything the client sends: the caller
 * proves who they are through the session, and this looks up what that person is.
 *
 * <h3>Two kinds of super administrator (V0167)</h3>
 *
 * <ul>
 *   <li><b>Platform</b> — a row with no organization: global, as every super administrator was until V0167. Alone
 *       holds what belongs to no organization: creating organizations, assigning super administrators and Data
 *       Protection Officers, the system-wide actions.</li>
 *   <li><b>Scoped</b> — rows naming organizations: the super-administrator features, over those organizations only.</li>
 * </ul>
 *
 * <p>There is deliberately no "is a super administrator" question any more. It was the global answer, and every caller
 * that asked it was deciding something about a target: each now says which question it means —
 * {@link #isPlatformSuperAdmin}, {@link #isSuperAdminOf} (this target's organization), {@link #isSuperAdminAnywhere}
 * (a target that belongs to no organization, where a scoped super administrator counts like a role holder in any
 * organization) or the whole {@link #scopeOf}. A lookup that fails, or a caller with no person, is not one: fail
 * closed, no exception.
 *
 * @author Claude Code
 */
public final class SuperAdminMembership {

    private SuperAdminMembership() {
    }

    /**
     * What a person is a super administrator of.
     *
     * @param platform        a row with no organization: global
     * @param organizationIds the organizations their scoped rows name (normalised to Integer)
     */
    public record Scope(boolean platform, Set<Integer> organizationIds) {

        /** Not a super administrator at all. */
        public static final Scope NONE = new Scope(false, Collections.emptySet());

        public Scope {
            organizationIds = Collections.unmodifiableSet(new LinkedHashSet<>(organizationIds));
        }

        /** Any row: platform, or scoped to at least one organization. */
        public boolean isSuperAdmin() {
            return platform || !organizationIds.isEmpty();
        }

        /** A super administrator of this organization: platform, or scoped to it. A null organization is covered by platform only. */
        public boolean covers(Object organizationId) {
            if (platform)
                return true;
            Integer id = toOrganizationId(organizationId);
            return id != null && organizationIds.contains(id);
        }

        /** Every one of these organizations is covered — the empty set too, but only for a platform super administrator. */
        public boolean coversAll(Collection<?> organizationIdsToCover) {
            if (platform)
                return true;
            if (organizationIdsToCover == null || organizationIdsToCover.isEmpty())
                return false;
            for (Object organizationId : organizationIdsToCover)
                if (!covers(organizationId))
                    return false;
            return true;
        }
    }

    /** What the principal's person is a super administrator of. */
    public static Future<Scope> scopeOf(ModalityUserPrincipal principal, DataSourceModel dataSourceModel) {
        return scopeOf(principal == null ? null : principal.getUserPersonId(), EntityStore.create(dataSourceModel));
    }

    /** What this person is a super administrator of, from their rows; {@link Scope#NONE} with no person or no row. */
    public static Future<Scope> scopeOf(Object personId, EntityStore entityStore) {
        if (personId == null)
            return Future.succeededFuture(Scope.NONE);
        return entityStore.<AuthorizationSuperAdmin>executeQuery("select organization from AuthorizationSuperAdmin where superAdmin=$1", personId)
            .map(rows -> {
                boolean platform = false;
                Set<Integer> organizationIds = new LinkedHashSet<>();
                for (AuthorizationSuperAdmin row : rows) {
                    Integer organizationId = toOrganizationId(row.getOrganizationId());
                    if (organizationId == null)
                        platform = true;
                    else
                        organizationIds.add(organizationId);
                }
                return new Scope(platform, organizationIds);
            });
    }

    /** A platform (global) super administrator: the one kind for what belongs to no organization. */
    public static Future<Boolean> isPlatformSuperAdmin(ModalityUserPrincipal principal, DataSourceModel dataSourceModel) {
        return scopeOf(principal, dataSourceModel).map(Scope::platform);
    }

    /** A platform (global) super administrator: the one kind for what belongs to no organization. */
    public static Future<Boolean> isPlatformSuperAdmin(Object personId, EntityStore entityStore) {
        return scopeOf(personId, entityStore).map(Scope::platform);
    }

    /** A super administrator of this organization: platform, or scoped to it. */
    public static Future<Boolean> isSuperAdminOf(Object personId, Object organizationId, EntityStore entityStore) {
        return scopeOf(personId, entityStore).map(scope -> scope.covers(organizationId));
    }

    /**
     * A super administrator of any organization, or platform — for a target that belongs to NO organization, where a
     * role holder passes on a grant held in any organization ({@link RoleOperationMembership#holdsThroughRole},
     * {@link RouteGrantMembership}'s "anywhere"), so a scoped super administrator passes too. Not for anything that
     * acts on a specific organization's rows: use {@link #isSuperAdminOf}.
     */
    public static Future<Boolean> isSuperAdminAnywhere(Object personId, EntityStore entityStore) {
        return scopeOf(personId, entityStore).map(Scope::isSuperAdmin);
    }

    /**
     * Whether this super administrator may act on this member of staff's sign-in or account (open their account in a
     * support or back-office view, and the like).
     *
     * <p>A platform super administrator may act on anybody. A scoped one only on staff whose access lies ENTIRELY
     * within the organizations they are super administrator of — every access row and organization-admin row the
     * target holds — and never on another super administrator. "Entirely", because acting on somebody's sign-in is
     * acting as them: a super administrator of X who could take over a member of staff who also holds access in Y
     * would reach Y through them. A member of staff with no organization at all belongs to no scoped super
     * administrator either.
     */
    public static Future<Boolean> mayActOnStaff(Object agentPersonId, Object targetPersonId, EntityStore entityStore) {
        if (agentPersonId == null || targetPersonId == null)
            return Future.succeededFuture(false);
        return scopeOf(agentPersonId, entityStore).compose(agent -> {
            if (agent.platform())
                return Future.succeededFuture(true);
            if (!agent.isSuperAdmin())
                return Future.succeededFuture(false);
            return Future.all(scopeOf(targetPersonId, entityStore), staffOrganizations(targetPersonId, entityStore),
                    holdsExplicitOnlyRole(targetPersonId, entityStore))
                .map(all -> {
                    Scope target = all.resultAt(0);
                    Set<Integer> staffOrganizations = all.resultAt(1);
                    // Acting on somebody's account opens it WITH their grants, so a scoped super administrator must not
                    // reach a role they could not assign themselves: a Data Protection Officer's, or any role granting
                    // an explicit-only operation (V0166). The platform tier alone assigns those, and alone acts on them.
                    boolean explicitOnlyRole = !Boolean.FALSE.equals(all.resultAt(2));
                    return !target.isSuperAdmin() && !explicitOnlyRole && agent.coversAll(staffOrganizations);
                });
        }).recover(e -> Future.succeededFuture(false));
    }

    /** The organizations a member of staff holds access in: their access rows and organization-admin rows. */
    static Future<Set<Integer>> staffOrganizations(Object personId, EntityStore entityStore) {
        return Future.all(
            entityStore.executeQuery("select organization from AuthorizationOrganizationUserAccess where user=$1", personId),
            entityStore.executeQuery("select organization from AuthorizationOrganizationAdmin where admin=$1", personId)
        ).map(both -> {
            Set<Integer> organizationIds = new LinkedHashSet<>();
            for (int i = 0; i < 2; i++) {
                dev.webfx.stack.orm.entity.EntityList<?> rows = both.resultAt(i);
                for (Object row : rows)
                    organizationIds.add(toOrganizationId(((dev.webfx.stack.orm.entity.Entity) row).getForeignEntityId("organization")));
            }
            organizationIds.remove(null);
            return organizationIds;
        });
    }

    /**
     * Whether one of this person's roles grants an explicit-only operation (V0166), directly or through an operation
     * group. Read-only access rows count too: what matters is what opening their account would show.
     */
    static Future<Boolean> holdsExplicitOnlyRole(Object personId, EntityStore entityStore) {
        return entityStore.executeQuery("select id from AuthorizationRoleOperation"
                + " where role in (select role from AuthorizationOrganizationUserAccess where user=$1)"
                + " and (operation in (select id from Operation where explicitOnly)"
                + " or operationGroup in (select o.group from Operation o where o.explicitOnly)) limit 1", personId)
            .map(rows -> !rows.isEmpty());
    }

    /** An organization id from whatever the caller holds — an entity id, a primary key, a number of any width. */
    static Integer toOrganizationId(Object organizationId) {
        if (organizationId == null)
            return null;
        Object key = Entities.getPrimaryKey(organizationId);
        return Numbers.toInteger(key);
    }
}

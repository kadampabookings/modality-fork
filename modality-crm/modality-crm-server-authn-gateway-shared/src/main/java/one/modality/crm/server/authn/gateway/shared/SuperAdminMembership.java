package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

/**
 * Answers "is the caller a super administrator?" for gateways that gate an operation on it.
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
 * @author Claude Code
 */
public final class SuperAdminMembership {

    private SuperAdminMembership() {
    }

    /**
     * Whether the principal's person is a super administrator. A principal with no person is
     * simply not one — fail closed, no exception.
     */
    public static Future<Boolean> isSuperAdmin(ModalityUserPrincipal principal, DataSourceModel dataSourceModel) {
        return isSuperAdminPerson(principal.getUserPersonId(), EntityStore.create(dataSourceModel));
    }

    /**
     * Whether this person is a super administrator — the one definition, for callers that hold a
     * person id rather than a principal (the magic-link gateway, which re-asks it for the agent
     * behind a view pass at redemption). No id means no: fail closed.
     */
    public static Future<Boolean> isSuperAdminPerson(Object personId, EntityStore entityStore) {
        if (personId == null)
            return Future.succeededFuture(false);
        return entityStore.executeQuery("select AuthorizationSuperAdmin where superAdmin=$1 limit 1", personId)
            .map(superAdmins -> !superAdmins.isEmpty());
    }
}

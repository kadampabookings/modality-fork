package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Person;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

/**
 * Answers "is the caller a super administrator?" for gateways that gate an operation on it.
 *
 * <p>Membership is the only key — it is NOT an operation code, so nothing in the role/operation
 * grant tables can delegate it (the same stance {@code ModalityMagicLinkAuthenticationGateway}
 * takes for back-office views). It is keyed on {@code Person.email}, which is what
 * {@code AuthorizationSuperAdmin.superAdmin.email} records; people routinely have a different
 * account username from their person email, so checking the username instead would silently
 * deny staff the membership they hold.
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
     * Whether the principal's person is a super administrator. A principal whose person cannot
     * be found, or has no email, is simply not one — fail closed, no exception.
     */
    public static Future<Boolean> isSuperAdmin(ModalityUserPrincipal principal, DataSourceModel dataSourceModel) {
        EntityStore entityStore = EntityStore.create(dataSourceModel);
        return entityStore.<Person>executeQuery("select email from Person where id=$1 limit 1", principal.getUserPersonId())
            .map(Collections::first)
            .compose(person -> isSuperAdminEmail(person == null ? null : person.getEmail(), entityStore));
    }

    /**
     * Whether the email is a super administrator's — the one definition, for callers that already
     * hold the email (the magic-link gateway's back-office views). Blank means no: fail closed.
     */
    public static Future<Boolean> isSuperAdminEmail(String email, EntityStore entityStore) {
        if (email == null || email.isBlank())
            return Future.succeededFuture(false);
        return entityStore.executeQuery("select AuthorizationSuperAdmin where superAdmin.email=$1 limit 1", email)
            .map(superAdmins -> !superAdmins.isEmpty());
    }
}

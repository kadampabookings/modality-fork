package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;

/**
 * @author Bruno Salmon
 */
public interface AuthorizationSuperAdmin extends Entity {

    // Field name constants
    String superAdmin = "superAdmin";

    // Super Admin (required)

    default void setSuperAdmin(Object value) {
        setForeignField(superAdmin, value);
    }

    default EntityId getSuperAdminId() {
        return getForeignEntityId(superAdmin);
    }

    default Person getSuperAdmin() {
        return getForeignEntity(superAdmin);
    }

    // Organization (optional, V0167): the organization this super admin is scoped to; null = platform (global)

    String organization = "organization";

    default void setOrganization(Object value) {
        setForeignField(organization, value);
    }

    default EntityId getOrganizationId() {
        return getForeignEntityId(organization);
    }

    default Organization getOrganization() {
        return getForeignEntity(organization);
    }
}

package one.modality.base.client.activity.organizationdependent;

import dev.webfx.stack.orm.domainmodel.activity.domain.DomainActivityContext;
import dev.webfx.stack.orm.domainmodel.activity.domain.DomainActivityContextMixin;
import dev.webfx.stack.routing.uirouter.activity.uiroute.UiRouteActivityContext;
import dev.webfx.stack.routing.uirouter.activity.uiroute.UiRouteActivityContextMixin;
import one.modality.crm.backoffice.organization.fx.FXOrganizationId;

public interface OrganizationDependentActivityMixin
        <C extends DomainActivityContext<C> & UiRouteActivityContext<C>>

        extends UiRouteActivityContextMixin<C>,
        DomainActivityContextMixin<C>,
        OrganizationDependentPresentationModelMixin {

    default void updateOrganizationDependentPresentationModelFromContextParameters() {
        Object organizationId = getParameter("organizationId");
        if (isUsableId(organizationId))
            setOrganizationId(organizationId);
        else
            organizationIdProperty().bind(FXOrganizationId.organizationIdProperty());
    }

    /**
     * Whether a route parameter is a usable entity id — a number or a non-blank string. Guards against
     * a malformed/absent optional route segment (or a round-tripped activity state) arriving as a
     * Boolean such as {@code false}: passed on to a reactive query as {@code organization=$1} /
     * {@code event=$1}, it fails at the database ("Boolean cannot be coerced to Number") on every push
     * re-fire. A non-usable value falls back to the FX organization / event instead of poisoning the query.
     */
    static boolean isUsableId(Object id) {
        return id instanceof Number || id instanceof String && !((String) id).trim().isEmpty();
    }
}

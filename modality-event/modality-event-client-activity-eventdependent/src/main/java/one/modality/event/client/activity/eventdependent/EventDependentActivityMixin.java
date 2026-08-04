package one.modality.event.client.activity.eventdependent;

import dev.webfx.stack.orm.domainmodel.activity.domain.DomainActivityContext;
import dev.webfx.stack.routing.uirouter.activity.uiroute.UiRouteActivityContext;
import one.modality.base.client.activity.organizationdependent.OrganizationDependentActivityMixin;
import one.modality.event.client.event.fx.FXEventId;

/**
 * @author Bruno Salmon
 */
public interface EventDependentActivityMixin
        <C extends DomainActivityContext<C> & UiRouteActivityContext<C>>

        extends OrganizationDependentActivityMixin<C>,
        EventDependentPresentationModelMixin
{

    default void updateEventDependentPresentationModelFromContextParameters() {
        Object eventId = getParameter("eventId");
        // Same guard as the organization id: a malformed/absent optional route segment can arrive as a
        // Boolean, which would fail the reactive query's event=$1 bind on every push re-fire.
        if (OrganizationDependentActivityMixin.isUsableId(eventId))
            setEventId(eventId);
        else
            eventIdProperty().bind(FXEventId.eventIdProperty());
        updateOrganizationDependentPresentationModelFromContextParameters();
    }

}

package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.VolunteeringTimeline;

public final class VolunteeringTimelineImpl extends DynamicEntity implements VolunteeringTimeline {

    public VolunteeringTimelineImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<VolunteeringTimeline> {
        public ProvidedFactory() {
            super(VolunteeringTimeline.class, VolunteeringTimelineImpl::new);
        }
    }
}

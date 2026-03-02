package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.VolunteeringApplication;

public final class VolunteeringApplicationImpl extends DynamicEntity implements VolunteeringApplication {

    public VolunteeringApplicationImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<VolunteeringApplication> {
        public ProvidedFactory() {
            super(VolunteeringApplication.class, VolunteeringApplicationImpl::new);
        }
    }
}

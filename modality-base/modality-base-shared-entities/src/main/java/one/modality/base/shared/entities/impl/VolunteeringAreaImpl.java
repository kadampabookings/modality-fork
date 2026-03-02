package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.VolunteeringArea;

public final class VolunteeringAreaImpl extends DynamicEntity implements VolunteeringArea {

    public VolunteeringAreaImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<VolunteeringArea> {
        public ProvidedFactory() {
            super(VolunteeringArea.class, VolunteeringAreaImpl::new);
        }
    }
}

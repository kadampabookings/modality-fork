package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.VolunteeringAssignment;

public final class VolunteeringAssignmentImpl extends DynamicEntity implements VolunteeringAssignment {

    public VolunteeringAssignmentImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<VolunteeringAssignment> {
        public ProvidedFactory() {
            super(VolunteeringAssignment.class, VolunteeringAssignmentImpl::new);
        }
    }
}

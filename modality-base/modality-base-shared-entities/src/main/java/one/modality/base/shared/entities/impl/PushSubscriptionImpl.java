package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.PushSubscription;

/**
 * @author Bruno Salmon
 */
public final class PushSubscriptionImpl extends DynamicEntity implements PushSubscription {

    public PushSubscriptionImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<PushSubscription> {
        public ProvidedFactory() {
            super(PushSubscription.class, PushSubscriptionImpl::new);
        }
    }
}

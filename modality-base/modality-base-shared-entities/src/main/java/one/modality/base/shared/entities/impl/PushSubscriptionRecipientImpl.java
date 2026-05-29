package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.PushSubscriptionRecipient;

/**
 * @author Bruno Salmon
 */
public final class PushSubscriptionRecipientImpl extends DynamicEntity implements PushSubscriptionRecipient {

    public PushSubscriptionRecipientImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<PushSubscriptionRecipient> {
        public ProvidedFactory() {
            super(PushSubscriptionRecipient.class, PushSubscriptionRecipientImpl::new);
        }
    }
}

package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.SoldOutItem;

/**
 * @author Bruno Salmon
 */
public final class SoldOutItemImpl extends DynamicEntity implements SoldOutItem {

    public SoldOutItemImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<SoldOutItem> {
        public ProvidedFactory() {
            super(SoldOutItem.class, SoldOutItemImpl::new);
        }
    }
}

package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.DevIssue;

/**
 * @author Bruno Salmon
 */
public final class DevIssueImpl extends DynamicEntity implements DevIssue {

    public DevIssueImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<DevIssue> {
        public ProvidedFactory() {
            super(DevIssue.class, DevIssueImpl::new);
        }
    }
}

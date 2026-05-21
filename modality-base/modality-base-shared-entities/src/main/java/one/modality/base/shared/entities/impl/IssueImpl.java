package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.Issue;

/**
 * @author Bruno Salmon
 */
public final class IssueImpl extends DynamicEntity implements Issue {

    public IssueImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<Issue> {
        public ProvidedFactory() {
            super(Issue.class, IssueImpl::new);
        }
    }
}

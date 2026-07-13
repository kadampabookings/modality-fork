package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.Conversation;

/**
 * @author Bruno Salmon
 */
public final class ConversationImpl extends DynamicEntity implements Conversation {

    public ConversationImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<Conversation> {
        public ProvidedFactory() {
            super(Conversation.class, ConversationImpl::new);
        }
    }
}

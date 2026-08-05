package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.ChatMessageReaction;

/**
 * @author Bruno Salmon
 */
public final class ChatMessageReactionImpl extends DynamicEntity implements ChatMessageReaction {

    public ChatMessageReactionImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<ChatMessageReaction> {
        public ProvidedFactory() {
            super(ChatMessageReaction.class, ChatMessageReactionImpl::new);
        }
    }
}

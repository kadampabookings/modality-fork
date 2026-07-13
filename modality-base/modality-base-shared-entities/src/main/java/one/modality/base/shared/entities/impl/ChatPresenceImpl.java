package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.ChatPresence;

/**
 * @author Bruno Salmon
 */
public final class ChatPresenceImpl extends DynamicEntity implements ChatPresence {

    public ChatPresenceImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<ChatPresence> {
        public ProvidedFactory() {
            super(ChatPresence.class, ChatPresenceImpl::new);
        }
    }
}

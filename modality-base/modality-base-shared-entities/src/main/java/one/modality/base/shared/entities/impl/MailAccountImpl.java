package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.MailAccount;

/**
 * @author Bruno Salmon
 */
public final class MailAccountImpl extends DynamicEntity implements MailAccount {

    public MailAccountImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<MailAccount> {
        public ProvidedFactory() {
            super(MailAccount.class, MailAccountImpl::new);
        }
    }
}

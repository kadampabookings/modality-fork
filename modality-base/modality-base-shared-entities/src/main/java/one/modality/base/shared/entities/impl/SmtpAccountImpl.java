package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.SmtpAccount;

/**
 * @author Bruno Salmon
 */
public final class SmtpAccountImpl extends DynamicEntity implements SmtpAccount {

    public SmtpAccountImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<SmtpAccount> {
        public ProvidedFactory() {
            super(SmtpAccount.class, SmtpAccountImpl::new);
        }
    }
}

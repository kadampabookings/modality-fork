package one.modality.base.shared.entities.impl;

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.impl.DynamicEntity;
import dev.webfx.stack.orm.entity.impl.EntityFactoryProviderImpl;
import one.modality.base.shared.entities.SupportReport;

/**
 * @author Bruno Salmon
 */
public final class SupportReportImpl extends DynamicEntity implements SupportReport {

    public SupportReportImpl(EntityId id, EntityStore store) {
        super(id, store);
    }

    public static final class ProvidedFactory extends EntityFactoryProviderImpl<SupportReport> {
        public ProvidedFactory() {
            super(SupportReport.class, SupportReportImpl::new);
        }
    }
}

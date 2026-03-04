package one.modality.base.shared.entities.markers;

import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.Currency;

/**
 * @author Bruno Salmon
 */
public interface HasCurrency {

    void setCurrency(Object currency);

    EntityId getCurrencyId();

    Currency getCurrency();

}

package one.modality.base.shared.entities.markers;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.Currency;

/**
 * @author Bruno Salmon
 */
public interface EntityHasCurrency extends Entity, HasCurrency {

    // Replace string literals with static constants
    String currency = "currency";

    @Override
    default void setCurrency(Object value) { // Use consistent parameter naming
        setForeignField(currency, value);
    }

    @Override
    default EntityId getCurrencyId() {
        return getForeignEntityId(currency);
    }

    @Override
    default Currency getCurrency() {
        return getForeignEntity(currency);
    }

}

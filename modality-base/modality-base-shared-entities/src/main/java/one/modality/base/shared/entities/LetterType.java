package one.modality.base.shared.entities;

import one.modality.base.shared.entities.markers.EntityHasName;
import one.modality.base.shared.entities.markers.EntityHasOrd;

/**
 * @author Bruno Salmon
 */
public interface LetterType extends
    EntityHasName,
    EntityHasOrd {

    String confirmation = "confirmation";
    String cancellation = "cancellation";

    default void setConfirmation(Boolean value) {
        setFieldValue(confirmation, value);
    }

    default Boolean isConfirmation() {
        return getBooleanFieldValue(confirmation);
    }

    default void setCancellation(Boolean value) {
        setFieldValue(cancellation, value);
    }

    default Boolean isCancellation() {
        return getBooleanFieldValue(cancellation);
    }
}

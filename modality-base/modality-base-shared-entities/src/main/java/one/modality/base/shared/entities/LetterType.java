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
    String payer = "payer";

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

    // Payer (V0109): letters of this type go to the booking's payer (Document.payer) rather than
    // the attendee, falling back to the attendee when the booking has no payer. The "Payment
    // request" type carries it.
    default void setPayer(Boolean value) {
        setFieldValue(payer, value);
    }

    default Boolean isPayer() {
        return getBooleanFieldValue(payer);
    }
}

package one.modality.base.shared.entities.markers;

import one.modality.base.shared.knownitems.KnownItemFamily;

/**
 * @author Bruno Salmon
 */
public interface HasItemFamilyType {

    KnownItemFamily getKnownItemFamily();

    default boolean isAccommodation() {
        return getKnownItemFamily() == KnownItemFamily.ACCOMMODATION;
    }

    default boolean isMeals() {
        return getKnownItemFamily() == KnownItemFamily.MEALS;
    }

    default boolean isDiet() {
        return getKnownItemFamily() == KnownItemFamily.DIET;
    }

    default boolean isTeaching() {
        return getKnownItemFamily() == KnownItemFamily.TEACHING;
    }

    default boolean isTranslation() {
        return getKnownItemFamily() == KnownItemFamily.TRANSLATION;
    }

    default boolean isTransport() {
        return getKnownItemFamily() == KnownItemFamily.TRANSPORT;
    }

    default boolean isTax() {
        return getKnownItemFamily() == KnownItemFamily.TAX;
    }

}

package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.*;

/**
 * @author Bruno Salmon
 */
public interface ItemPolicy extends Entity,
    EntityHasItem
{

    String scope = "scope";
    String descriptionLabel = "descriptionLabel";
    String noticeLabel = "noticeLabel";
    String minDay = "minDay";
    String _default = "default";
    String earlyAccommodationAllowed = "earlyAccommodationAllowed";
    String lateAccommodationAllowed = "lateAccommodationAllowed";
    String genderInfoRequired = "genderRequired";
    String minOccupancy = "minOccupancy";
    String forceSoldOut = "forceSoldOut";
    String autoBookItem = "autoBookItem";
    String childAllowed = "childAllowed";
    String youngAdultAllowed = "youngAdultAllowed";
    String adultAllowed = "adultAllowed";

    default void setScope(Object value) {
        setForeignField(scope, value);
    }

    default EntityId getScopeId() {
        return getForeignEntityId(scope);
    }

    default PolicyScope getScope() {
        return getForeignEntity(scope);
    }

    default void setDescriptionLabel(Object value) {
        setForeignField(descriptionLabel, value);
    }

    default EntityId getDescriptionLabelId() {
        return getForeignEntityId(descriptionLabel);
    }

    default Label getDescriptionLabel() {
        return getForeignEntity(noticeLabel);
    }

    default void setNoticeLabel(Object value) {
        setForeignField(noticeLabel, value);
    }

    default EntityId getNoticeLabelId() {
        return getForeignEntityId(noticeLabel);
    }

    default Label getNoticeLabel() {
        return getForeignEntity(descriptionLabel);
    }

    default void setMinDay(Integer value) {
        setFieldValue(minDay, value);
    }

    default Integer getMinDay() {
        return getIntegerFieldValue(minDay);
    }

    default void setDefault(Boolean value) {
        setFieldValue(_default, value);
    }

    default Boolean isDefault() {
        return getBooleanFieldValue(_default);
    }

    default void setGenderInfoRequired(Boolean value) {
        setFieldValue(genderInfoRequired, value);
    }

    default Boolean isGenderInfoRequired() {
        return getBooleanFieldValue(genderInfoRequired);
    }

    default void setEarlyAccommodationAllowed(Boolean value) {
        setFieldValue(earlyAccommodationAllowed, value);
    }

    default Boolean isEarlyAccommodationAllowed() {
        return getBooleanFieldValue(earlyAccommodationAllowed);
    }

    default void setLateAccommodationAllowed(Boolean value) {
        setFieldValue(lateAccommodationAllowed, value);
    }

    default Boolean isLateAccommodationAllowed() {
        return getBooleanFieldValue(lateAccommodationAllowed);
    }

    default void setMinOccupancy(Integer value) {
        setFieldValue(minOccupancy, value);
    }

    default Integer getMinOccupancy() {
        return getIntegerFieldValue(minOccupancy);
    }

    default void setForceSoldOut(Boolean value) {
        setFieldValue(forceSoldOut, value);
    }

    default Boolean isSoldOutForced() {
        return getBooleanFieldValue(forceSoldOut);
    }

    default void setAutoBookItem(Object value) {
        setForeignField(autoBookItem, value);
    }

    default EntityId getAutoBookItemId() {
        return getForeignEntityId(autoBookItem);
    }

    /**
     * Returns the item that is automatically booked whenever this policy's item is booked (ex: a
     * pre-erected tent or campervan-sharing option auto-books a Camping pitch). The booking form
     * adds it on the same nights; pricing then treats both lines normally. Note the direction: this
     * is "booking me also books X", not the precondition sense of {@link Rate#getWithItem()}.
     */
    default Item getAutoBookItem() {
        return getForeignEntity(autoBookItem);
    }

    // Age eligibility — who may book this policy's item. All default true (null = allowed). The
    // booking form maps the booker's age to a category (child 0-15, young adult 16-17, adult 18+)
    // and hides options whose flag for that category is false.

    default void setChildAllowed(Boolean value) {
        setFieldValue(childAllowed, value);
    }

    default Boolean isChildAllowed() {
        return getBooleanFieldValue(childAllowed);
    }

    default void setYoungAdultAllowed(Boolean value) {
        setFieldValue(youngAdultAllowed, value);
    }

    default Boolean isYoungAdultAllowed() {
        return getBooleanFieldValue(youngAdultAllowed);
    }

    default void setAdultAllowed(Boolean value) {
        setFieldValue(adultAllowed, value);
    }

    default Boolean isAdultAllowed() {
        return getBooleanFieldValue(adultAllowed);
    }

}

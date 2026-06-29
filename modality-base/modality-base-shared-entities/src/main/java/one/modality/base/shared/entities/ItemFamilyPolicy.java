package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasItemFamily;

import java.util.List;

/**
 * @author Bruno Salmon
 */
public interface ItemFamilyPolicy extends Entity,
    EntityHasItemFamily
{

    String scope = "scope";
    String eventPhaseCoverage1 = "eventPhaseCoverage1";
    String eventPhaseCoverage2 = "eventPhaseCoverage2";
    String eventPhaseCoverage3 = "eventPhaseCoverage3";
    String eventPhaseCoverage4 = "eventPhaseCoverage4";
    String noticeLabel = "noticeLabel";
    String prerequisiteDescriptionLabel = "prerequisiteDescriptionLabel";
    String prerequisiteConfirmationLabel = "prerequisiteConfirmationLabel";
    String includedByDefault = "includedByDefault";
    String askDietForBreakfast = "askDietForBreakfast";
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

    default void setEventPhaseCoverage1(Object value) {
        setFieldValue(eventPhaseCoverage1, value);
    }

    default EntityId getEventPhaseCoverage1Id() {
        return getForeignEntityId(eventPhaseCoverage1);
    }

    default EventPhaseCoverage getEventPhaseCoverage1() {
        return getForeignEntity(eventPhaseCoverage1);
    }

    default void setEventPhaseCoverage2(Object value) {
        setFieldValue(eventPhaseCoverage2, value);
    }

    default EntityId getEventPhaseCoverage2Id() {
        return getForeignEntityId(eventPhaseCoverage2);
    }

    default EventPhaseCoverage getEventPhaseCoverage2() {
        return getForeignEntity(eventPhaseCoverage2);
    }

    default void setEventPhaseCoverage3(Object value) {
        setFieldValue(eventPhaseCoverage3, value);
    }

    default EntityId getEventPhaseCoverage3Id() {
        return getForeignEntityId(eventPhaseCoverage3);
    }

    default EventPhaseCoverage getEventPhaseCoverage3() {
        return getForeignEntity(eventPhaseCoverage3);
    }

    default void setEventPhaseCoverage4(Object value) {
        setFieldValue(eventPhaseCoverage4, value);
    }

    default EntityId getEventPhaseCoverage4Id() {
        return getForeignEntityId(eventPhaseCoverage4);
    }

    default EventPhaseCoverage getEventPhaseCoverage4() {
        return getForeignEntity(eventPhaseCoverage4);
    }

    List<EventPhaseCoverage> getEventPhaseCoverages(); // implemented in ItemFamilyPolicyImpl

    void setEventPhaseCoverages(List<EventPhaseCoverage> phaseCoverages); // implemented in ItemFamilyPolicyImpl

    default void setNoticeLabel(Object value) {
        setForeignField(noticeLabel, value);
    }

    default EntityId getNoticeLabelId() {
        return getForeignEntityId(noticeLabel);
    }

    default Label getNoticeLabel() {
        return getForeignEntity(noticeLabel);
    }

    default boolean hasPrerequisite() {
        return getPrerequisiteDescriptionLabelId() != null || getPrerequisiteConfirmationLabelId() != null;
    }

    default void setPrerequisiteDescriptionLabel(Object value) {
        setForeignField(prerequisiteDescriptionLabel, value);
    }

    default EntityId getPrerequisiteDescriptionLabelId() {
        return getForeignEntityId(prerequisiteDescriptionLabel);
    }

    default Label getPrerequisiteDescriptionLabel() {
        return getForeignEntity(prerequisiteDescriptionLabel);
    }

    default void setPrerequisiteConfirmationLabel(Object value) {
        setForeignField(prerequisiteConfirmationLabel, value);
    }

    default EntityId getPrerequisiteConfirmationLabelId() {
        return getForeignEntityId(prerequisiteConfirmationLabel);
    }

    default Label getPrerequisiteConfirmationLabel() {
        return getForeignEntity(prerequisiteConfirmationLabel);
    }

    default void setIncludedByDefault(Boolean value) {
        setFieldValue(includedByDefault, value);
    }

    default Boolean isIncludedByDefault() {
        return getBooleanFieldValue(includedByDefault);
    }

    // Whether the dietary preference question is asked when only breakfast is booked. Nullable: an
    // explicit value is an admin override; null means the booking app applies its default (ask diet
    // for any booked meal, breakfast included). Set to false on the DIET ItemFamilyPolicy for
    // organisations that only need diet info for lunch and dinner — a breakfast-only booking then
    // skips the dietary question.

    default void setAskDietForBreakfast(Boolean value) {
        setFieldValue(askDietForBreakfast, value);
    }

    default Boolean isAskDietForBreakfast() {
        return getBooleanFieldValue(askDietForBreakfast);
    }

    // Age eligibility — who may book this family's options. Nullable: an explicit value is an admin
    // override; null means the booking app applies a hardcoded per-family default (e.g. only adults
    // book Parking). The app maps the booker's age to a category (child 0-15, young adult 16-17,
    // adult 18+) and hides this option family when the resolved value for that category is false.

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

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
    String disabled = "disabled";
    String replacesWiderScopes = "replacesWiderScopes";
    String displayTimes = "displayTimes";
    String minDay = "minDay";
    String wholeEvent = "wholeEvent";
    String earlyAccommodationAllowed = "earlyAccommodationAllowed";
    String lateAccommodationAllowed = "lateAccommodationAllowed";

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

    // Cross-scope resolution (see PolicyAggregate.resolveScopes). Policies are declared at a general
    // scope (organization, optionally narrowed to a site), an eventType scope or an event scope, and
    // the server returns every matching scope's rows unioned together. These two flags, read on the
    // winning (narrowest) policy for the family, say what that narrower scope means.

    // Withdraws the whole family for the event: its item policies are dropped and no ItemPolicy flag
    // can reintroduce them. This is the "hide the discovery options on advanced retreats" lever —
    // distinct from applicableToInPerson/applicableToOnline, which say the option doesn't suit an
    // attendance mode rather than that it isn't offered here at all.

    default void setDisabled(Boolean value) {
        setFieldValue(disabled, value);
    }

    default Boolean isDisabled() {
        return getBooleanFieldValue(disabled);
    }

    // Whether this scope's ItemPolicy set for the family replaces the set declared at wider scopes,
    // instead of adding to it. False (the default, and the historical behaviour) means the sets
    // merge; per-item attributes are overridden by the narrowest scope either way. The flag lives on
    // the family rather than the item because "which accommodation types do I offer" is a question
    // about a set, and no per-item row can express that the wider set should stop applying.

    default void setReplacesWiderScopes(Boolean value) {
        setFieldValue(replacesWiderScopes, value);
    }

    default Boolean isReplacesWiderScopes() {
        return getBooleanFieldValue(replacesWiderScopes);
    }

    // Whether the booking form shows this family's session times (meal serving times, etc). Nullable:
    // null means unset ⇒ show, so only an explicit false hides them. Set false on the MEALS policy of
    // an event hosted at another centre's venue, where the meals — which belong to the venue, not the
    // event — carry the host's normal serving times rather than the event's own.

    default void setDisplayTimes(Boolean value) {
        setFieldValue(displayTimes, value);
    }

    default Boolean isDisplayTimes() {
        return getBooleanFieldValue(displayTimes);
    }

    // Minimum nights INSIDE THE MAIN EVENT PERIOD for this family's items — the same measure as
    // ItemPolicy.minDay, which overrides it. Nullable: null means unset, so an item with no value of
    // its own asks the family, and a family that says nothing imposes no minimum.
    // Careful: the main event period is not the event's own dates. getEventDateRange EXTENDS it to
    // cover every scheduled teaching, so on an extendable retreat (event 1933 runs 02/01-31/01 but
    // teaches to 28/02) a minimum of 29 is satisfied by any 29 nights inside a 57-night span. Use
    // wholeEvent when the stay must actually cover the event.

    default void setMinDay(Integer value) {
        setFieldValue(minDay, value);
    }

    default Integer getMinDay() {
        return getIntegerFieldValue(minDay);
    }

    // Whether a booking must cover the EVENT'S OWN start..end dates, extra nights either side
    // allowed. Distinct from minDay, and measured against a different window (see above) — on an
    // extendable event minDay cannot express this at all. It is also the value that cannot go stale:
    // a night count is a copy of a derived fact, so moving the event silently invalidates it.
    // Nullable: null means unset. The two constraints are independent and both apply when both
    // resolve, so an item opting out of its family's wholeEvent must set it false explicitly.

    default void setWholeEvent(Boolean value) {
        setFieldValue(wholeEvent, value);
    }

    default Boolean isWholeEvent() {
        return getBooleanFieldValue(wholeEvent);
    }

    // Whether this family's items may be booked before the event starts / after it ends. Nullable:
    // null means unset, so resolution asks a wider scope and finally defaults to allowed. Stated on
    // the family, a retreat says "no early arrival" once instead of on every room.

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

}

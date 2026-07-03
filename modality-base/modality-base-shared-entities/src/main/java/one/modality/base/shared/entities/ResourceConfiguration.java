package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.*;

import java.time.LocalDate;

public interface ResourceConfiguration extends Entity,
    EntityHasSiteAndItem,
    EntityHasResource,
    EntityHasOnline,
    EntityHasEvent,
    HasName {

    String name = "name";
    String endDate = "endDate";
    String startDate = "startDate";
    String allowsMale = "allowsMale";
    String allowsFemale = "allowsFemale";
    String allowsGuest = "allowsGuest";
    String allowsSpecialGuest = "allowsSpecialGuest";
    String allowsVolunteer = "allowsVolunteer";
    String allowsResident = "allowsResident";
    String allowsResidentFamily = "allowsResidentFamily";
    String max = "max";
    String maxReserved = "maxReserved";
    String pool = "pool";
    String comment = "comment";

    @Override
    default String getName() {
        return evaluate(name);
    }

    @Override
    default void setName(String value) {
        setExpressionValue(parseExpression(name), value);
    }

    default void setEndDate(LocalDate value) {
        setFieldValue(endDate, value);
    }

    default LocalDate getStartDate() {
        return getLocalDateFieldValue(startDate);
    }

    default void setStartDate(LocalDate value) {
        setFieldValue(startDate, value);
    }

    default LocalDate getEndDate() {
        return getLocalDateFieldValue(endDate);
    }

    default Boolean allowsMale() {
        return getBooleanFieldValue(allowsMale);
    }

    default void setAllowsMale(Boolean value) {
        setFieldValue(allowsMale, value);
    }

    default Boolean allowsFemale() {
        return getBooleanFieldValue(allowsFemale);
    }

    default void setAllowsFemale(Boolean value) {
        setFieldValue(allowsFemale, value);
    }

    default Boolean allowsGuest() {
        return getBooleanFieldValue(allowsGuest);
    }

    default void setAllowsGuest(Boolean value) {
        setFieldValue(allowsGuest, value);
    }

    default Boolean allowsSpecialGuest() {
        return getBooleanFieldValue(allowsSpecialGuest);
    }

    default void setAllowsSpecialGuest(Boolean value) {
        setFieldValue(allowsSpecialGuest, value);
    }

    default Boolean allowsVolunteer() {
        return getBooleanFieldValue(allowsVolunteer);
    }

    default void setAllowsVolunteer(Boolean value) {
        setFieldValue(allowsVolunteer, value);
    }

    default Boolean allowsResident() {
        return getBooleanFieldValue(allowsResident);
    }

    default void setAllowsResident(Boolean value) {
        setFieldValue(allowsResident, value);
    }

    default Boolean allowsResidentFamily() {
        return getBooleanFieldValue(allowsResidentFamily);
    }

    default void setAllowsResidentFamily(Boolean value) {
        setFieldValue(allowsResidentFamily, value);
    }

    default Integer getMax() {
        return getIntegerFieldValue(max);
    }

    default void setMax(int value) {
        setFieldValue(max, value);
    }

    // Reserved beds: withheld from public booking (public beds = max - maxReserved, bookable iff
    // online). Bookings consuming a reserved bed carry documentLine.pool as the partition marker.
    default Integer getMaxReserved() {
        return getIntegerFieldValue(maxReserved);
    }

    default void setMaxReserved(Integer value) {
        setFieldValue(maxReserved, value);
    }

    // Room categorization / reason beds are held (informative; drives pool-targeted allocation for
    // volunteer-style events and the residents room list). May be set with maxReserved null/0 —
    // categorization without a capacity hold.
    default void setPool(Object value) {
        setForeignField(pool, value);
    }

    default EntityId getPoolId() {
        return getForeignEntityId(pool);
    }

    default Pool getPool() {
        return getForeignEntity(pool);
    }

    default String getComment() {
        return getStringFieldValue(comment);
    }

    default void setComment(String value) {
        setFieldValue(comment, value);
    }
}
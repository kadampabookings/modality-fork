package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.*;

/**
 * @author Bruno Salmon
 */
public interface Organization extends
    EntityHasName,
    EntityHasLabel,
    EntityHasIcon,
    EntityHasCountry,
    EntityHasCurrency {

    String closed = "closed";
    String type = "type";
    String kdmCenter = "kdmCenter";
    String latitude = "latitude";
    String longitude = "longitude";
    String importIssue = "importIssue";
    String language = "language";
    String teachingsDayTicketItem = "teachingsDayTicketItem";
    String globalSite = "globalSite";
    String termsUrlLabel = "termsUrlLabel";
    String privacyUrlLabel = "privacyUrlLabel";
    String registrationMailAccount = "registrationMailAccount";
    String timezone = "timezone";
    String metaPixelId = "metaPixelId";
    String includeTeachingsInAccommodationPricesByDefault = "includeTeachingsInAccommodationPricesByDefault";

    default String getMetaPixelId() {
        return getStringFieldValue(metaPixelId);
    }

    default void setIncludeTeachingsInAccommodationPricesByDefault(Boolean value) {
        setFieldValue(includeTeachingsInAccommodationPricesByDefault, value);
    }

    /** Per-centre default for the accommodation pricing-view toggle: when true (the default),
     *  accommodation cards start with teachings included in the price (combined); when false they
     *  start showing the accommodation-only price. The booker can flip the toggle either way, and the
     *  setting applies to all the organisation's events. */
    default Boolean isIncludeTeachingsInAccommodationPricesByDefault() {
        return getBooleanFieldValue(includeTeachingsInAccommodationPricesByDefault);
    }

    default void setMetaPixelId(String value) {
        setFieldValue(metaPixelId, value);
    }

    default void setTimezone(String value) {
        setFieldValue(timezone, value);
    }

    default String getTimezone() {
        return getStringFieldValue(timezone);
    }

    default void setClosed(boolean value) { setFieldValue(closed, value); }

    default void setType(Object value) {
        setForeignField(type, value);
    }

    default EntityId getTypeId() {
        return getForeignEntityId(type);
    }

    default OrganizationType getType() {
        return getForeignEntity(type);
    }

    default void setKdmCenter(Object value) {
        setForeignField(kdmCenter, value);
    }

    default EntityId getKdmCenterId() {
        return getForeignEntityId(kdmCenter);
    }

    default KdmCenter getKdmCenter() {
        return getForeignEntity(kdmCenter);
    }

    default Float getLatitude() {
        return getFloatFieldValue(latitude);
    }

    default void setLatitude(Float value) {
        setFieldValue(latitude, value);
    }

    default Float getLongitude() {
        return getFloatFieldValue(longitude);
    }

    default void setLongitude(Float value) {
        setFieldValue(longitude, value);
    }

    default void setImportIssue(String value) {
        setFieldValue(importIssue, value);
    }

    default String getImportIssue() {
        return getStringFieldValue(importIssue);
    }

    default void setLanguage(Object value) {
        setForeignField(language, value);
    }

    default EntityId getLanguageId() {
        return getForeignEntityId(language);
    }

    default Language getLanguage() {
        return getForeignEntity(language);
    }

    default void setTeachingsDayTicketItem(Object value) {
        setForeignField(teachingsDayTicketItem, value);
    }

    default EntityId getTeachingsDayTicketItemId() {
        return getForeignEntityId(teachingsDayTicketItem);
    }

    default Item getTeachingsDayTicketItem() {
        return getForeignEntity(teachingsDayTicketItem);
    }

    default void setGlobalSite(Object value) {
        setForeignField(globalSite, value);
    }

    default EntityId getGlobalSiteId() {
        return getForeignEntityId(globalSite);
    }

    default Site getGlobalSite() {
        return getForeignEntity(globalSite);
    }

    default void setTermsUrlLabel(Object value) {
        setForeignField(termsUrlLabel, value);
    }

    default EntityId getTermsUrlLabelId() {
        return getForeignEntityId(termsUrlLabel);
    }

    default Label getTermsUrlLabel() {
        return getForeignEntity(termsUrlLabel);
    }

    default void setPrivacyUrlLabel(Object value) {
        setForeignField(privacyUrlLabel, value);
    }

    default EntityId getPrivacyUrlLabelId() {
        return getForeignEntityId(privacyUrlLabel);
    }

    default Label getPrivacyUrlLabel() {
        return getForeignEntity(privacyUrlLabel);
    }

    /** Mail account used as the "from" sender for letters/mails of this organization's events, when the
     *  letter has no associated account and the event type has no registrationMailAccount of its own. */
    default void setRegistrationMailAccount(Object value) {
        setForeignField(registrationMailAccount, value);
    }

    default EntityId getRegistrationMailAccountId() {
        return getForeignEntityId(registrationMailAccount);
    }

    default MailAccount getRegistrationMailAccount() {
        return getForeignEntity(registrationMailAccount);
    }

}
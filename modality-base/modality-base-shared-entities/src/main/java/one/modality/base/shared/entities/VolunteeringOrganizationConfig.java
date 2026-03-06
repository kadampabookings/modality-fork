package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

/**
 * Per-organization volunteer program configuration.
 */
public interface VolunteeringOrganizationConfig extends Entity, EntityHasOrganization {

    String name = "name";
    String description = "description";
    String logoUrl = "logoUrl";
    String primaryColor = "primaryColor";
    String timezone = "timezone";
    String publicApplicationsEnabled = "publicApplicationsEnabled";
    String reminderDaysBeforeArrival = "reminderDaysBeforeArrival";
    String workOnArrivalDay = "workOnArrivalDay";
    String workOnDepartureDay = "workOnDepartureDay";
    String applicationFormIntro = "applicationFormIntro";
    String formConfig = "formConfig";
    String availableSkills = "availableSkills";
    String kbsVolunteerEvent = "kbsVolunteerEvent";
    String kbsSite = "kbsSite";
    String kbsAccommodationItem = "kbsAccommodationItem";
    String kbsBreakfastItem = "kbsBreakfastItem";
    String kbsLunchItem = "kbsLunchItem";
    String kbsDinnerItem = "kbsDinnerItem";
    String kbsDietaryFamilyCode = "kbsDietaryFamilyCode";
    String kbsSmtpMailAccount = "kbsSmtpMailAccount";
    String active = "active";

    // --- Simple fields ---

    default void setName(String value) { setFieldValue(name, value); }
    default String getName() { return getStringFieldValue(name); }

    default void setDescription(String value) { setFieldValue(description, value); }
    default String getDescription() { return getStringFieldValue(description); }

    default void setLogoUrl(String value) { setFieldValue(logoUrl, value); }
    default String getLogoUrl() { return getStringFieldValue(logoUrl); }

    default void setPrimaryColor(String value) { setFieldValue(primaryColor, value); }
    default String getPrimaryColor() { return getStringFieldValue(primaryColor); }

    default void setTimezone(String value) { setFieldValue(timezone, value); }
    default String getTimezone() { return getStringFieldValue(timezone); }

    default void setPublicApplicationsEnabled(Boolean value) { setFieldValue(publicApplicationsEnabled, value); }
    default Boolean isPublicApplicationsEnabled() { return getBooleanFieldValue(publicApplicationsEnabled); }

    default void setReminderDaysBeforeArrival(Integer value) { setFieldValue(reminderDaysBeforeArrival, value); }
    default Integer getReminderDaysBeforeArrival() { return getIntegerFieldValue(reminderDaysBeforeArrival); }

    default void setWorkOnArrivalDay(Boolean value) { setFieldValue(workOnArrivalDay, value); }
    default Boolean isWorkOnArrivalDay() { return getBooleanFieldValue(workOnArrivalDay); }

    default void setWorkOnDepartureDay(Boolean value) { setFieldValue(workOnDepartureDay, value); }
    default Boolean isWorkOnDepartureDay() { return getBooleanFieldValue(workOnDepartureDay); }

    default void setApplicationFormIntro(String value) { setFieldValue(applicationFormIntro, value); }
    default String getApplicationFormIntro() { return getStringFieldValue(applicationFormIntro); }

    default void setFormConfig(String value) { setFieldValue(formConfig, value); }
    default String getFormConfig() { return getStringFieldValue(formConfig); }

    default void setAvailableSkills(String value) { setFieldValue(availableSkills, value); }
    default String getAvailableSkills() { return getStringFieldValue(availableSkills); }

    default void setKbsDietaryFamilyCode(String value) { setFieldValue(kbsDietaryFamilyCode, value); }
    default String getKbsDietaryFamilyCode() { return getStringFieldValue(kbsDietaryFamilyCode); }

    default void setActive(Boolean value) { setFieldValue(active, value); }
    default Boolean isActive() { return getBooleanFieldValue(active); }

    // --- Foreign key fields ---

    default void setKbsVolunteerEvent(Object value) { setForeignField(kbsVolunteerEvent, value); }
    default EntityId getKbsVolunteerEventId() { return getForeignEntityId(kbsVolunteerEvent); }
    default Event getKbsVolunteerEvent() { return getForeignEntity(kbsVolunteerEvent); }

    default void setKbsSite(Object value) { setForeignField(kbsSite, value); }
    default EntityId getKbsSiteId() { return getForeignEntityId(kbsSite); }
    default Site getKbsSite() { return getForeignEntity(kbsSite); }

    default void setKbsAccommodationItem(Object value) { setForeignField(kbsAccommodationItem, value); }
    default EntityId getKbsAccommodationItemId() { return getForeignEntityId(kbsAccommodationItem); }
    default Item getKbsAccommodationItem() { return getForeignEntity(kbsAccommodationItem); }

    default void setKbsBreakfastItem(Object value) { setForeignField(kbsBreakfastItem, value); }
    default EntityId getKbsBreakfastItemId() { return getForeignEntityId(kbsBreakfastItem); }
    default Item getKbsBreakfastItem() { return getForeignEntity(kbsBreakfastItem); }

    default void setKbsLunchItem(Object value) { setForeignField(kbsLunchItem, value); }
    default EntityId getKbsLunchItemId() { return getForeignEntityId(kbsLunchItem); }
    default Item getKbsLunchItem() { return getForeignEntity(kbsLunchItem); }

    default void setKbsDinnerItem(Object value) { setForeignField(kbsDinnerItem, value); }
    default EntityId getKbsDinnerItemId() { return getForeignEntityId(kbsDinnerItem); }
    default Item getKbsDinnerItem() { return getForeignEntity(kbsDinnerItem); }

    default void setKbsSmtpMailAccount(Object value) { setForeignField(kbsSmtpMailAccount, value); }
    default EntityId getKbsSmtpMailAccountId() { return getForeignEntityId(kbsSmtpMailAccount); }
    default MailAccount getKbsSmtpMailAccount() { return getForeignEntity(kbsSmtpMailAccount); }
}

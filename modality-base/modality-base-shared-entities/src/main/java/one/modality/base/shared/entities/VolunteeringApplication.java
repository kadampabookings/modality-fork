package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasDocument;
import one.modality.base.shared.entities.markers.EntityHasOrganization;
import one.modality.base.shared.entities.markers.EntityHasPerson;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Master volunteer application record. Includes visa and reference fields (1:1).
 * Links to KBS3 person, document, and resource tables.
 */
public interface VolunteeringApplication extends Entity,
        EntityHasOrganization,
        EntityHasPerson,
        EntityHasDocument {

    // --- KBS3 entity links ---
    String resource = "resource";
    String country = "country";

    // --- Form metadata ---
    String formType = "formType";

    // --- Personal information ---
    String firstName = "firstName";
    String lastName = "lastName";
    String ordainedName = "ordainedName";
    String email = "email";
    String phone = "phone";
    String countryName = "countryName";
    String gender = "gender";
    String dateOfBirth = "dateOfBirth";
    String age = "age";
    String photo = "photo";

    // --- Dates ---
    String startDate = "startDate";
    String endDate = "endDate";
    String startTime = "startTime";
    String endTime = "endTime";
    String dateFlexibility = "dateFlexibility";
    String earliestStartDate = "earliestStartDate";
    String latestEndDate = "latestEndDate";
    String dateNotes = "dateNotes";
    String datesConfirmed = "datesConfirmed";
    String workOnArrivalDay = "workOnArrivalDay";
    String workOnDepartureDay = "workOnDepartureDay";
    String datesNotes = "datesNotes";
    String datesStatus = "datesStatus";
    String originalStartDate = "originalStartDate";
    String originalEndDate = "originalEndDate";

    // --- Festival ---
    String springFestivalAttending = "springFestivalAttending";
    String summerFestivalAttending = "summerFestivalAttending";

    // --- Skills & preferences ---
    String skills = "skills";
    String englishLevel = "englishLevel";
    String preferredAreas = "preferredAreas";
    String occupation = "occupation";
    String fitnessLevel = "fitnessLevel";

    // --- Dietary ---
    String diet = "diet";
    String kbsDietItem = "kbsDietItem";

    // --- Emergency contact ---
    String emergencyContactName = "emergencyContactName";
    String emergencyContactPhone = "emergencyContactPhone";
    String emergencyContactRelationship = "emergencyContactRelationship";

    // --- Consent ---
    String refereeDataConsent = "refereeDataConsent";
    String emergencyDataConsent = "emergencyDataConsent";
    String privacyNoticeAccepted = "privacyNoticeAccepted";
    String communityLifeAccepted = "communityLifeAccepted";
    String communityLifeDate = "communityLifeDate";
    String termsAccepted = "termsAccepted";
    String termsDate = "termsDate";

    // --- Background ---
    String whyVolunteer = "whyVolunteer";
    String previousExperience = "previousExperience";
    String howHeardAboutUs = "howHeardAboutUs";
    String criminalConvictions = "criminalConvictions";
    String hasCriminalConvictions = "hasCriminalConvictions";
    String healthInfo = "healthInfo";
    String hasMedicalInsurance = "hasMedicalInsurance";
    String additionalInfo = "additionalInfo";
    String previousNktVolunteering = "previousNktVolunteering";
    String previousNktDetails = "previousNktDetails";

    // --- Room ---
    String room = "room";

    // --- Status & workflow ---
    String status = "status";
    String prerequisiteStatus = "prerequisiteStatus";
    String notes = "notes";

    // --- Arrival tracking ---
    String arrivalReminderSentAt = "arrivalReminderSentAt";
    String arrivalConfirmationToken = "arrivalConfirmationToken";
    String arrivalTokenExpiresAt = "arrivalTokenExpiresAt";
    String arrivalTokenUsedAt = "arrivalTokenUsedAt";

    // --- Visa fields (merged) ---
    String visaStatus = "visaStatus";
    String ukPassportHolder = "ukPassportHolder";
    String passportType = "passportType";
    String passportCountry = "passportCountry";
    String immigrationStatus = "immigrationStatus";
    String immigrationStatusOther = "immigrationStatusOther";
    String shareCode = "shareCode";
    String visitReason = "visitReason";
    String visitReasonOther = "visitReasonOther";
    String priorVolunteeredElsewhere = "priorVolunteeredElsewhere";
    String volunteer30dayConfirmed = "volunteer30dayConfirmed";
    String visaType = "visaType";
    String ukEntryDate = "ukEntryDate";
    String priorVolunteerDays = "priorVolunteerDays";
    String visaAppliedDate = "visaAppliedDate";
    String visaApprovedDate = "visaApprovedDate";
    String visaNotes = "visaNotes";

    // --- Reference fields (merged) ---
    String referenceName = "referenceName";
    String referenceEmail = "referenceEmail";
    String referencePhone = "referencePhone";
    String referenceJobTitle = "referenceJobTitle";
    String referenceStatus = "referenceStatus";
    String referenceVerificationDate = "referenceVerificationDate";
    String referenceNotes = "referenceNotes";

    // ==================== FOREIGN KEY ACCESSORS ====================

    default void setResource(Object value) { setForeignField(resource, value); }
    default EntityId getResourceId() { return getForeignEntityId(resource); }
    default Resource getResource() { return getForeignEntity(resource); }

    default void setCountry(Object value) { setForeignField(country, value); }
    default EntityId getCountryId() { return getForeignEntityId(country); }
    default Country getCountry() { return getForeignEntity(country); }

    default void setKbsDietItem(Object value) { setForeignField(kbsDietItem, value); }
    default EntityId getKbsDietItemId() { return getForeignEntityId(kbsDietItem); }
    default Item getKbsDietItem() { return getForeignEntity(kbsDietItem); }

    default void setRoom(Object value) { setForeignField(room, value); }
    default EntityId getRoomId() { return getForeignEntityId(room); }
    default Resource getRoom() { return getForeignEntity(room); }

    // ==================== SIMPLE FIELD ACCESSORS ====================

    // --- Form metadata ---
    default void setFormType(String value) { setFieldValue(formType, value); }
    default String getFormType() { return getStringFieldValue(formType); }

    // --- Personal information ---
    default void setFirstName(String value) { setFieldValue(firstName, value); }
    default String getFirstName() { return getStringFieldValue(firstName); }

    default void setLastName(String value) { setFieldValue(lastName, value); }
    default String getLastName() { return getStringFieldValue(lastName); }

    default void setOrdainedName(String value) { setFieldValue(ordainedName, value); }
    default String getOrdainedName() { return getStringFieldValue(ordainedName); }

    default void setEmail(String value) { setFieldValue(email, value); }
    default String getEmail() { return getStringFieldValue(email); }

    default void setPhone(String value) { setFieldValue(phone, value); }
    default String getPhone() { return getStringFieldValue(phone); }

    default void setCountryName(String value) { setFieldValue(countryName, value); }
    default String getCountryName() { return getStringFieldValue(countryName); }

    default void setGender(String value) { setFieldValue(gender, value); }
    default String getGender() { return getStringFieldValue(gender); }

    default void setDateOfBirth(LocalDate value) { setFieldValue(dateOfBirth, value); }
    default LocalDate getDateOfBirth() { return getLocalDateFieldValue(dateOfBirth); }

    // Declared on the application form since V0072. Null on legacy and KBS2-imported
    // rows, which only carry dateOfBirth — derive the age from it in that case.
    default void setAge(Integer value) { setFieldValue(age, value); }
    default Integer getAge() { return getIntegerFieldValue(age); }

    default void setPhoto(String value) { setFieldValue(photo, value); }
    default String getPhoto() { return getStringFieldValue(photo); }

    // --- Dates ---
    default void setStartDate(LocalDate value) { setFieldValue(startDate, value); }
    default LocalDate getStartDate() { return getLocalDateFieldValue(startDate); }

    default void setEndDate(LocalDate value) { setFieldValue(endDate, value); }
    default LocalDate getEndDate() { return getLocalDateFieldValue(endDate); }

    default void setStartTime(String value) { setFieldValue(startTime, value); }
    default String getStartTime() { return getStringFieldValue(startTime); }

    default void setEndTime(String value) { setFieldValue(endTime, value); }
    default String getEndTime() { return getStringFieldValue(endTime); }

    default void setDateFlexibility(String value) { setFieldValue(dateFlexibility, value); }
    default String getDateFlexibility() { return getStringFieldValue(dateFlexibility); }

    default void setEarliestStartDate(LocalDate value) { setFieldValue(earliestStartDate, value); }
    default LocalDate getEarliestStartDate() { return getLocalDateFieldValue(earliestStartDate); }

    default void setLatestEndDate(LocalDate value) { setFieldValue(latestEndDate, value); }
    default LocalDate getLatestEndDate() { return getLocalDateFieldValue(latestEndDate); }

    default void setDateNotes(String value) { setFieldValue(dateNotes, value); }
    default String getDateNotes() { return getStringFieldValue(dateNotes); }

    default void setDatesConfirmed(Boolean value) { setFieldValue(datesConfirmed, value); }
    default Boolean isDatesConfirmed() { return getBooleanFieldValue(datesConfirmed); }

    default void setWorkOnArrivalDay(Boolean value) { setFieldValue(workOnArrivalDay, value); }
    default Boolean isWorkOnArrivalDay() { return getBooleanFieldValue(workOnArrivalDay); }

    default void setWorkOnDepartureDay(Boolean value) { setFieldValue(workOnDepartureDay, value); }
    default Boolean isWorkOnDepartureDay() { return getBooleanFieldValue(workOnDepartureDay); }

    default void setDatesNotes(String value) { setFieldValue(datesNotes, value); }
    default String getDatesNotes() { return getStringFieldValue(datesNotes); }

    default void setDatesStatus(String value) { setFieldValue(datesStatus, value); }
    default String getDatesStatus() { return getStringFieldValue(datesStatus); }

    default void setOriginalStartDate(LocalDate value) { setFieldValue(originalStartDate, value); }
    default LocalDate getOriginalStartDate() { return getLocalDateFieldValue(originalStartDate); }

    default void setOriginalEndDate(LocalDate value) { setFieldValue(originalEndDate, value); }
    default LocalDate getOriginalEndDate() { return getLocalDateFieldValue(originalEndDate); }

    // --- Festival ---
    default void setSpringFestivalAttending(Boolean value) { setFieldValue(springFestivalAttending, value); }
    default Boolean isSpringFestivalAttending() { return getBooleanFieldValue(springFestivalAttending); }

    default void setSummerFestivalAttending(Boolean value) { setFieldValue(summerFestivalAttending, value); }
    default Boolean isSummerFestivalAttending() { return getBooleanFieldValue(summerFestivalAttending); }

    // --- Skills & preferences ---
    default void setSkills(String value) { setFieldValue(skills, value); }
    default String getSkills() { return getStringFieldValue(skills); }

    default void setEnglishLevel(Integer value) { setFieldValue(englishLevel, value); }
    default Integer getEnglishLevel() { return getIntegerFieldValue(englishLevel); }

    default void setPreferredAreas(String value) { setFieldValue(preferredAreas, value); }
    default String getPreferredAreas() { return getStringFieldValue(preferredAreas); }

    default void setOccupation(String value) { setFieldValue(occupation, value); }
    default String getOccupation() { return getStringFieldValue(occupation); }

    default void setFitnessLevel(String value) { setFieldValue(fitnessLevel, value); }
    default String getFitnessLevel() { return getStringFieldValue(fitnessLevel); }

    // --- Dietary ---
    default void setDiet(String value) { setFieldValue(diet, value); }
    default String getDiet() { return getStringFieldValue(diet); }

    // --- Emergency contact ---
    default void setEmergencyContactName(String value) { setFieldValue(emergencyContactName, value); }
    default String getEmergencyContactName() { return getStringFieldValue(emergencyContactName); }

    default void setEmergencyContactPhone(String value) { setFieldValue(emergencyContactPhone, value); }
    default String getEmergencyContactPhone() { return getStringFieldValue(emergencyContactPhone); }

    default void setEmergencyContactRelationship(String value) { setFieldValue(emergencyContactRelationship, value); }
    default String getEmergencyContactRelationship() { return getStringFieldValue(emergencyContactRelationship); }

    // --- Consent ---
    default void setRefereeDataConsent(Boolean value) { setFieldValue(refereeDataConsent, value); }
    default Boolean isRefereeDataConsent() { return getBooleanFieldValue(refereeDataConsent); }

    default void setEmergencyDataConsent(Boolean value) { setFieldValue(emergencyDataConsent, value); }
    default Boolean isEmergencyDataConsent() { return getBooleanFieldValue(emergencyDataConsent); }

    default void setPrivacyNoticeAccepted(Boolean value) { setFieldValue(privacyNoticeAccepted, value); }
    default Boolean isPrivacyNoticeAccepted() { return getBooleanFieldValue(privacyNoticeAccepted); }

    default void setCommunityLifeAccepted(Boolean value) { setFieldValue(communityLifeAccepted, value); }
    default Boolean isCommunityLifeAccepted() { return getBooleanFieldValue(communityLifeAccepted); }

    default void setCommunityLifeDate(LocalDate value) { setFieldValue(communityLifeDate, value); }
    default LocalDate getCommunityLifeDate() { return getLocalDateFieldValue(communityLifeDate); }

    default void setTermsAccepted(Boolean value) { setFieldValue(termsAccepted, value); }
    default Boolean isTermsAccepted() { return getBooleanFieldValue(termsAccepted); }

    default void setTermsDate(LocalDate value) { setFieldValue(termsDate, value); }
    default LocalDate getTermsDate() { return getLocalDateFieldValue(termsDate); }

    // --- Background ---
    default void setWhyVolunteer(String value) { setFieldValue(whyVolunteer, value); }
    default String getWhyVolunteer() { return getStringFieldValue(whyVolunteer); }

    default void setPreviousExperience(String value) { setFieldValue(previousExperience, value); }
    default String getPreviousExperience() { return getStringFieldValue(previousExperience); }

    default void setHowHeardAboutUs(String value) { setFieldValue(howHeardAboutUs, value); }
    default String getHowHeardAboutUs() { return getStringFieldValue(howHeardAboutUs); }

    default void setCriminalConvictions(String value) { setFieldValue(criminalConvictions, value); }
    default String getCriminalConvictions() { return getStringFieldValue(criminalConvictions); }

    default void setHasCriminalConvictions(Boolean value) { setFieldValue(hasCriminalConvictions, value); }
    default Boolean isHasCriminalConvictions() { return getBooleanFieldValue(hasCriminalConvictions); }

    default void setHealthInfo(String value) { setFieldValue(healthInfo, value); }
    default String getHealthInfo() { return getStringFieldValue(healthInfo); }

    default void setHasMedicalInsurance(Boolean value) { setFieldValue(hasMedicalInsurance, value); }
    default Boolean isHasMedicalInsurance() { return getBooleanFieldValue(hasMedicalInsurance); }

    default void setAdditionalInfo(String value) { setFieldValue(additionalInfo, value); }
    default String getAdditionalInfo() { return getStringFieldValue(additionalInfo); }

    default void setPreviousNktVolunteering(Boolean value) { setFieldValue(previousNktVolunteering, value); }
    default Boolean isPreviousNktVolunteering() { return getBooleanFieldValue(previousNktVolunteering); }

    default void setPreviousNktDetails(String value) { setFieldValue(previousNktDetails, value); }
    default String getPreviousNktDetails() { return getStringFieldValue(previousNktDetails); }

    // --- Status & workflow ---
    default void setStatus(String value) { setFieldValue(status, value); }
    default String getStatus() { return getStringFieldValue(status); }

    default void setPrerequisiteStatus(String value) { setFieldValue(prerequisiteStatus, value); }
    default String getPrerequisiteStatus() { return getStringFieldValue(prerequisiteStatus); }

    default void setNotes(String value) { setFieldValue(notes, value); }
    default String getNotes() { return getStringFieldValue(notes); }

    // --- Arrival tracking ---
    default void setArrivalReminderSentAt(LocalDateTime value) { setFieldValue(arrivalReminderSentAt, value); }
    default LocalDateTime getArrivalReminderSentAt() { return getLocalDateTimeFieldValue(arrivalReminderSentAt); }

    default void setArrivalConfirmationToken(String value) { setFieldValue(arrivalConfirmationToken, value); }
    default String getArrivalConfirmationToken() { return getStringFieldValue(arrivalConfirmationToken); }

    default void setArrivalTokenExpiresAt(LocalDateTime value) { setFieldValue(arrivalTokenExpiresAt, value); }
    default LocalDateTime getArrivalTokenExpiresAt() { return getLocalDateTimeFieldValue(arrivalTokenExpiresAt); }

    default void setArrivalTokenUsedAt(LocalDateTime value) { setFieldValue(arrivalTokenUsedAt, value); }
    default LocalDateTime getArrivalTokenUsedAt() { return getLocalDateTimeFieldValue(arrivalTokenUsedAt); }

    // --- Visa fields (merged) ---
    default void setVisaStatus(String value) { setFieldValue(visaStatus, value); }
    default String getVisaStatus() { return getStringFieldValue(visaStatus); }

    default void setUkPassportHolder(Boolean value) { setFieldValue(ukPassportHolder, value); }
    default Boolean isUkPassportHolder() { return getBooleanFieldValue(ukPassportHolder); }

    default void setPassportType(String value) { setFieldValue(passportType, value); }
    default String getPassportType() { return getStringFieldValue(passportType); }

    default void setPassportCountry(String value) { setFieldValue(passportCountry, value); }
    default String getPassportCountry() { return getStringFieldValue(passportCountry); }

    default void setImmigrationStatus(String value) { setFieldValue(immigrationStatus, value); }
    default String getImmigrationStatus() { return getStringFieldValue(immigrationStatus); }

    default void setImmigrationStatusOther(String value) { setFieldValue(immigrationStatusOther, value); }
    default String getImmigrationStatusOther() { return getStringFieldValue(immigrationStatusOther); }

    default void setShareCode(String value) { setFieldValue(shareCode, value); }
    default String getShareCode() { return getStringFieldValue(shareCode); }

    default void setVisitReason(String value) { setFieldValue(visitReason, value); }
    default String getVisitReason() { return getStringFieldValue(visitReason); }

    default void setVisitReasonOther(String value) { setFieldValue(visitReasonOther, value); }
    default String getVisitReasonOther() { return getStringFieldValue(visitReasonOther); }

    default void setPriorVolunteeredElsewhere(Boolean value) { setFieldValue(priorVolunteeredElsewhere, value); }
    default Boolean isPriorVolunteeredElsewhere() { return getBooleanFieldValue(priorVolunteeredElsewhere); }

    default void setVolunteer30dayConfirmed(Boolean value) { setFieldValue(volunteer30dayConfirmed, value); }
    default Boolean isVolunteer30dayConfirmed() { return getBooleanFieldValue(volunteer30dayConfirmed); }

    default void setVisaType(String value) { setFieldValue(visaType, value); }
    default String getVisaType() { return getStringFieldValue(visaType); }

    default void setUkEntryDate(LocalDate value) { setFieldValue(ukEntryDate, value); }
    default LocalDate getUkEntryDate() { return getLocalDateFieldValue(ukEntryDate); }

    default void setPriorVolunteerDays(Integer value) { setFieldValue(priorVolunteerDays, value); }
    default Integer getPriorVolunteerDays() { return getIntegerFieldValue(priorVolunteerDays); }

    default void setVisaAppliedDate(LocalDate value) { setFieldValue(visaAppliedDate, value); }
    default LocalDate getVisaAppliedDate() { return getLocalDateFieldValue(visaAppliedDate); }

    default void setVisaApprovedDate(LocalDate value) { setFieldValue(visaApprovedDate, value); }
    default LocalDate getVisaApprovedDate() { return getLocalDateFieldValue(visaApprovedDate); }

    default void setVisaNotes(String value) { setFieldValue(visaNotes, value); }
    default String getVisaNotes() { return getStringFieldValue(visaNotes); }

    // --- Reference fields (merged) ---
    default void setReferenceName(String value) { setFieldValue(referenceName, value); }
    default String getReferenceName() { return getStringFieldValue(referenceName); }

    default void setReferenceEmail(String value) { setFieldValue(referenceEmail, value); }
    default String getReferenceEmail() { return getStringFieldValue(referenceEmail); }

    default void setReferencePhone(String value) { setFieldValue(referencePhone, value); }
    default String getReferencePhone() { return getStringFieldValue(referencePhone); }

    default void setReferenceJobTitle(String value) { setFieldValue(referenceJobTitle, value); }
    default String getReferenceJobTitle() { return getStringFieldValue(referenceJobTitle); }

    default void setReferenceStatus(String value) { setFieldValue(referenceStatus, value); }
    default String getReferenceStatus() { return getStringFieldValue(referenceStatus); }

    default void setReferenceVerificationDate(LocalDate value) { setFieldValue(referenceVerificationDate, value); }
    default LocalDate getReferenceVerificationDate() { return getLocalDateFieldValue(referenceVerificationDate); }

    default void setReferenceNotes(String value) { setFieldValue(referenceNotes, value); }
    default String getReferenceNotes() { return getStringFieldValue(referenceNotes); }

    // --- Application source ---
    String applicationSource = "applicationSource";

    default void setApplicationSource(String value) { setFieldValue(applicationSource, value); }
    default String getApplicationSource() { return getStringFieldValue(applicationSource); }

    // --- Soft delete ---
    String removed = "removed";
    default void setRemoved(Boolean value) { setFieldValue(removed, value); }
    default Boolean isRemoved() { return getBooleanFieldValue(removed); }
}

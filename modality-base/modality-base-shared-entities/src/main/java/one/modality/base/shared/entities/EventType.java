package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasLabel;
import one.modality.base.shared.entities.markers.EntityHasName;
import one.modality.base.shared.entities.markers.EntityHasOrd;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

/**
 * @author Bruno Salmon
 */
public interface EventType extends Entity,
    EntityHasName,
    EntityHasLabel,
    EntityHasOrganization,
    EntityHasOrd {

    String bookingForm = "bookingForm";
    String recurringItem = "recurringItem";
    String registrationMailAccount = "registrationMailAccount";
    String supportEmail = "supportEmail";
    String ord = "ord";
    String ongoing = "ongoing";

    default void setBookingForm(Object value) {
        setForeignField(bookingForm, value);
    }

    default EntityId getBookingFormId() {
        return getForeignEntityId(bookingForm);
    }

    default BookingForm getBookingForm() {
        return getForeignEntity(bookingForm);
    }

    default void setRecurringItem(Object value) {
        setForeignField(recurringItem, value);
    }

    default EntityId getRecurringItemId() {
        return getForeignEntityId(recurringItem);
    }

    default Item getRecurringItem() {
        return getForeignEntity(recurringItem);
    }

    /** Mail account used as the "from" sender for letters/mails of events of this type, when the letter
     *  itself has no associated account. Takes precedence over the organization's registrationMailAccount. */
    default void setRegistrationMailAccount(Object value) {
        setForeignField(registrationMailAccount, value);
    }

    default EntityId getRegistrationMailAccountId() {
        return getForeignEntityId(registrationMailAccount);
    }

    default MailAccount getRegistrationMailAccount() {
        return getForeignEntity(registrationMailAccount);
    }

    /** Address shown to bookers on this event type's booking forms as "contact us for help with your
     *  booking". Takes precedence over the organization's supportEmail, so a programme with its own
     *  inbox (public talks, say) can route its bookers there while the rest of the centre's events
     *  keep the general address. Null defers to the organization. */
    default void setSupportEmail(String value) {
        setFieldValue(supportEmail, value);
    }

    default String getSupportEmail() {
        return getStringFieldValue(supportEmail);
    }


    default void setOrd(Integer value) {
        setFieldValue(ord, value);
    }

    default Integer getOrd() {
        return getIntegerFieldValue(ord);
    }

    /** Events of this type are ongoing containers rather than discrete events (Stays, Residents,
     *  Volunteering…) — they can span months or years, and event timelines may hide them by
     *  default. Named "ongoing" because "background" is taken by a legacy KBS2 styling field. */
    default void setOngoing(Boolean value) {
        setFieldValue(ongoing, value);
    }

    default Boolean isOngoing() {
        return getBooleanFieldValue(ongoing);
    }

    default Boolean isRecurring() {
        if (getRecurringItemId() != null)
            return true;
        if (isFieldLoaded(recurringItem))
            return false;
        return null;
    }
}
package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

/**
 * @author Bruno Salmon
 */
public interface MailAccount extends Entity, EntityHasOrganization {
    String name = "name";
    String email = "email";
    String isDefault = "default";
    String event = "event";
    String signatureLabel = "signatureLabel";
    String smtpAccount = "smtpAccount";

    default void setName(String value) {
        setFieldValue(name, value);
    }

    default String getName() {
        return getStringFieldValue(name);
    }

    default void setEmail(String value) {
        setFieldValue(email, value);
    }

    default String getEmail() {
        return getStringFieldValue(email);
    }

    default void setDefault(Boolean value) {
        setFieldValue(isDefault, value);
    }

    default Boolean isDefault() {
        return getBooleanFieldValue(isDefault);
    }

    default void setEvent(Object value) {
        setForeignField(event, value);
    }

    default EntityId getEventId() {
        return getForeignEntityId(event);
    }

    default Event getEvent() {
        return getForeignEntity(event);
    }

    default void setSignatureLabel(Object value) {
        setForeignField(signatureLabel, value);
    }

    default EntityId getSignatureLabelId() {
        return getForeignEntityId(signatureLabel);
    }

    default void setSmtpAccount(Object value) {
        setForeignField(smtpAccount, value);
    }

    default EntityId getSmtpAccountId() {
        return getForeignEntityId(smtpAccount);
    }

    default SmtpAccount getSmtpAccount() {
        return getForeignEntity(smtpAccount);
    }
}

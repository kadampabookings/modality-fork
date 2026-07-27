package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasDocument;
import dev.webfx.stack.orm.entity.Entity;
import one.modality.base.shared.entities.markers.EntityHasOrganization;

import java.time.Instant;
import java.time.LocalDate;

/**
 * @author Bruno Salmon
 */
public interface Mail extends Entity, EntityHasDocument, EntityHasOrganization {
    String fromName = "fromName";
    String fromEmail = "fromEmail";
    String subject = "subject";
    String content = "content";
    String out = "out";
    String magicLink = "magicLink";
    String account = "account";
    String date = "date";
    String letter = "letter";
    String transmitted = "transmitted";
    String transmissionDate = "transmissionDate";
    String error = "error";

    default void setFromName(String value) {
        setFieldValue(fromName, value);
    }

    default String getFromName() {
        return getStringFieldValue(fromName);
    }

    default void setFromEmail(String value) {
        setFieldValue(fromEmail, value);
    }

    default String getFromEmail() {
        return getStringFieldValue(fromEmail);
    }

    default void setSubject(String value) {
        setFieldValue(subject, value);
    }

    default String getSubject() {
        return getStringFieldValue(subject);
    }

    default void setContent(String value) {
        setFieldValue(content, value);
    }

    default String getContent() {
        return getStringFieldValue(content);
    }

    default void setOut(Boolean value) {
        setFieldValue(out, value);
    }

    default Boolean isOut() {
        return getBooleanFieldValue(out);
    }

    default void setMagicLink(Object value) {
        setForeignField(magicLink, value);
    }

    default EntityId getMagicLinkId() {
        return getForeignEntityId(magicLink);
    }

    default MagicLink getMagicLink() {
        return getForeignEntity(magicLink);
    }

    default void setAccount(Object value) {
        setForeignField(account, value);
    }

    default EntityId getAccountId() {
        return getForeignEntityId(account);
    }

    default MailAccount getAccount() {
        return getForeignEntity(account);
    }

    default void setDate(LocalDate value) {
        setFieldValue(date, value);
    }

    default LocalDate getDate() {
        return getLocalDateFieldValue(date);
    }

    default void setLetter(Object value) {
        setForeignField(letter, value);
    }

    default EntityId getLetterId() {
        return getForeignEntityId(letter);
    }

    default Letter getLetter() {
        return getForeignEntity(letter);
    }

    default void setTransmitted(Boolean value) {
        setFieldValue(transmitted, value);
    }

    default Boolean isTransmitted() {
        return getBooleanFieldValue(transmitted);
    }

    default void setTransmissionDate(Instant value) {
        setFieldValue(transmissionDate, value);
    }

    default Instant getTransmissionDate() {
        return getInstantFieldValue(transmissionDate);
    }

    default void setError(String value) {
        setFieldValue(error, value);
    }

    default String getError() {
        return getStringFieldValue(error);
    }

}
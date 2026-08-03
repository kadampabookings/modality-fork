package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.markers.EntityHasEvent;
import one.modality.base.shared.entities.markers.EntityHasEventType;
import one.modality.base.shared.entities.markers.EntityHasI18nFields;
import one.modality.base.shared.entities.markers.EntityHasName;
import one.modality.base.shared.entities.markers.EntityHasOrganization;
import one.modality.base.shared.entities.markers.EntityHasSite;

/**
 * @author Bruno Salmon
 */
public interface Letter extends
    //EntityHasIcon,
    EntityHasName,
    EntityHasOrganization,
    EntityHasEvent,
    // Scope columns (V0037): a letter may live at event / (site, eventType) / eventType /
    // (site, organization) / organization scope — narrowest wins, see docs/letter-scope-plan.md.
    EntityHasSite,
    EntityHasEventType,
    EntityHasI18nFields {

    // Suppression (V0043): an active letter that WINS scope resolution but sends nothing —
    // lets a narrower scope switch off a wider-scoped letter ("no cart letter for GP classes").
    String suppressesSending = "suppressesSending";

    String type = "type";
    String onHold = "onHold";
    // KBS2→KBS3 mail-transmission handover: mails of a flagged letter are transmitted by the
    // KBS3 mailer, unflagged ones stay with KBS2's — both drain queries test this same column,
    // so each mail has exactly one transmitter and letters migrate one by one via plain SQL.
    String kbs3 = "kbs3";
    // Web-push variant (V0055): non-null = bookers live-subscribed to this push context get the
    // letter as a web push (a channel='push' mail composed from push_title_<lang>/push_body_<lang>)
    // instead of an email. A DB CHECK forces push-variant letters onto the kbs3 mailer partition.
    String pushContext = "pushContext";
    String pushUrl = "pushUrl";

    default void setSuppressesSending(Boolean value) {
        setFieldValue(suppressesSending, value);
    }

    default Boolean isSuppressesSending() {
        return getBooleanFieldValue(suppressesSending);
    }

    default void setType(Object value) {
        setForeignField(type, value);
    }

    default EntityId getTypeId() {
        return getForeignEntityId(type);
    }

    default LetterType getType() {
        return getForeignEntity(type);
    }

    default void setOnHold(Boolean value) {
        setFieldValue(onHold, value);
    }

    default Boolean isOnHold() {
        return getBooleanFieldValue(onHold);
    }

    default void setKbs3(Boolean value) {
        setFieldValue(kbs3, value);
    }

    default Boolean isKbs3() {
        return getBooleanFieldValue(kbs3);
    }

    default void setPushContext(String value) {
        setFieldValue(pushContext, value);
    }

    default String getPushContext() {
        return getStringFieldValue(pushContext);
    }

    default void setPushUrl(String value) {
        setFieldValue(pushUrl, value);
    }

    default String getPushUrl() {
        return getStringFieldValue(pushUrl);
    }
}
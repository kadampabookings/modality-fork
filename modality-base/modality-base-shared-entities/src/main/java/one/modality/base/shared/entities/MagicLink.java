package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;

import java.time.Instant;

/**
 * @author Bruno Salmon
 */
public interface MagicLink extends Entity {
    String creationDate = "creationDate";
    String usageDate = "usageDate";
    String usageRunId = "usageRunId";
    String loginRunId = "loginRunId";
    String token = "token";
    String email = "email";
    String oldEmail = "oldEmail";
    String lang = "lang";
    String link = "link";
    String requestedPath = "requestedPath";
    String verificationCode = "verificationCode";
    String linkType = "linkType";

    default void setCreationDate(Instant value) {
        setFieldValue(creationDate, value);
    }

    default Instant getCreationDate() {
        return getInstantFieldValue(creationDate);
    }

    default void setUsageDate(Instant value) {
        setFieldValue(usageDate, value);
    }

    default Instant getUsageDate() {
        return getInstantFieldValue(usageDate);
    }

    default void setUsageRunId(String value) {
        setFieldValue(usageRunId, value);
    }

    default String getUsageRunId() {
        return getStringFieldValue(usageRunId);
    }

    default void setLoginRunId(String value) {
        setFieldValue(loginRunId, value);
    }

    default String getLoginRunId() {
        return getStringFieldValue(loginRunId);
    }

    default void setToken(String value) {
        setFieldValue(token, value);
    }

    default String getToken() {
        return getStringFieldValue(token);
    }

    default void setEmail(String value) {
        setFieldValue(email, value);
    }

    default String getEmail() {
        return getStringFieldValue(email);
    }

    default void setOldEmail(String value) {
        setFieldValue(oldEmail, value);
    }

    default String getOldEmail() {
        return getStringFieldValue(oldEmail);
    }

    default void setLang(String value) {
        setFieldValue(lang, value);
    }

    default String getLang() {
        return getStringFieldValue(lang);
    }

    default void setLink(String value) {
        setFieldValue(link, value);
    }

    default String getLink() {
        return getStringFieldValue(link);
    }

    default void setRequestedPath(String value) {
        setFieldValue(requestedPath, value);
    }

    default String getRequestedPath() {
        return getStringFieldValue(requestedPath);
    }

    default void setVerificationCode(String value) {
        setFieldValue(verificationCode, value);
    }

    default String getVerificationCode() {
        return getStringFieldValue(verificationCode);
    }

    default void setLinkType(MagicLinkType value) {
        setFieldValue(linkType, value != null ? value.name() : null);
    }

    default MagicLinkType getLinkType() {
        String value = getStringFieldValue(linkType);
        if (value == null) return MagicLinkType.LOGIN;
        try {
            return MagicLinkType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return MagicLinkType.LOGIN; // safe fallback for unknown future values
        }
    }

    default boolean isBookingAccess() {
        return MagicLinkType.BOOKING_ACCESS == getLinkType();
    }

    /**
     * True for a support-view grant — a support member's read-only pass into a customer's front
     * office. These are never emailed and never behave like a login link; see {@link MagicLinkType}
     * for why every redemption path must check the type rather than trusting the token alone.
     */
    default boolean isSupportView() {
        return MagicLinkType.SUPPORT_VIEW == getLinkType();
    }
}
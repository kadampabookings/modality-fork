package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;

import java.time.Instant;

/**
 * A browser-side Web Push subscription, one per (device, browser profile,
 * origin). Carries the addressing ({@link #endpoint}) and the encryption
 * keys ({@link #p256dhKey}, {@link #authKey}) the server needs to send an
 * encrypted push message, plus device-level metadata captured at subscribe
 * time and refreshed on rotation ({@link #userAgent}, {@link #lang},
 * {@link #timezone}, {@link #displayMode}, {@link #appTimestamp}).
 * <p>
 * The {@code endpoint} is the unique key — both the application's "find
 * existing subscription by device" lookup and the SW's rotate handler use
 * it. A DB UNIQUE constraint enforces this server-side.
 *
 * @author Bruno Salmon
 */
public interface PushSubscription extends Entity {

    String endpoint = "endpoint";
    String p256dhKey = "p256dhKey";
    String authKey = "authKey";
    String userAgent = "userAgent";
    String lang = "lang";
    String timezone = "timezone";
    String displayMode = "displayMode";
    String appTimestamp = "appTimestamp";
    String createdAt = "createdAt";
    String lastSeenAt = "lastSeenAt";
    /**
     * The VAPID public key the browser was subscribed with. Captured at
     * subscribe time and used at send time to skip subscriptions whose
     * server identity no longer matches (different environment after a
     * prod→staging DB copy, or a rotated VAPID keypair).
     */
    String vapidPublicKey = "vapidPublicKey";

    default String getEndpoint() { return getStringFieldValue(endpoint); }
    default void setEndpoint(String value) { setFieldValue(endpoint, value); }

    default String getP256dhKey() { return getStringFieldValue(p256dhKey); }
    default void setP256dhKey(String value) { setFieldValue(p256dhKey, value); }

    default String getAuthKey() { return getStringFieldValue(authKey); }
    default void setAuthKey(String value) { setFieldValue(authKey, value); }

    default String getUserAgent() { return getStringFieldValue(userAgent); }
    default void setUserAgent(String value) { setFieldValue(userAgent, value); }

    default String getLang() { return getStringFieldValue(lang); }
    default void setLang(String value) { setFieldValue(lang, value); }

    default String getTimezone() { return getStringFieldValue(timezone); }
    default void setTimezone(String value) { setFieldValue(timezone, value); }

    default String getDisplayMode() { return getStringFieldValue(displayMode); }
    default void setDisplayMode(String value) { setFieldValue(displayMode, value); }

    default String getAppTimestamp() { return getStringFieldValue(appTimestamp); }
    default void setAppTimestamp(String value) { setFieldValue(appTimestamp, value); }

    default Instant getCreatedAt() { return getInstantFieldValue(createdAt); }

    default Instant getLastSeenAt() { return getInstantFieldValue(lastSeenAt); }
    default void setLastSeenAt(Instant value) { setFieldValue(lastSeenAt, value); }

    default String getVapidPublicKey() { return getStringFieldValue(vapidPublicKey); }
    default void setVapidPublicKey(String value) { setFieldValue(vapidPublicKey, value); }
}

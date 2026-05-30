// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * KBS-side glue that adapts the generic webfx-stack-webpush-server module
        to KBS's domain. Provides:
        - a `WebPushSubscriptionStore` impl that stores and queries push subscriptions
          and their recipient links via Modality's `PushSubscription` /
          `PushSubscriptionRecipient` entities;
        - the `ModalityWebPushTarget` type (with its SerialCodec) used as the
          `target` payload of the generic `SendPushNotification` BO operation —
          carries the FK(s) that scope the recipient set (event / document /
          organization).
        Pulled in by kbs-server-application as a runtime plugin.
 */
module modality.crm.server.webpush.plugin {

    // Direct dependencies modules
    requires modality.base.shared.entities;
    requires webfx.platform.ast;
    requires webfx.platform.async;
    requires webfx.platform.console;
    requires webfx.stack.com.serial;
    requires webfx.stack.db.submit;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.orm.entity;
    requires webfx.stack.webpush.server;

    // Exported packages
    exports one.modality.crm.server.webpush;
    exports one.modality.crm.server.webpush.serial;

    // Provided services
    provides dev.webfx.stack.com.serial.spi.SerialCodec with one.modality.crm.server.webpush.serial.ModalityWebPushTargetSerialCodec;
    provides dev.webfx.stack.webpush.spi.WebPushSubscriptionStore with one.modality.crm.server.webpush.ModalityWebPushSubscriptionStore;

}
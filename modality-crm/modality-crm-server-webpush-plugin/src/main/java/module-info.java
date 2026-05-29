// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * KBS-side glue that adapts the generic webfx-stack-webpush-server module
        to KBS's domain: provides a `WebPushSubscriptionStore` implementation that updates
        the modality `PushSubscription` entity via the standard EntityStore/UpdateStore
        pipeline. Pulled in by kbs-server-application as a runtime plugin.
 */
module modality.crm.server.webpush.plugin {

    // Direct dependencies modules
    requires modality.base.shared.entities;
    requires webfx.platform.async;
    requires webfx.platform.console;
    requires webfx.stack.orm.entity;
    requires webfx.stack.webpush.server;

    // Exported packages
    exports one.modality.crm.server.webpush;

    // Provided services
    provides dev.webfx.stack.webpush.spi.WebPushSubscriptionStore with one.modality.crm.server.webpush.ModalityWebPushSubscriptionStore;

}
// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * The Modality web-push mailer: drains channel='push' mails (created by the
        system-letter trigger when a letter has a web-push variant and the booker a live
        subscription on its push context) and transmits each one as a Web Push notification
        to every live subscribed device of the booker, through webfx-stack-webpush-server.
        Bookkeeping mirrors the SMTP mailer (always marked transmitted — with the error
        recorded on failure — so a poison row can never block the queue), plus two
        push-specific duties: an endpoint answering 410/404 deletes the dead
        push_subscription row (recipient links cascade), and a mail with zero successful
        deliveries re-fires document.trigger_send_system_letter_id so the trigger composes
        the email variant instead (automatic email fallback — the already-created push row
        makes the trigger skip the push branch on the second pass). Disabled by default
        (modality.base.server.webpushmailer.enabled); sandbox mode processes the queue but
        logs instead of sending — the intended staging state, since a prod→staging DB
        refresh copies real device subscriptions.
 */
module modality.base.server.webpushmailer.plugin {

    // Direct dependencies modules
    requires modality.base.shared.entities;
    requires webfx.platform.async;
    requires webfx.platform.boot;
    requires webfx.platform.conf;
    requires webfx.platform.console;
    requires webfx.platform.meta;
    requires webfx.platform.scheduler;
    requires webfx.platform.util;
    requires webfx.stack.db.datasource;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.orm.domainmodel;
    requires webfx.stack.orm.entity;
    requires webfx.stack.session.state;
    requires webfx.stack.webpush.server;

    // Exported packages
    exports one.modality.base.server.webpushmailer;

    // Provided services
    provides dev.webfx.platform.boot.spi.ApplicationJob with one.modality.base.server.webpushmailer.WebPushMailerJob;

}
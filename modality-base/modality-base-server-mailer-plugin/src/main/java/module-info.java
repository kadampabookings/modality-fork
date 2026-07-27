// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * The Modality mailer: drains the shared mail table (out=true, untransmitted)
        and transmits each mail through the webfx mail transport, taking over the role of
        KBS2's legacy JavaMailSender. Behaviour-preserving port: same drain order (letter
        type ord), same 4s inter-send spacing, same sender resolution (awsSesVerified vs
        central address + Reply-To), same custom Message-ID, same bookkeeping (transmitted /
        error / recipient ok / document confirmed-cancelled-read side effects). The KBS2→KBS3
        handover is progressive: with drainScope = flagged (default) only letterless mails
        and letters flagged kbs3 are drained here, everything else stays with
        KBS2's mailer — both drain queries test the same DB column, so each mail has exactly
        one transmitter. The job is disabled by default (modality.base.server.mailer.enabled)
        and additionally requires a configured mail transport provider before it pulses.
 */
module modality.base.server.mailer.plugin {

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
    requires webfx.stack.mail.transport;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.orm.domainmodel;
    requires webfx.stack.orm.entity;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.base.server.mailer;

    // Provided services
    provides dev.webfx.platform.boot.spi.ApplicationJob with one.modality.base.server.mailer.MailerJob;

}
// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * Records one row per live login session (auth_session, V0083) so that renewing an
        identity token retires the one it replaces, and a retired token turning up again is recognised
        as a copy somebody else is holding. Stores a generation counter, never a token.
 */
module modality.crm.server.authsession.plugin {

    // Direct dependencies modules
    requires modality.crm.shared.authn;
    requires webfx.platform.async;
    requires webfx.platform.boot;
    requires webfx.platform.console;
    requires webfx.platform.scheduler;
    requires webfx.platform.util;
    requires webfx.stack.db.datasource;
    requires webfx.stack.db.query;
    requires webfx.stack.db.submit;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.session.state;
    requires webfx.stack.session.token;

    // Exported packages
    exports one.modality.crm.server.authsession;

    // Provided services
    provides dev.webfx.platform.boot.spi.ApplicationJob with one.modality.crm.server.authsession.ModalityAuthSessionStoreInitializer;

}
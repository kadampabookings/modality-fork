// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * Server-side module responsible for pushing the user authorization data to the client just after a successful authentication.
 */
module modality.crm.server.authz.required.plugin {

    // Direct dependencies modules
    requires modality.base.shared.entities;
    requires webfx.extras.operation;
    requires webfx.platform.boot;
    requires webfx.platform.console;
    requires webfx.platform.async;
    requires webfx.platform.util;
    requires webfx.stack.authn;
    requires webfx.stack.authz.core;
    requires webfx.stack.authz.server;
    requires webfx.stack.com.bus;
    requires webfx.stack.db.submit;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.orm.entity;
    requires webfx.stack.push.server;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.crm.server.services.authz;

    // Provided services
    provides dev.webfx.platform.boot.spi.ApplicationJob with one.modality.crm.server.services.authz.ProtectedEntityWritesJob;
    provides dev.webfx.stack.authz.server.spi.AuthorizationServerServiceProvider with one.modality.crm.server.services.authz.ModalityAuthorizationServerServiceProvider;

}
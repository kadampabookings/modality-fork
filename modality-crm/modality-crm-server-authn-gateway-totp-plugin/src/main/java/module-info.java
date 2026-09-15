// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * Server-side TOTP (RFC 6238) second-factor authentication gateway.
 */
module modality.crm.server.authn.gateway.totp.plugin {

    // Direct dependencies modules
    requires modality.base.shared.entities;
    requires modality.crm.server.authn.gateway.shared;
    requires modality.crm.shared.authn;
    requires webfx.platform.ast;
    requires webfx.platform.ast.json.plugin;
    requires webfx.platform.async;
    requires webfx.platform.conf;
    requires webfx.platform.console;
    requires webfx.platform.substitution;
    requires webfx.platform.util;
    requires webfx.stack.authn;
    requires webfx.stack.authn.logout.server;
    requires webfx.stack.authn.server.gateway;
    requires webfx.stack.db.query;
    requires webfx.stack.db.submit;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.orm.domainmodel;
    requires webfx.stack.orm.entity;
    requires webfx.stack.push.server;
    requires webfx.stack.session.state;
    requires webfx.stack.session.token;

    // Exported packages
    exports one.modality.crm.server.authn.gateway.totp;

    // Provided services
    provides dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway with one.modality.crm.server.authn.gateway.totp.ModalityTotpAuthenticationGateway;
    provides one.modality.crm.server.authn.gateway.shared.SecondFactorVerifier with one.modality.crm.server.authn.gateway.totp.TotpSecondFactorVerifier;

}
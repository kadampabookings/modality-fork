// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * Server-side authentication gateway for ModalityGuestPrincipal sessions (no registered account).
 */
module modality.crm.server.authn.gateway.guest.plugin {

    // Direct dependencies modules
    requires modality.base.server.mail;
    requires modality.base.shared.context;
    requires modality.base.shared.entities;
    requires modality.crm.server.authn.gateway.shared;
    requires modality.crm.shared.authn;
    requires modality.ecommerce.document.service;
    requires webfx.platform.async;
    requires webfx.platform.util;
    requires webfx.stack.authn;
    requires webfx.stack.authn.logout.server;
    requires webfx.stack.authn.server.gateway;
    requires webfx.stack.mail;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.orm.domainmodel;
    requires webfx.stack.orm.entity;
    requires webfx.stack.push.server;
    requires webfx.stack.session.state;
    requires webfx.stack.session.token;

    // Exported packages
    exports one.modality.crm.server.authn.gateway.guest;

    // Resources packages
    opens one.modality.crm.server.authn.gateway.guest;

    // Provided services
    provides dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway with one.modality.crm.server.authn.gateway.guest.ModalityGuestAuthenticationGateway;
    provides one.modality.ecommerce.document.service.GuestBookingAccessService with one.modality.crm.server.authn.gateway.guest.ModalityGuestAuthenticationGateway;

}
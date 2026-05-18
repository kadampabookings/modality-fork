// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * Server-side authentication gateway for ModalityGuestPrincipal sessions (no registered account).
 */
module modality.crm.server.authn.gateway.guest.plugin {

    // Direct dependencies modules
    requires modality.crm.server.authn.gateway.shared;
    requires modality.crm.shared.authn;
    requires modality.ecommerce.document.service;
    requires webfx.platform.async;
    requires webfx.stack.authn;
    requires webfx.stack.authn.logout.server;
    requires webfx.stack.authn.server.gateway;
    requires webfx.stack.orm.domainmodel;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.crm.server.authn.gateway.guest;

    // Provided services
    provides dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway with one.modality.crm.server.authn.gateway.guest.ModalityGuestAuthenticationGateway;
    provides one.modality.ecommerce.document.service.GuestBookingAccessService with one.modality.crm.server.authn.gateway.guest.ModalityGuestAuthenticationGateway;

}
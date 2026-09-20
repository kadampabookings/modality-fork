// File managed by WebFX (DO NOT EDIT MANUALLY)

module modality.hotel.server.resource.plugin {

    // Direct dependencies modules
    requires modality.crm.server.authn.gateway.shared;
    requires webfx.platform.async;
    requires webfx.platform.console;
    requires webfx.platform.util;
    requires webfx.stack.com.bus.call;
    requires webfx.stack.db.query;
    requires webfx.stack.db.submit;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.hotel.server.resource;

    // Provided services
    provides dev.webfx.stack.com.bus.call.spi.BusCallEndpoint with one.modality.hotel.server.resource.DeleteRoomEndpoint;

}
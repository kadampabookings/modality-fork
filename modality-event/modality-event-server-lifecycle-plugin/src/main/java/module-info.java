// File managed by WebFX (DO NOT EDIT MANUALLY)

module modality.event.server.lifecycle.plugin {

    // Direct dependencies modules
    requires io.vertx.core;
    requires io.vertx.sql.client;
    requires io.vertx.sql.client.pg;
    requires modality.crm.server.authn.gateway.shared;
    requires modality.crm.shared.authn;
    requires webfx.platform.async;
    requires webfx.platform.conf;
    requires webfx.platform.console;
    requires webfx.platform.util;
    requires webfx.platform.util.vertx;
    requires webfx.stack.com.bus.call;
    requires webfx.stack.db.datasource;
    requires webfx.stack.db.query;
    requires webfx.stack.db.submit;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.event.server.lifecycle;

    // Provided services
    provides dev.webfx.stack.com.bus.call.spi.BusCallEndpoint with one.modality.event.server.lifecycle.DuplicateEventEndpoint, one.modality.event.server.lifecycle.StartEventErasureEndpoint, one.modality.event.server.lifecycle.EventErasureRunEndpoint, one.modality.event.server.lifecycle.EventErasureRunsEndpoint;

}
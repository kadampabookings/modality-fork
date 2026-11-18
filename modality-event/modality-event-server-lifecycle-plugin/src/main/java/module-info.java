// File managed by WebFX (DO NOT EDIT MANUALLY)

module modality.event.server.lifecycle.plugin {

    // Direct dependencies modules
    requires io.vertx.core;
    requires io.vertx.sql.client;
    requires io.vertx.sql.client.pg;
    requires modality.crm.server.authn.gateway.shared;
    requires modality.crm.shared.authn;
    requires webfx.platform.ast;
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
    requires webfx.stack.orm.entity;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.event.server.lifecycle;

    // Provided services
    provides dev.webfx.stack.com.bus.call.spi.BusCallEndpoint with one.modality.event.server.lifecycle.DuplicateEventEndpoint, one.modality.event.server.lifecycle.StartEventErasureEndpoint, one.modality.event.server.lifecycle.EventErasureRunEndpoint, one.modality.event.server.lifecycle.EventErasureRunsEndpoint, one.modality.event.server.lifecycle.SetEventBookingStateEndpoint, one.modality.event.server.lifecycle.EventRemovalDatesEndpoint, one.modality.event.server.lifecycle.PreviewPersonAnonymisationEndpoint, one.modality.event.server.lifecycle.CommitPersonAnonymisationEndpoint, one.modality.event.server.lifecycle.PreviewBookerAnonymisationEndpoint, one.modality.event.server.lifecycle.CommitBookerAnonymisationEndpoint, one.modality.event.server.lifecycle.DataProtectionAccessEndpoint, one.modality.event.server.lifecycle.ErasureFindEndpoint, one.modality.event.server.lifecycle.ErasureCheckEndpoint, one.modality.event.server.lifecycle.ErasureRequestsEndpoint, one.modality.event.server.lifecycle.SaveErasureRequestEndpoint, one.modality.event.server.lifecycle.RetentionPolicyEndpoint, one.modality.event.server.lifecycle.SetRetentionPolicyEndpoint, one.modality.event.server.lifecycle.DataProtectionActivityEndpoint;

}
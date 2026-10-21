// File managed by WebFX (DO NOT EDIT MANUALLY)

module modality.event.server.rest.bunny.stream.vertx.plugin {

    // Direct dependencies modules
    requires io.vertx.core;
    requires io.vertx.web;
    requires modality.base.shared.entities;
    requires modality.crm.server.authn.gateway.shared;
    requires webfx.platform.ast;
    requires webfx.platform.async;
    requires webfx.platform.boot;
    requires webfx.platform.console;
    requires webfx.platform.fetch;
    requires webfx.platform.fetch.ast.json;
    requires webfx.platform.util;
    requires webfx.platform.util.http;
    requires webfx.platform.util.vertx;
    requires webfx.stack.com.bus.call;
    requires webfx.stack.db.query;
    requires webfx.stack.db.submit;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.orm.entity;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.event.server.rest.bunnyaudio;
    exports one.modality.event.server.rest.bunnystream;

    // Provided services
    provides dev.webfx.platform.boot.spi.ApplicationModuleBooter with one.modality.event.server.rest.bunnystream.BunnyStreamRestService, one.modality.event.server.rest.bunnyaudio.BunnyAudioRestService;
    provides dev.webfx.stack.com.bus.call.spi.BusCallEndpoint with one.modality.event.server.rest.bunnyaudio.DescribeAudioStorageEndpoint, one.modality.event.server.rest.bunnyaudio.SetAudioFolderEndpoint, one.modality.event.server.rest.bunnyaudio.PrepareAudioUploadsEndpoint, one.modality.event.server.rest.bunnyaudio.DeleteAudioRecordingEndpoint, one.modality.event.server.rest.bunnyaudio.CreateAudioFoldersEndpoint;

}
// File managed manually for now; mirrors modality-base-server-rest-map-vertx-plugin structure.

module modality.event.server.rest.bunny.stream.vertx.plugin {

    // Direct dependencies modules
    requires io.vertx.core;
    requires io.vertx.web;
    requires modality.base.shared.entities;
    requires webfx.platform.async;
    requires webfx.platform.ast;
    requires webfx.platform.boot;
    requires webfx.platform.console;
    requires webfx.platform.fetch;
    requires webfx.platform.fetch.ast.json;
    requires webfx.platform.util;
    requires webfx.platform.util.http;
    requires webfx.platform.util.vertx;
    requires webfx.stack.orm.entity;

    // Exported packages
    exports one.modality.event.server.rest.bunnystream;

    // Provided services
    provides dev.webfx.platform.boot.spi.ApplicationModuleBooter with one.modality.event.server.rest.bunnystream.BunnyStreamRestService;

}

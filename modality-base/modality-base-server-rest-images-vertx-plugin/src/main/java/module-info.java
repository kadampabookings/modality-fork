// File managed by WebFX (DO NOT EDIT MANUALLY)

module modality.base.server.rest.images.vertx.plugin {

    // Direct dependencies modules
    requires io.vertx.core;
    requires io.vertx.web;
    requires modality.base.shared.entities;
    requires modality.crm.shared.authn;
    requires webfx.platform.async;
    requires webfx.platform.blob;
    requires webfx.platform.boot;
    requires webfx.platform.conf;
    requires webfx.platform.console;
    requires webfx.platform.file.jre;
    requires webfx.platform.util;
    requires webfx.platform.util.http;
    requires webfx.platform.util.vertx;
    requires webfx.stack.cloud.image;
    requires webfx.stack.orm.entity;
    requires webfx.stack.session;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.base.server.rest.images;

    // Provided services
    provides dev.webfx.platform.boot.spi.ApplicationModuleBooter with one.modality.base.server.rest.images.SecuredImagesRestService;

}

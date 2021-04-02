// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.client.sessionrecorder {

    // Direct dependencies modules
    requires java.base;
    requires javafx.base;
    requires mongoose.client.authn;
    requires webfx.framework.client.push;
    requires webfx.framework.client.uirouter;
    requires webfx.framework.shared.orm.datasourcemodelservice;
    requires webfx.framework.shared.orm.entity;
    requires webfx.kit.launcher;
    requires webfx.platform.client.storage;
    requires webfx.platform.shared.appcontainer;
    requires webfx.platform.shared.bus;
    requires webfx.platform.shared.log;
    requires webfx.platform.shared.submit;
    requires webfx.platform.shared.util;

    // Exported packages
    exports mongoose.client.jobs.sessionrecorder;

    // Provided services
    provides dev.webfx.platform.shared.services.appcontainer.spi.ApplicationJob with mongoose.client.jobs.sessionrecorder.ClientSessionRecorderJob;

}
// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.server.sessioncloser {

    // Direct dependencies modules
    requires webfx.framework.server.push;
    requires webfx.framework.shared.orm.datasourcemodelservice;
    requires webfx.framework.shared.orm.domainmodel;
    requires webfx.platform.shared.appcontainer;
    requires webfx.platform.shared.log;
    requires webfx.platform.shared.submit;
    requires webfx.platform.shared.util;

    // Exported packages
    exports mongoose.server.jobs.sessioncloser;

    // Provided services
    provides dev.webfx.platform.shared.services.appcontainer.spi.ApplicationJob with mongoose.server.jobs.sessioncloser.MongooseServerUnresponsiveClientSessionCloserJob;

}
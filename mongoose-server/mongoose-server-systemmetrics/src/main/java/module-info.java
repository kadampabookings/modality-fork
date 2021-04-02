// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.server.systemmetrics {

    // Direct dependencies modules
    requires java.base;
    requires mongoose.shared.entities;
    requires webfx.framework.shared.orm.datasourcemodelservice;
    requires webfx.framework.shared.orm.domainmodel;
    requires webfx.framework.shared.orm.entity;
    requires webfx.platform.shared.appcontainer;
    requires webfx.platform.shared.log;
    requires webfx.platform.shared.scheduler;
    requires webfx.platform.shared.submit;
    requires webfx.platform.shared.util;

    // Exported packages
    exports mongoose.server.jobs.systemmetrics;
    exports mongoose.server.services.systemmetrics;
    exports mongoose.server.services.systemmetrics.spi;

    // Used services
    uses mongoose.server.services.systemmetrics.spi.SystemMetricsServiceProvider;

    // Provided services
    provides dev.webfx.platform.shared.services.appcontainer.spi.ApplicationJob with mongoose.server.jobs.systemmetrics.SystemMetricsRecorderJob;

}
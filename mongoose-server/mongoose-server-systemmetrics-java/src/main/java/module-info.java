// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.server.systemmetrics.java {

    // Direct dependencies modules
    requires java.base;
    requires jdk.management;
    requires mongoose.server.systemmetrics;
    requires mongoose.shared.entities;

    // Exported packages
    exports mongoose.server.services.systemmetrics.spi.java;

    // Provided services
    provides mongoose.server.services.systemmetrics.spi.SystemMetricsServiceProvider with mongoose.server.services.systemmetrics.spi.java.JavaSystemMetricsServiceProvider;

}
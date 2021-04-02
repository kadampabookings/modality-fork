// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.frontend.activities.program {

    // Direct dependencies modules
    requires javafx.controls;
    requires javafx.graphics;
    requires mongoose.client.bookingcalendar;
    requires mongoose.client.bookingprocess;
    requires mongoose.client.businesslogic;
    requires mongoose.client.icons;
    requires mongoose.client.sectionpanel;
    requires mongoose.client.util;
    requires webfx.framework.client.activity;
    requires webfx.framework.client.orm.domainmodel.activity;
    requires webfx.framework.client.uirouter;
    requires webfx.framework.client.util;
    requires webfx.platform.client.windowhistory;
    requires webfx.platform.shared.log;
    requires webfx.platform.shared.util;

    // Exported packages
    exports mongoose.frontend.activities.program;
    exports mongoose.frontend.activities.program.routing;
    exports mongoose.frontend.operations.program;

    // Provided services
    provides dev.webfx.framework.client.ui.uirouter.UiRoute with mongoose.frontend.activities.program.ProgramUiRoute;

}
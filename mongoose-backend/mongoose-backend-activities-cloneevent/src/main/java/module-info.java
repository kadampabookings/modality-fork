// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.backend.activities.cloneevent {

    // Direct dependencies modules
    requires java.base;
    requires javafx.base;
    requires javafx.controls;
    requires javafx.graphics;
    requires mongoose.backend.activities.bookings;
    requires mongoose.backend.activities.cloneevent.routing;
    requires mongoose.client.activity;
    requires mongoose.shared.domainmodel;
    requires mongoose.shared.entities;
    requires webfx.framework.client.activity;
    requires webfx.framework.client.controls;
    requires webfx.framework.client.orm.domainmodel.activity;
    requires webfx.framework.client.uirouter;
    requires webfx.framework.shared.orm.entity;
    requires webfx.kit.util;
    requires webfx.platform.client.uischeduler;
    requires webfx.platform.shared.submit;
    requires webfx.platform.shared.util;

    // Exported packages
    exports mongoose.backend.activities.cloneevent;

    // Provided services
    provides dev.webfx.framework.client.ui.uirouter.UiRoute with mongoose.backend.activities.cloneevent.CloneEventUiRoute;

}
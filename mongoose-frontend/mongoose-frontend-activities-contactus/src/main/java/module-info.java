// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.frontend.activities.contactus {

    // Direct dependencies modules
    requires javafx.controls;
    requires javafx.graphics;
    requires mongoose.client.activity;
    requires mongoose.client.util;
    requires mongoose.client.validation;
    requires mongoose.frontend.activities.cart.routing;
    requires mongoose.shared.entities;
    requires webfx.framework.client.action;
    requires webfx.framework.client.activity;
    requires webfx.framework.client.orm.domainmodel.activity;
    requires webfx.framework.client.uirouter;
    requires webfx.framework.client.util;
    requires webfx.framework.shared.orm.entity;
    requires webfx.platform.client.uischeduler;
    requires webfx.platform.client.windowhistory;
    requires webfx.platform.client.windowlocation;
    requires webfx.platform.shared.log;
    requires webfx.platform.shared.submit;
    requires webfx.platform.shared.util;

    // Exported packages
    exports mongoose.frontend.activities.contactus;
    exports mongoose.frontend.activities.contactus.routing;
    exports mongoose.frontend.operations.contactus;

    // Provided services
    provides dev.webfx.framework.client.ui.uirouter.UiRoute with mongoose.frontend.activities.contactus.ContactUsUiRoute;

}
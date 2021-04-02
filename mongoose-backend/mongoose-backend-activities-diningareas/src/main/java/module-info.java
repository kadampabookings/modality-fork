// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.backend.activities.diningareas {

    // Direct dependencies modules
    requires javafx.base;
    requires javafx.controls;
    requires javafx.graphics;
    requires mongoose.backend.activities.statistics;
    requires mongoose.backend.masterslave;
    requires mongoose.backend.operations.allocationrule;
    requires mongoose.backend.operations.generic;
    requires mongoose.client.activity;
    requires mongoose.client.util;
    requires mongoose.shared.entities;
    requires webfx.extras.visual.base;
    requires webfx.extras.visual.controls.grid;
    requires webfx.framework.client.action;
    requires webfx.framework.client.activity;
    requires webfx.framework.client.operationaction;
    requires webfx.framework.client.orm.domainmodel.activity;
    requires webfx.framework.client.orm.reactive.visual;
    requires webfx.framework.client.uirouter;
    requires webfx.framework.shared.operation;
    requires webfx.framework.shared.orm.dql;
    requires webfx.framework.shared.orm.entity;
    requires webfx.framework.shared.router;
    requires webfx.platform.client.windowhistory;
    requires webfx.platform.shared.util;

    // Exported packages
    exports mongoose.backend.activities.diningareas;
    exports mongoose.backend.activities.diningareas.routing;
    exports mongoose.backend.operations.routes.diningareas;

    // Provided services
    provides dev.webfx.framework.client.operations.route.RouteRequestEmitter with mongoose.backend.activities.diningareas.RouteToDiningAreasRequestEmitter;
    provides dev.webfx.framework.client.ui.uirouter.UiRoute with mongoose.backend.activities.diningareas.DiningAreasUiRoute;

}
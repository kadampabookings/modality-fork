// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.backend.activities.letters {

    // Direct dependencies modules
    requires javafx.graphics;
    requires mongoose.backend.activities.letter;
    requires mongoose.client.activity;
    requires mongoose.client.util;
    requires webfx.framework.client.activity;
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
    exports mongoose.backend.activities.letters;
    exports mongoose.backend.activities.letters.routing;
    exports mongoose.backend.operations.routes.letters;

    // Provided services
    provides dev.webfx.framework.client.operations.route.RouteRequestEmitter with mongoose.backend.activities.letters.RouteToLettersRequestEmitter;
    provides dev.webfx.framework.client.ui.uirouter.UiRoute with mongoose.backend.activities.letters.LettersUiRoute;

}
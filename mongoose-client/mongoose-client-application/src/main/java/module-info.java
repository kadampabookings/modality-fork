// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.client.application {

    // Direct dependencies modules
    requires java.base;
    requires javafx.base;
    requires javafx.controls;
    requires javafx.graphics;
    requires mongoose.client.activities.login;
    requires mongoose.client.activities.unauthorized;
    requires mongoose.client.activity;
    requires mongoose.client.authz;
    requires mongoose.client.busconfig;
    requires mongoose.client.css;
    requires mongoose.client.i18n;
    requires mongoose.client.icons;
    requires mongoose.client.operationactionsloading;
    requires mongoose.client.sessionrecorder;
    requires webfx.extras.imagestore;
    requires webfx.framework.client.action;
    requires webfx.framework.client.activity;
    requires webfx.framework.client.i18n;
    requires webfx.framework.client.operationaction;
    requires webfx.framework.client.orm.domainmodel.activity;
    requires webfx.framework.client.push.simple;
    requires webfx.framework.client.querypush.simple;
    requires webfx.framework.client.uirouter;
    requires webfx.framework.client.util;
    requires webfx.framework.shared.operation;
    requires webfx.framework.shared.orm.datasourcemodelservice;
    requires webfx.framework.shared.router;
    requires webfx.kit.launcher;
    requires webfx.kit.util;
    requires webfx.platform.client.uischeduler;
    requires webfx.platform.shared.buscall;
    requires webfx.platform.shared.util;

    // Exported packages
    exports mongoose.client.application;

    // Resources packages
    opens images.buddhas;
    opens mongoose.client.images;

}
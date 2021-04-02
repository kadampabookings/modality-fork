// File managed by WebFX (DO NOT EDIT MANUALLY)

module mongoose.backend.application.javafx {

    // Direct dependencies modules
    requires javafx.controls;
    requires mongoose.backend.activities.cloneevent;
    requires mongoose.backend.activities.cloneevent.routing;
    requires mongoose.backend.application;
    requires mongoose.client.navigationarrows.java;
    requires mongoose.shared.domainmodel;
    requires webfx.framework.client.activity;
    requires webfx.framework.client.orm.domainmodel.activity;
    requires webfx.framework.client.uirouter;
    requires webfx.framework.shared.orm.dql.query.interceptor;
    requires webfx.framework.shared.orm.dql.querypush.interceptor;
    requires webfx.framework.shared.orm.dql.submit.interceptor;
    requires webfx.kit.javafx;
    requires webfx.platform.java.appcontainer.impl;
    requires webfx.platform.java.resource.impl;
    requires webfx.platform.java.scheduler.impl;
    requires webfx.platform.java.shutdown.impl;
    requires webfx.platform.java.storage.impl;
    requires webfx.platform.java.websocket.impl;
    requires webfx.platform.java.windowhistory.impl;
    requires webfx.platform.java.windowlocation.impl;
    requires webfx.platform.shared.json.impl;
    requires webfx.platform.shared.log.impl.simple;
    requires webfx.platform.shared.query.impl.remote;
    requires webfx.platform.shared.submit.impl.remote;
    requires webfx.platform.shared.util;

    // Exported packages
    exports mongoose.backend.activities.event.clone.javafx;

}
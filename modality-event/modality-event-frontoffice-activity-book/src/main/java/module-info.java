// File managed by WebFX (DO NOT EDIT MANUALLY)

module modality.event.frontoffice.activity.book {

    // Direct dependencies modules
    requires javafx.base;
    requires javafx.controls;
    requires javafx.graphics;
    requires modality.base.client.mainframe.fx;
    requires modality.base.client.util;
    requires modality.base.frontoffice.mainframe.fx;
    requires modality.base.frontoffice.utility;
    requires modality.base.shared.entities;
    requires modality.booking.client.workingbooking;
    requires modality.booking.frontoffice.bookingform;
    requires modality.crm.client.authn.fx;
    requires modality.ecommerce.document.service;
    requires modality.ecommerce.payment;
    requires modality.ecommerce.policy.service;
    requires modality.event.client.event.fx;
    requires modality.event.client.lifecycle;
    requires modality.event.frontoffice.eventheader;
    requires webfx.extras.controlfactory;
    requires webfx.extras.panes;
    requires webfx.extras.util.control;
    requires webfx.kit.util;
    requires webfx.platform.async;
    requires webfx.platform.console;
    requires webfx.platform.service;
    requires webfx.platform.uischeduler;
    requires webfx.platform.util;
    requires webfx.platform.windowhistory;
    requires webfx.stack.orm.domainmodel.activity;
    requires webfx.stack.orm.entity;
    requires webfx.stack.routing.router;
    requires webfx.stack.routing.uirouter;

    // Exported packages
    exports one.modality.event.frontoffice.activities.book;
    exports one.modality.event.frontoffice.activities.book.account;
    exports one.modality.event.frontoffice.activities.book.event;
    exports one.modality.event.frontoffice.activities.book.fx;

    // Used services
    uses one.modality.booking.frontoffice.bookingform.BookingFormProvider;

    // Provided services
    provides dev.webfx.stack.routing.uirouter.UiRoute with one.modality.event.frontoffice.activities.book.event.BookEventRouting.BookEventUiRoute;

}
// File managed by WebFX (DO NOT EDIT MANUALLY)

module modality.booking.backoffice.bookingeditor.volunteer.plugin {

    // Direct dependencies modules
    requires javafx.controls;
    requires javafx.graphics;
    requires modality.base.shared.entities;
    requires modality.base.shared.knownitems;
    requires modality.booking.backoffice.bookingeditor;
    requires modality.booking.client.scheduleditemsselector.box;
    requires modality.booking.client.workingbooking;
    requires modality.ecommerce.policy.service;
    requires webfx.extras.styles.bootstrap;
    requires webfx.kit.util;
    requires webfx.platform.util;
    requires webfx.platform.util.time;
    requires webfx.stack.orm.entity;

    // Exported packages
    exports one.modality.booking.backoffice.bookingeditor.volunteer;

    // Provided services
    provides one.modality.booking.backoffice.bookingeditor.spi.BookingEditorProvider with one.modality.booking.backoffice.bookingeditor.volunteer.VolunteerBookingEditorProvider;

}
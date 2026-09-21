// File managed by WebFX (DO NOT EDIT MANUALLY)

module modality.crm.server.person.plugin {

    // Direct dependencies modules
    requires modality.base.shared.entities;
    requires modality.crm.server.authn.gateway.shared;
    requires modality.crm.shared.authn;
    requires webfx.platform.async;
    requires webfx.platform.console;
    requires webfx.platform.util;
    requires webfx.stack.com.bus.call;
    requires webfx.stack.db.query;
    requires webfx.stack.db.submit;
    requires webfx.stack.orm.datasourcemodel.service;
    requires webfx.stack.session.state;

    // Exported packages
    exports one.modality.crm.server.person;

    // Provided services
    provides dev.webfx.stack.com.bus.call.spi.BusCallEndpoint with one.modality.crm.server.person.MergeDuplicatePersonsEndpoint, one.modality.crm.server.person.RevokeLinkEndpoint, one.modality.crm.server.person.CreateInvitationEndpoint, one.modality.crm.server.person.ApproveInvitationEndpoint, one.modality.crm.server.person.UpdatePersonDetailsEndpoint, one.modality.crm.server.person.AddMemberEndpoint, one.modality.crm.server.person.CreateAccountOwnerEndpoint;

}
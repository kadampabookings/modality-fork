package one.modality.base.shared.entities;

import one.modality.base.shared.entities.markers.EntityHasEvent;
import one.modality.base.shared.entities.markers.EntityHasEventType;
import one.modality.base.shared.entities.markers.EntityHasI18nFields;
import one.modality.base.shared.entities.markers.EntityHasName;
import one.modality.base.shared.entities.markers.EntityHasOrganization;
import one.modality.base.shared.entities.markers.EntityHasSite;

/**
 * @author Bruno Salmon
 */
public interface Letter extends
    //EntityHasIcon,
    EntityHasName,
    EntityHasOrganization,
    EntityHasEvent,
    // Scope columns (V0037): a letter may live at event / (site, eventType) / eventType /
    // (site, organization) / organization scope — narrowest wins, see docs/letter-scope-plan.md.
    EntityHasSite,
    EntityHasEventType,
    EntityHasI18nFields {
}
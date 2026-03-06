package one.modality.ecommerce.policy.service;

import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.Organization;

import java.time.LocalDate;

/**
 * @author Bruno Salmon
 */
public final class LoadPolicyArgument { // Note: converting it to a record is causing a GWT compilation error

    private final Object organizationPk;
    private final Object eventPk;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Object accommodationItemPk;

    public LoadPolicyArgument(Event event) {
        this(event.getPrimaryKey());
    }

    public LoadPolicyArgument(Object eventPk) {
        this(null, eventPk, null, null, null);
    }

    public LoadPolicyArgument(Organization organization, LocalDate startDate, LocalDate endDate) {
        this(organization.getPrimaryKey(), startDate, endDate);
    }

    public LoadPolicyArgument(Object organizationPk, LocalDate startDate, LocalDate endDate) {
        this(organizationPk, null, startDate, endDate, null);
    }

    public LoadPolicyArgument(Object organizationPk, Object eventPk, LocalDate startDate, LocalDate endDate) {
        this(organizationPk, eventPk, startDate, endDate, null);
    }

    public LoadPolicyArgument(Object organizationPk, Object eventPk, LocalDate startDate, LocalDate endDate, Object accommodationItemPk) {
        this.endDate = endDate;
        this.eventPk = eventPk;
        this.organizationPk = organizationPk;
        this.startDate = startDate;
        this.accommodationItemPk = accommodationItemPk;
    }

    public Object getOrganizationPk() {
        return organizationPk;
    }

    public Object getEventPk() {
        return eventPk;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public Object getAccommodationItemPk() {
        return accommodationItemPk;
    }
}

package one.modality.hotel.backoffice.accommodation;

import one.modality.base.shared.entities.ResourceConfiguration;

import java.time.LocalDate;

/**
 * A synthetic per-(resource configuration, date) cell for the accommodation Gantt, computed on the fly
 * from {@link ResourceConfiguration} + the booking occupancy — replacing the former scheduled_resource
 * row. One instance per room per day; consecutive identical instances (same config + remaining) are
 * grouped into bars by TimeBarUtil (see {@code ResourceDayGantt}).
 *
 * @author Bruno Salmon
 */
public final class ResourceDay {

    private final ResourceConfiguration configuration;
    private final LocalDate date;
    private final int max;
    private final int booked;
    private final boolean online;

    public ResourceDay(ResourceConfiguration configuration, LocalDate date, int max, int booked, boolean online) {
        this.configuration = configuration;
        this.date = date;
        this.max = max;
        this.booked = booked;
        this.online = online;
    }

    public ResourceConfiguration getConfiguration() {
        return configuration;
    }

    public LocalDate getDate() {
        return date;
    }

    public int getMax() {
        return max;
    }

    public int getBooked() {
        return booked;
    }

    public boolean isOnline() {
        return online;
    }
}

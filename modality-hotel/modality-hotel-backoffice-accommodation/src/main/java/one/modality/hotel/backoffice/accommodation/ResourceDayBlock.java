package one.modality.hotel.backoffice.accommodation;

import one.modality.base.shared.entities.ResourceConfiguration;

/**
 * @author Bruno Salmon
 */
public final class ResourceDayBlock implements AccommodationBlock {
    private final ResourceConfiguration resourceConfiguration;
    private final boolean available;
    private final boolean online;
    private final int remaining;

    public ResourceDayBlock(ResourceDay resourceDay) {
        resourceConfiguration = resourceDay.getConfiguration();
        // Per-date availability override dropped (was always true in scheduled_resource).
        available = true;
        online = resourceDay.isOnline();
        remaining = resourceDay.getMax() - resourceDay.getBooked();
    }

    @Override
    public ResourceConfiguration getRoomConfiguration() {
        return resourceConfiguration;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isOnline() {
        return online;
    }

    public int getRemaining() {
        return remaining;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ResourceDayBlock that = (ResourceDayBlock) o;

        if (available != that.available) return false;
        if (online != that.online) return false;
        if (remaining != that.remaining) return false;
        return resourceConfiguration.equals(that.resourceConfiguration);
    }

    @Override
    public int hashCode() {
        int result = resourceConfiguration.hashCode();
        result = 31 * result + (available ? 1 : 0);
        result = 31 * result + (online ? 1 : 0);
        result = 31 * result + remaining;
        return result;
    }
}

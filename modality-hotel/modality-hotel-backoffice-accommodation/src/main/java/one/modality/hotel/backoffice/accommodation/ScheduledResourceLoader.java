package one.modality.hotel.backoffice.accommodation;

import dev.webfx.kit.util.properties.FXProperties;
import dev.webfx.kit.util.properties.ObservableLists;
import dev.webfx.stack.orm.reactive.entities.dql_to_entities.ReactiveEntitiesMapper;
import dev.webfx.stack.routing.activity.impl.elementals.activeproperty.HasActiveProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import one.modality.base.shared.entities.Attendance;
import one.modality.base.shared.entities.ResourceConfiguration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.webfx.stack.orm.dql.DqlStatement.where;

/**
 * Loads the accommodation Gantt data on the fly, with no dependency on the scheduled_resource table.
 * Two reactive queries are combined:
 *  • the accommodation room configurations of the organization (ResourceConfiguration), and
 *  • a grouped attendance "occupancy" query (one row per config+date carrying a booked count).
 * Whenever either result, or the time window, changes, {@link #rebuildResourceDays()} regenerates one
 * {@link ResourceDay} per room per day in the window (the synthetic equivalent of the former
 * ScheduledResource rows), which the Gantt then groups into bars.
 */
public final class ScheduledResourceLoader {

    // The presentation model used by the logic code to query the server.
    private final AccommodationPresentationModel pm;
    // The two reactive entities mappers feeding the synthetic ResourceDay list.
    private ReactiveEntitiesMapper<ResourceConfiguration> configurationsRem;
    private ReactiveEntitiesMapper<Attendance> occupancyRem;
    // The accommodation room configurations of the organization:
    private final ObservableList<ResourceConfiguration> configurations = FXCollections.observableArrayList();
    // Grouped attendance rows (one per config+date with a "booked" count) for the time window:
    private final ObservableList<Attendance> occupancy = FXCollections.observableArrayList();
    // The computed per-(config, date) cells consumed by the Gantt:
    private final ObservableList<ResourceDay> resourceDays = FXCollections.observableArrayList();

    // Workaround for a WebFX push notification issue that happens when several identical reactive entities mappers (ie
    // sending the exact same query and parameters to the server) run on the same client => the issue is that the push
    // notifications are sent to only 1 instance at a time. The workaround is to keep a single instance of the loader.
    // TODO: remove this workaround when the WebFX push notification issue is fixed
    private static ScheduledResourceLoader INSTANCE;
    private ObservableValue<Boolean> activeProperty;
    private boolean started;
    public static ScheduledResourceLoader getOrCreate(AccommodationPresentationModel pm) {
        // Creating the instance on first call only (assuming the presentation model is identical on subsequent calls)
        if (INSTANCE == null)
            INSTANCE = new ScheduledResourceLoader(pm);
        return INSTANCE;
    }

    private ScheduledResourceLoader(AccommodationPresentationModel pm) {
        this.pm = pm;
    }

    public ObservableList<ResourceDay> getResourceDays() {
        return resourceDays;
    }

    public void startLogic(Object mixin) { // may be called several times with different mixins (due to workaround)
        // Updating the active property with a OR => mixin1.active || mixin2.active || mixin3.active ...
        if (mixin instanceof HasActiveProperty) {
            ObservableValue<Boolean> ap = ((HasActiveProperty) mixin).activeProperty();
            if (activeProperty == null)
                activeProperty = ap;
            else
                activeProperty = FXProperties.combine(activeProperty, ap, (a1, a2) -> a1 || a2);
        }
        if (!started) { // first call
            started = true;
            // Query 1: the organization's accommodation room configurations (the Gantt's rooms).
            configurationsRem = ReactiveEntitiesMapper.<ResourceConfiguration>createPushReactiveChain(mixin)
                    .always( // language=JSON5
                        "{class: 'ResourceConfiguration', alias: 'rc', fields: 'max,online,startDate,endDate,name,item.name'}")
                    .ifNotNullOtherwiseEmpty(pm.organizationIdProperty(), o -> where("resource.site.organization=$1 and item.family.code='acco'", o))
                    .storeEntitiesInto(configurations)
                    .start();

            // Query 2: booking occupancy grouped per (config, date) — the booked count per room per day.
            occupancyRem = ReactiveEntitiesMapper.<Attendance>createPushReactiveChain(mixin)
                    .always( // language=JSON5
                        "{class: 'Attendance', alias: 'a', fields: 'documentLine.resourceConfiguration,date,count(1) as booked', where: 'present and !documentLine.cancelled and documentLine.resourceConfiguration.item.family.code=`acco`', groupBy: 'documentLine.resourceConfiguration,date'}")
                    .ifNotNullOtherwiseEmpty(pm.organizationIdProperty(), o -> where("documentLine.resourceConfiguration.resource.site.organization=$1", o))
                    .always(pm.timeWindowStartProperty(), startDate -> where("a.date >= $1", startDate))
                    .always(pm.timeWindowEndProperty(),   endDate   -> where("a.date <= $1", endDate))
                    .storeEntitiesInto(occupancy)
                    .start();

            // Regenerate the per-day cells whenever the rooms, the occupancy, or the time window change.
            ObservableLists.runOnListChange(this::rebuildResourceDays, configurations);
            ObservableLists.runOnListChange(this::rebuildResourceDays, occupancy);
            FXProperties.runOnPropertiesChange(this::rebuildResourceDays, pm.timeWindowStartProperty(), pm.timeWindowEndProperty());
        } else if (activeProperty != null) { // subsequent calls
            configurationsRem.bindActivePropertyTo(activeProperty);
            occupancyRem.bindActivePropertyTo(activeProperty);
        }
    }

    private void rebuildResourceDays() {
        LocalDate start = pm.timeWindowStartProperty().getValue();
        LocalDate end = pm.timeWindowEndProperty().getValue();
        if (start == null || end == null || start.isAfter(end)) {
            resourceDays.clear();
            return;
        }
        // Occupancy map: "configPk|date" -> booked count (from the grouped attendance rows).
        Map<String, Integer> bookedByConfigDate = new HashMap<>();
        for (Attendance a : occupancy) {
            ResourceConfiguration rc = a.getDocumentLine() == null ? null : a.getDocumentLine().getResourceConfiguration();
            LocalDate date = a.getDate();
            if (rc == null || date == null)
                continue;
            bookedByConfigDate.put(occupancyKey(rc.getPrimaryKey(), date), a.getIntegerFieldValue("booked"));
        }
        // One ResourceDay per room per day in the window (where the config's date range covers the day).
        List<ResourceDay> days = new ArrayList<>();
        for (ResourceConfiguration rc : configurations) {
            Integer maxValue = rc.getMax();
            int max = maxValue == null ? 0 : maxValue;
            boolean online = Boolean.TRUE.equals(rc.isOnline());
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                if (!configurationCoversDate(rc, date))
                    continue;
                Integer booked = bookedByConfigDate.get(occupancyKey(rc.getPrimaryKey(), date));
                days.add(new ResourceDay(rc, date, max, booked == null ? 0 : booked, online));
            }
        }
        resourceDays.setAll(days);
    }

    private static String occupancyKey(Object configPk, LocalDate date) {
        return configPk + "|" + date;
    }

    /**
     * A null startDate/endDate is open-ended. Global configs are date-scoped (they start/stop over time);
     * event configs carry no dates so they are always considered applicable here (no event-vs-global dedup,
     * matching the React back-office's "Option 2").
     */
    static boolean configurationCoversDate(ResourceConfiguration rc, LocalDate date) {
        LocalDate start = rc.getStartDate();
        if (start != null && start.isAfter(date))
            return false;
        LocalDate end = rc.getEndDate();
        return end == null || !end.isBefore(date);
    }
}

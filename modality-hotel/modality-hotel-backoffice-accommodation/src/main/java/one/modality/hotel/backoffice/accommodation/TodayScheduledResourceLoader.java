package one.modality.hotel.backoffice.accommodation;

import dev.webfx.kit.util.properties.FXProperties;
import dev.webfx.kit.util.properties.ObservableLists;
import dev.webfx.stack.orm.reactive.entities.dql_to_entities.ReactiveEntitiesMapper;
import dev.webfx.stack.routing.activity.impl.elementals.activeproperty.HasActiveProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import one.modality.base.client.gantt.fx.today.FXToday;
import one.modality.base.shared.entities.Attendance;
import one.modality.base.shared.entities.ResourceConfiguration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.webfx.stack.orm.dql.DqlStatement.where;

/**
 * Loads today's accommodation status on the fly (no scheduled_resource): the organization's
 * accommodation room configurations + a grouped attendance occupancy count for today, combined into one
 * {@link ResourceDay} per room (dated today) carrying its booked count.
 */
public final class TodayScheduledResourceLoader {

    // The presentation model used by the logic code to query the server.
    private final AccommodationPresentationModel pm;
    private ReactiveEntitiesMapper<ResourceConfiguration> configurationsRem;
    private ReactiveEntitiesMapper<Attendance> occupancyRem;
    // The accommodation room configurations of the organization:
    private final ObservableList<ResourceConfiguration> configurations = FXCollections.observableArrayList();
    // Grouped attendance rows (one per config with a "booked" count) for today:
    private final ObservableList<Attendance> occupancy = FXCollections.observableArrayList();
    // The computed per-room cells (dated today):
    private final ObservableList<ResourceDay> todayResourceDays = FXCollections.observableArrayList();

    // Workaround for a WebFX push notification issue that happens when several identical reactive entities mappers (ie
    // sending the exact same query and parameters to the server) run on the same client => the issue is that the push
    // notifications are sent to only 1 instance at a time. The workaround is to keep a single instance of the loader.
    // TODO: remove this workaround when the WebFX push notification issue is fixed
    private static TodayScheduledResourceLoader INSTANCE;
    private ObservableValue<Boolean> activeProperty;
    private boolean started;
    public static TodayScheduledResourceLoader getOrCreate(AccommodationPresentationModel pm) {
        // Creating the instance on first call only (assuming the presentation model is identical on subsequent calls)
        if (INSTANCE == null)
            INSTANCE = new TodayScheduledResourceLoader(pm);
        return INSTANCE;
    }

    private TodayScheduledResourceLoader(AccommodationPresentationModel pm) {
        this.pm = pm;
    }

    public ObservableList<ResourceDay> getTodayResourceDays() {
        return todayResourceDays;
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
            // The organization's accommodation room configurations.
            configurationsRem = ReactiveEntitiesMapper.<ResourceConfiguration>createPushReactiveChain(mixin)
                    .always( // language=JSON5
                        "{class: 'ResourceConfiguration', alias: 'rc', fields: 'max,online,startDate,endDate'}")
                    .ifNotNullOtherwiseEmpty(pm.organizationIdProperty(), o -> where("resource.site.organization=$1 and item.family.code='acco'", o))
                    .storeEntitiesInto(configurations)
                    .setResultCacheEntry("modality/hotel/accommodation/today-room-configurations")
                    .start();

            // Today's booking occupancy grouped per config.
            occupancyRem = ReactiveEntitiesMapper.<Attendance>createPushReactiveChain(mixin)
                    .always( // language=JSON5
                        "{class: 'Attendance', alias: 'a', fields: 'documentLine.resourceConfiguration,count(1) as booked', where: 'present and !documentLine.cancelled and documentLine.resourceConfiguration.item.family.code=`acco`', groupBy: 'documentLine.resourceConfiguration'}")
                    .ifNotNullOtherwiseEmpty(pm.organizationIdProperty(), o -> where("documentLine.resourceConfiguration.resource.site.organization=$1", o))
                    .always(FXToday.todayProperty(), today -> where("a.date = $1", today))
                    .storeEntitiesInto(occupancy)
                    .start();

            ObservableLists.runOnListChange(this::rebuildResourceDays, configurations);
            ObservableLists.runOnListChange(this::rebuildResourceDays, occupancy);
            FXProperties.runOnPropertyChange(this::rebuildResourceDays, FXToday.todayProperty());
        } else if (activeProperty != null) { // subsequent calls
            configurationsRem.bindActivePropertyTo(activeProperty);
            occupancyRem.bindActivePropertyTo(activeProperty);
        }
    }

    private void rebuildResourceDays() {
        LocalDate today = FXToday.getToday();
        if (today == null) {
            todayResourceDays.clear();
            return;
        }
        // Occupancy map: configPk -> booked count today (from the grouped attendance rows).
        Map<Object, Integer> bookedByConfig = new HashMap<>();
        for (Attendance a : occupancy) {
            ResourceConfiguration rc = a.getDocumentLine() == null ? null : a.getDocumentLine().getResourceConfiguration();
            if (rc == null)
                continue;
            bookedByConfig.put(rc.getPrimaryKey(), a.getIntegerFieldValue("booked"));
        }
        // One ResourceDay (dated today) per room whose config range covers today.
        List<ResourceDay> days = new ArrayList<>();
        for (ResourceConfiguration rc : configurations) {
            if (!ScheduledResourceLoader.configurationCoversDate(rc, today))
                continue;
            Integer maxValue = rc.getMax();
            int max = maxValue == null ? 0 : maxValue;
            Integer booked = bookedByConfig.get(rc.getPrimaryKey());
            days.add(new ResourceDay(rc, today, max, booked == null ? 0 : booked, Boolean.TRUE.equals(rc.isOnline())));
        }
        todayResourceDays.setAll(days);
    }
}

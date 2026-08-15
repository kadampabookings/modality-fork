package one.modality.base.shared.entities.util;

import dev.webfx.platform.util.Booleans;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.platform.util.time.Times;
import dev.webfx.stack.orm.entity.Entities;
import one.modality.base.shared.entities.*;
import one.modality.base.shared.entities.markers.EntityHasItem;
import one.modality.base.shared.knownitems.KnownItemFamily;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Bruno Salmon
 */
public final class ScheduledItems {

    public static boolean isOfFamily(ScheduledItem scheduledItem, KnownItemFamily family) {
        return Items.isOfFamily(scheduledItem, family);
    }

    public static boolean hasResourceManagement(ScheduledItem scheduledItem) {
        return scheduledItem.isResource();
    }

    public static boolean hasResourceManagement(List<ScheduledItem> scheduledItems) {
        return Collections.anyMatch(scheduledItems, ScheduledItems::hasResourceManagement);
    }

    public static Stream<ScheduledItem> filterFamily(Stream<ScheduledItem> scheduledItems, KnownItemFamily family) {
        return scheduledItems.filter(scheduledItem -> isOfFamily(scheduledItem, family));
    }

    public static List<ScheduledItem> filterFamily(List<ScheduledItem> scheduledItems, KnownItemFamily family) {
        return Collections.filter(scheduledItems, scheduledItem -> isOfFamily(scheduledItem, family));
    }

    public static Stream<ScheduledItem> filterNotCancelled(Stream<ScheduledItem> scheduledItems) {
        return scheduledItems.filter(scheduledItem -> Booleans.isNotTrue(scheduledItem.isCancelled()));
    }

    public static List<ScheduledItem> filterNotCancelled(List<ScheduledItem> scheduledItems) {
        return Collections.filter(scheduledItems, scheduledItem -> Booleans.isNotTrue(scheduledItem.isCancelled()));
    }

    public static List<ScheduledItem> filterSiteItem(List<ScheduledItem> scheduledItems, Site site, Item item) {
        return Collections.filter(scheduledItems, scheduledItem -> Entities.sameId(scheduledItem.getSite(), site) && Entities.sameId(scheduledItem.getItem(), item));
    }

    public static boolean isInPeriod(ScheduledItem scheduledItem, Period period) {
        LocalDate periodStartDate = period.getStartDate();
        LocalDate periodEndDate = period.getEndDate();
        LocalDate date = scheduledItem.getDate();
        if (!Times.isBetween(date, periodStartDate, periodEndDate))
            return false;
        if (Objects.equals(date, periodStartDate)) {
            LocalTime periodStartTime = period.getStartTime();
            LocalTime sessionStartTime = getSessionStartTimeOrMin(scheduledItem);
            if (sessionStartTime.isBefore(periodStartTime))
                return false;
        }
        if (Objects.equals(date, periodEndDate)) {
            LocalTime periodEndTime = period.getEndTime();
            LocalTime sessionEndTime = getSessionEndTimeOrMax(scheduledItem);
            if (sessionEndTime.isAfter(periodEndTime))
                return false;
        }
        return true;
    }

    public static List<ScheduledItem> filterOverPeriod(List<ScheduledItem> scheduledItems, Period period) {
        return Collections.filter(scheduledItems, scheduledItem -> isInPeriod(scheduledItem, period));
    }

    public static List<ScheduledItem> filterSiteItemOverPeriod(List<ScheduledItem> scheduledItems, Site site, Item item, Period period) {
        return filterOverPeriod(filterSiteItem(scheduledItems, site, item), period);
    }

    public static Map<Item, List<ScheduledItem>> groupScheduledItemsByItems(Stream<ScheduledItem> scheduledItems) {
        return scheduledItems
            .collect(Collectors.groupingBy(EntityHasItem::getItem,
                () -> new TreeMap<>(Comparator.comparing(Item::getOrd)),
                Collectors.toList()));
    }

    public static Map<Item, List<ScheduledItem>> groupScheduledItemsByAudioRecordingItems(Stream<ScheduledItem> scheduledItems) {
        return groupScheduledItemsByItems(filterFamily(scheduledItems, KnownItemFamily.AUDIO_RECORDING));
    }

    public static List<ScheduledItem> fromAttendances(List<Attendance> attendances) {
        return Collections.map(attendances, Attendance::getScheduledItem);
    }

    public static List<LocalDate> toDates(List<ScheduledItem> scheduledItems) {
        return Collections.map(scheduledItems, ScheduledItem::getDate);
    }

    /**
     * A session's effective start time, as a DQL expression — the four levels it can live at, in
     * precedence order. Used both to evaluate the time client-side ({@link #getSessionStartTime})
     * and to sort session lists server-side ({@link #SESSION_ORDER_BY_DQL}); keeping one string for
     * both is what stops a query sorting on a level the UI doesn't display.
     *
     * <p>The {@code ?} marks each FK hop as an OUTER join. It is omitted in the evaluate() form
     * (which walks loaded entities, not SQL) but required in a query — ordering through a bare
     * nullable FK inner-joins and silently drops the rows whose FK is null.</p>
     */
    public static final String SESSION_START_TIME_DQL =
        "coalesce(startTime, timeline?.startTime, programScheduledItem?.startTime, programScheduledItem?.timeline?.startTime)";

    /**
     * Canonical {@code order by} clause for a chronological session list: day first, then the
     * effective start time within the day, then {@code id} as a deterministic tie-break.
     *
     * <p>A plain {@code order by date, a, b} is NOT equivalent: Postgres sorts ASC with NULLS LAST,
     * so every row carrying {@code a} lands before every row carrying only {@code b}, whatever the
     * clock says. Only {@code coalesce} interleaves them by actual time.</p>
     *
     * <p>Without the trailing {@code id}, two sessions sharing a sort key come back in whatever
     * order the plan happens to produce — unstable between runs, which reads to users as a randomly
     * shuffled list.</p>
     */
    public static final String SESSION_ORDER_BY_DQL = " order by date, " + SESSION_START_TIME_DQL + ", id";

    public static LocalTime getSessionStartTime(ScheduledItem scheduledItem) {
        return scheduledItem.evaluate("coalesce(startTime, timeline.startTime, programScheduledItem.startTime, programScheduledItem.timeline.startTime)");
    }

    public static LocalTime getSessionEndTime(ScheduledItem scheduledItem) {
        return scheduledItem.evaluate("coalesce(endTime, timeline.endTime, programScheduledItem.endTime, programScheduledItem.timeline.endTime)");
    }

    public static LocalTime getSessionStartTimeOrMin(ScheduledItem scheduledItem) {
        LocalTime sessionStartTime = getSessionStartTime(scheduledItem);
        return sessionStartTime != null ? sessionStartTime : LocalTime.MIN;
    }

    public static LocalTime getSessionEndTimeOrMax(ScheduledItem scheduledItem) {
        LocalTime sessionEndTime = getSessionEndTime(scheduledItem);
        return sessionEndTime != null ? sessionEndTime : LocalTime.MAX;
    }

}

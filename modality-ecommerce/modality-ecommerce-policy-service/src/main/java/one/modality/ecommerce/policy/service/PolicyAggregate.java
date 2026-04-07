package one.modality.ecommerce.policy.service;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Booleans;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.orm.dql.sqlcompiler.mapping.QueryRowToEntityMapping;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityList;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.query_result_to_entities.QueryResultToEntitiesMapper;
import one.modality.base.shared.entities.*;
import one.modality.base.shared.entities.util.Rates;
import one.modality.base.shared.entities.util.ScheduledItems;
import one.modality.base.shared.knownitems.KnownItemFamily;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Bruno Salmon
 */
public final class PolicyAggregate {

    // Fields intended for serialisation
    private final QueryResult eventQueryResult;
    private final QueryResult scheduledItemsQueryResult;
    private final QueryResult scheduledBoundariesQueryResult;
    private final QueryResult eventPartsQueryResult;
    private final QueryResult eventSelectionsQueryResult;
    private final QueryResult eventPhasesQueryResult;
    private final QueryResult eventPhaseCoveragesQueryResult;
    private final QueryResult itemFamilyPoliciesQueryResult;
    private final QueryResult itemPoliciesQueryResult;
    private final QueryResult ratesQueryResult;
    private final long creationTimeMillis = System.currentTimeMillis();

    // Fields intended for application code
    private Event event;
    private Double secondsToOpeningDateAtLoadingTime;
    private Double secondsToBookingProcessStartAtLoadingTime;
    private EntityStore entityStore;
    private EntityList<ScheduledItem> scheduledItems;
    private EntityList<ScheduledBoundary> scheduledBoundaries;
    private EntityList<EventPart> eventParts;
    private EntityList<EventSelection> eventSelections;
    private EntityList<EventPhase> eventPhases;
    private EntityList<EventPhaseCoverage> phaseCoverages;
    private EntityList<ItemFamilyPolicy> itemFamilyPolicies;
    private EntityList<ItemPolicy> itemPolicies;
    private EntityList<Rate> rates;

    public PolicyAggregate(
            QueryResult eventQueryResult,
            QueryResult scheduledItemsQueryResult,
            QueryResult scheduledBoundariesQueryResult,
            QueryResult eventPartsQueryResult,
            QueryResult eventSelectionsQueryResult,
            QueryResult eventPhasesQueryResult,
            QueryResult phaseCoveragesQueryResult,
            QueryResult itemFamilyPoliciesQueryResult,
            QueryResult itemPoliciesQueryResult,
            QueryResult ratesQueryResult) {
        this.eventQueryResult = eventQueryResult;
        this.scheduledItemsQueryResult = scheduledItemsQueryResult;
        this.scheduledBoundariesQueryResult = scheduledBoundariesQueryResult;
        this.eventPartsQueryResult = eventPartsQueryResult;
        this.eventSelectionsQueryResult = eventSelectionsQueryResult;
        this.eventPhasesQueryResult = eventPhasesQueryResult;
        this.eventPhaseCoveragesQueryResult = phaseCoveragesQueryResult;
        this.ratesQueryResult = ratesQueryResult;
        this.itemFamilyPoliciesQueryResult = itemFamilyPoliciesQueryResult;
        this.itemPoliciesQueryResult = itemPoliciesQueryResult;
    }

    public void rebuildEntities(Event event) {
        rebuildEntities(EntityStore.createAbove(event.getStore()));
    }

    public void rebuildEntities(EntityStore entityStore) {
        this.entityStore = entityStore;
        QueryRowToEntityMapping queryMapping = (QueryRowToEntityMapping) eventQueryResult.getEntityMapping();
        // The event returned by PolicyAggregate is a different instance from the passes
        // event and may contain some
        // additional fields such as termsUrlEn
        this.event = Collections.first(QueryResultToEntitiesMapper.mapQueryResultToEntities(eventQueryResult,
                queryMapping, entityStore, "events"));
        secondsToOpeningDateAtLoadingTime = this.event.getDoubleFieldValue(Event.secondsToOpeningDateAtLoadingTime);
        secondsToBookingProcessStartAtLoadingTime = this.event
                .getDoubleFieldValue(Event.secondsToBookingProcessStartAtLoadingTime);
        queryMapping = (QueryRowToEntityMapping) scheduledItemsQueryResult.getEntityMapping();
        scheduledItems = QueryResultToEntitiesMapper.mapQueryResultToEntities(scheduledItemsQueryResult, queryMapping,
                entityStore, "scheduledItems");
        queryMapping = (QueryRowToEntityMapping) scheduledBoundariesQueryResult.getEntityMapping();
        scheduledBoundaries = QueryResultToEntitiesMapper.mapQueryResultToEntities(scheduledBoundariesQueryResult,
                queryMapping, entityStore, "scheduledBoundaries");
        queryMapping = (QueryRowToEntityMapping) eventPartsQueryResult.getEntityMapping();
        eventParts = QueryResultToEntitiesMapper.mapQueryResultToEntities(eventPartsQueryResult, queryMapping,
                entityStore, "eventParts");
        queryMapping = (QueryRowToEntityMapping) eventSelectionsQueryResult.getEntityMapping();
        eventSelections = QueryResultToEntitiesMapper.mapQueryResultToEntities(eventSelectionsQueryResult, queryMapping,
                entityStore, "eventSelections");
        queryMapping = (QueryRowToEntityMapping) eventPhasesQueryResult.getEntityMapping();
        eventPhases = QueryResultToEntitiesMapper.mapQueryResultToEntities(eventPhasesQueryResult, queryMapping,
                entityStore, "eventPhases");
        queryMapping = (QueryRowToEntityMapping) eventPhaseCoveragesQueryResult.getEntityMapping();
        phaseCoverages = QueryResultToEntitiesMapper.mapQueryResultToEntities(eventPhaseCoveragesQueryResult,
                queryMapping, entityStore, "phaseCoverages");
        queryMapping = (QueryRowToEntityMapping) itemFamilyPoliciesQueryResult.getEntityMapping();
        itemFamilyPolicies = QueryResultToEntitiesMapper.mapQueryResultToEntities(itemFamilyPoliciesQueryResult,
                queryMapping, entityStore, "itemFamilyPolicies");
        queryMapping = (QueryRowToEntityMapping) itemPoliciesQueryResult.getEntityMapping();
        itemPolicies = QueryResultToEntitiesMapper.mapQueryResultToEntities(itemPoliciesQueryResult, queryMapping,
                entityStore, "itemPolicies");
        queryMapping = (QueryRowToEntityMapping) ratesQueryResult.getEntityMapping();
        rates = QueryResultToEntitiesMapper.mapQueryResultToEntities(ratesQueryResult, queryMapping, entityStore,
                "rates");
    }

    public Future<Void> reloadAvailabilities() {
        return PolicyService.loadAvailabilities(new LoadPolicyArgument(event))
                .onSuccess(scheduledItemsQueryResult -> {
                    QueryRowToEntityMapping queryMapping = (QueryRowToEntityMapping) scheduledItemsQueryResult
                            .getEntityMapping();
                    scheduledItems = QueryResultToEntitiesMapper.mapQueryResultToEntities(scheduledItemsQueryResult,
                            queryMapping, entityStore, "scheduledItems");
                })
                .mapEmpty();
    }

    public EntityStore getEntityStore() {
        return entityStore;
    }

    public Event getEvent() {
        return event;
    }

    public Double getSecondsToOpeningDate() {
        return getSecondsNow(secondsToOpeningDateAtLoadingTime);
    }

    public Double getSecondsToBookingProcessStart() {
        return getSecondsNow(secondsToBookingProcessStartAtLoadingTime);
    }

    private Double getSecondsNow(Double secondsAtLoadingTime) {
        if (secondsAtLoadingTime == null)
            return null;
        return secondsAtLoadingTime - (System.currentTimeMillis() - creationTimeMillis) / 1000.0;
    }

    public List<ScheduledItem> getScheduledItems() {
        return scheduledItems;
    }

    public Stream<ScheduledItem> getScheduledItemsStream() {
        return getScheduledItems().stream();
    }

    public List<ScheduledItem> filterScheduledItemsOfFamily(KnownItemFamily knownItemFamily) {
        return ScheduledItems.filterFamily(getScheduledItems(), knownItemFamily);
    }

    public List<ScheduledItem> filterTeachingScheduledItems() {
        return filterScheduledItemsOfFamily(KnownItemFamily.TEACHING);
    }

    public List<ScheduledItem> filterAccommodationScheduledItems() {
        return filterScheduledItemsOfFamily(KnownItemFamily.ACCOMMODATION);
    }

    public List<ScheduledItem> filterMealsScheduledItems() {
        return filterScheduledItemsOfFamily(KnownItemFamily.MEALS);
    }

    public List<ScheduledItem> filterCeremonyScheduledItems() {
        return filterScheduledItemsOfFamily(KnownItemFamily.CEREMONY);
    }

    public ScheduledItem getCeremonyScheduledItem() {
        return Collections.first(filterCeremonyScheduledItems());
    }

    public List<ScheduledItem> filterExamScheduledItems() {
        return filterScheduledItemsOfFamily(KnownItemFamily.EXAM);
    }

    public ScheduledItem getExamScheduledItem() {
        return Collections.first(filterExamScheduledItems());
    }

    public EntityList<ScheduledBoundary> getScheduledBoundaries() {
        return scheduledBoundaries;
    }

    public EntityList<EventPart> getEventParts() {
        return eventParts;
    }

    public EntityList<EventSelection> getEventSelections() {
        return eventSelections;
    }

    public EntityList<EventPhase> getEventPhases() {
        return eventPhases;
    }

    public EntityList<EventPhaseCoverage> getPhaseCoverages() {
        return phaseCoverages;
    }

    public EntityList<ItemFamilyPolicy> getItemFamilyPolicies() {
        return itemFamilyPolicies;
    }

    public EntityList<ItemPolicy> getItemPolicies() {
        return itemPolicies;
    }

    public EventPart getEarlyArrivalPart() {
        // Should be listed first
        EventPart firstPart = Collections.first(getEventParts());
        if (firstPart == null)
            return null;
        if (firstPart.getStartDate().isBefore(getEvent().getStartDate()))
            return firstPart;
        for (EventSelection eventSelection : getEventSelections()) {
            if (eventSelection.getParts().contains(firstPart))
                return null;
        }
        return firstPart;
    }

    public EventPart getLateDeparturePart() {
        // Should be listed last
        EventPart lastPart = Collections.last(getEventParts());
        if (lastPart == null)
            return null;
        if (lastPart.getEndDate().isAfter(getEvent().getEndDate()))
            return lastPart;
        for (EventSelection eventSelection : getEventSelections()) {
            if (eventSelection.getParts().contains(lastPart))
                return null;
        }
        return lastPart;
    }

    /**
     * Gets the ItemPolicy for a specific Item.
     *
     * @param item the Item to find the policy for
     * @return the ItemPolicy for the item, or null if none exists
     */
    public ItemPolicy getItemPolicy(Item item) {
        return getItemPolicies().stream()
                .filter(ip -> Entities.samePrimaryKey(ip.getItem(), item))
                .findFirst().orElse(null);
    }

    public List<ItemPolicy> getDietItemPolicies() {
        return getItemPolicies().stream()
                .filter(ip -> ip.getItem().getItemFamilyType() == KnownItemFamily.DIET)
                .collect(Collectors.toList());
    }

    public List<ItemPolicy> getTranslationItemPolicies() {
        return getItemPolicies().stream()
                .filter(ip -> ip.getItem().getItemFamilyType() == KnownItemFamily.TRANSLATION)
                .collect(Collectors.toList());
    }

    public ItemPolicy getSharingAccommodationItemPolicy() {
        return getItemPolicies().stream()
                .filter(ip -> Booleans.isTrue(ip.getItem().isShare_mate())
                        && ip.getItem().getItemFamilyType() == KnownItemFamily.ACCOMMODATION)
                .findFirst().orElse(null);
    }

    public ItemFamilyPolicy getItemFamilyPolicy(KnownItemFamily knownItemFamily) {
        return getItemFamilyPolicies().stream()
                .filter(ifp -> ifp.getItemFamilyType() == knownItemFamily)
                .findFirst().orElse(null);
    }

    public List<EventPhaseCoverage> getAudioRecordingPhaseCoverages() {
        ItemFamilyPolicy audioRecordingPolicy = getItemFamilyPolicy(KnownItemFamily.AUDIO_RECORDING);
        if (audioRecordingPolicy == null)
            return Collections.emptyList();
        return audioRecordingPolicy.getEventPhaseCoverages();
    }

    public Map<Item, List<ScheduledItem>> groupScheduledItemsByAudioRecordingItems() {
        return ScheduledItems.groupScheduledItemsByAudioRecordingItems(getScheduledItemsStream());
    }

    private Timeline findMealsTimeline(LocalTime startsAfter, LocalTime startsBefore) {
        return scheduledItems.stream()
                .map(ScheduledItem::getTimeline)
                .distinct()
                .filter(timeline -> {
                    if (timeline == null)
                        return false;
                    Item item = timeline.getItem();
                    if (item == null
                            || !Entities.samePrimaryKey(item.getFamily(), KnownItemFamily.MEALS.getPrimaryKey()))
                        return false;
                    LocalTime startTime = timeline.getStartTime();
                    return startTime != null && (startsBefore == null || startTime.isBefore(startsBefore))
                            && (startsAfter == null || startTime.isAfter(startsAfter));
                }).findFirst()
                .orElse(null);
    }

    public Timeline getBreakfastTimeline() {
        return findMealsTimeline(null, LocalTime.of(10, 0));
    }

    public Timeline getLunchTimeline() {
        return findMealsTimeline(LocalTime.of(10, 0), LocalTime.of(15, 0));
    }

    public Timeline getDinnerTimeline() {
        return findMealsTimeline(LocalTime.of(15, 0), null);
    }

    public List<Rate> getRates() {
        return rates;
    }

    public Stream<Rate> getRatesStream() {
        return getRates().stream();
    }

    public List<Rate> getDailyRates() {
        return Rates.filterDailyRates(getRates());
    }

    public Stream<Rate> getDailyRatesStream() {
        return Rates.filterDailyRates(getRatesStream());
    }

    public Stream<Rate> getFixedRatesStream() {
        return Rates.filterFixedRates(getRatesStream());
    }

    public Rate getDailyRate() {
        List<Rate> dailyRates = getDailyRates();
        int dailyRatesCount = dailyRates.size();
        if (dailyRatesCount > 1) {
            Console.warn(
                    "PolicyAggregate.getDailyRate() is meant to be used with single daily rate policies, but this policy has "
                            + dailyRatesCount + " rates.");
        }
        return Collections.first(dailyRates);
    }

    public int getDailyRatePrice() {
        Rate dailyRate = getDailyRate();
        return dailyRate != null ? dailyRate.getPrice() : 0;
    }

    public Stream<Rate> filterRatesStreamOfSiteAndItem(Site site, Item item) {
        return Rates.filterRatesOfSiteAndItem(getRatesStream(), site, item);
    }

    public Stream<Rate> filterRatesStreamOfSiteAndItem(Site site, Item item, boolean perDay) {
        return perDay ? filterDailyRatesStreamOfSiteAndItem(site, item)
                : filterFixedRatesStreamOfSiteAndItem(site, item);
    }

    public Stream<Rate> filterDailyRatesStreamOfSiteAndItem(Site site, Item item) {
        return Rates.filterRatesOfSiteAndItem(getDailyRatesStream(), site, item);
    }

    public Stream<Rate> filterFixedRatesStreamOfSiteAndItem(Site site, Item item) {
        return Rates.filterRatesOfSiteAndItem(getFixedRatesStream(), site, item);
    }

    public Stream<Rate> filterRatesStreamOfSiteAndItemOnAtDateAndApplicableOverPeriod(Site site, Item item,
                                                                                      boolean perDay, LocalDateTime atDate, LocalDate startDate, LocalDate endDate) {
        return filterRatesStreamOfSiteAndItem(site, item, perDay)
                .filter(r -> Rates.isAndApplicableAtDateAndOverPeriod(r, atDate, startDate, endDate));
    }

    public Rate getScheduledItemDailyRateApplicableToday(ScheduledItem scheduledItem) {
        return getScheduledItemDailyRateApplicableAt(scheduledItem, LocalDateTime.now());
    }

    public Rate getScheduledItemDailyRateApplicableAt(ScheduledItem scheduledItem, LocalDateTime atDate) {
        return getSiteItemDailyRateAtDateOverPeriod(scheduledItem.getSite(), scheduledItem.getItem(), atDate, scheduledItem.getDate(),
                scheduledItem.getDate());
    }

    public Rate getSiteItemDailyRateAtDateOverPeriod(Site site, Item item, LocalDateTime atDate, LocalDate startDate, LocalDate endDate) {
        return filterRatesStreamOfSiteAndItemOnAtDateAndApplicableOverPeriod(site, item, true, atDate, startDate, endDate)
                .findFirst().orElse(null);
    }

    public boolean hasFacilityFees() {
        return Rates.hasFacilityFees(getRatesStream());
    }

        // The following methods are meant to be used for serialization, not by the
    // application code

    public QueryResult getEventQueryResult() {
        return eventQueryResult;
    }

    public QueryResult getScheduledItemsQueryResult() {
        return scheduledItemsQueryResult;
    }

    public QueryResult getScheduledBoundariesQueryResult() {
        return scheduledBoundariesQueryResult;
    }

    public QueryResult getEventPartsQueryResult() {
        return eventPartsQueryResult;
    }

    public QueryResult getEventSelectionsQueryResult() {
        return eventSelectionsQueryResult;
    }

    public QueryResult getEventPhasesQueryResult() {
        return eventPhasesQueryResult;
    }

    public QueryResult getEventPhaseCoveragesQueryResult() {
        return eventPhaseCoveragesQueryResult;
    }

    public QueryResult getItemFamilyPoliciesQueryResult() {
        return itemFamilyPoliciesQueryResult;
    }

    public QueryResult getItemPoliciesQueryResult() {
        return itemPoliciesQueryResult;
    }

    public QueryResult getRatesQueryResult() {
        return ratesQueryResult;
    }
}

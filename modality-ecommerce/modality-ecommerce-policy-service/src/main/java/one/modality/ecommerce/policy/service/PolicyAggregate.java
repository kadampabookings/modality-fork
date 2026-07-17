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

import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.impl.ThreadLocalEntityLoadingContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // Above lists as loaded, i.e. unioned across every scope matching the event; below lists after
    // cross-scope resolution, which is what application code sees. Rebuilt lazily, dropped on reload.
    private List<ItemFamilyPolicy> resolvedItemFamilyPolicies;
    private List<ItemPolicy> resolvedItemPolicies;

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
        resolvedItemFamilyPolicies = null;
        resolvedItemPolicies = null;
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

    public List<ItemFamilyPolicy> getItemFamilyPolicies() {
        if (resolvedItemFamilyPolicies == null)
            resolveScopes();
        return resolvedItemFamilyPolicies;
    }

    public List<ItemPolicy> getItemPolicies() {
        if (resolvedItemPolicies == null)
            resolveScopes();
        return resolvedItemPolicies;
    }

    // ------------------------------------------------------------------------
    // Cross-scope resolution
    // ------------------------------------------------------------------------

    // A policy hangs off a PolicyScope that is general (organization, optionally narrowed to a site),
    // eventType-scoped or event-scoped. The server's scope predicate is null-permissive, so for a
    // given event every matching scope's rows come back unioned together — an org-wide row and an
    // event-specific row for the same item are returned as siblings. Resolution collapses that union
    // down to what actually applies, and every accessor below goes through it.

    private static final int GENERAL_SCOPE_LEVEL = 1;
    private static final int EVENT_TYPE_SCOPE_LEVEL = 2;
    private static final int EVENT_SCOPE_LEVEL = 3;

    /**
     * Scope specificity — higher is narrower. Site is deliberately not ranked: it says which venue an
     * item belongs to (the booking forms read scope.getSite() as a payload) rather than how narrowly
     * the policy applies, and it has no defined order against eventType. A consequence worth knowing:
     * for an organization that declares policies across several sites, a narrow scope replacing a
     * family replaces it for all of them.
     */
    private static int scopeLevel(PolicyScope scope) {
        if (scope == null)
            return 0;
        if (scope.getEventId() != null)
            return EVENT_SCOPE_LEVEL;
        if (scope.getEventTypeId() != null)
            return EVENT_TYPE_SCOPE_LEVEL;
        return GENERAL_SCOPE_LEVEL;
    }

    private void resolveScopes() {
        // 1) One ItemFamilyPolicy per family, resolved FIELD BY FIELD along the scope chain: the
        // narrowest scope that declares a value wins, and whatever it leaves unset falls back to a
        // wider scope. Previously the narrowest row won wholesale, which meant an event-scoped row
        // tuning one field (say a minimum stay) silently discarded everything its organization had
        // said about the family — so avoiding per-item duplication just moved the duplication up to
        // the family level.
        Map<EntityId, List<ItemFamilyPolicy>> rowsByFamily = new LinkedHashMap<>();
        for (ItemFamilyPolicy ifp : itemFamilyPolicies) {
            EntityId familyId = ifp.getItemFamilyId();
            if (familyId == null)
                continue;
            rowsByFamily.computeIfAbsent(familyId, k -> new ArrayList<>()).add(ifp);
        }
        Map<EntityId, ItemFamilyPolicy> winnerByFamily = new LinkedHashMap<>();
        for (Map.Entry<EntityId, List<ItemFamilyPolicy>> familyRows : rowsByFamily.entrySet())
            winnerByFamily.put(familyRows.getKey(), mergeFamilyRowsAcrossScopes(familyRows.getValue()));

        // 2) Note which families are switched off at their winning scope. The policy row itself stays in
        // the resolved list: disabled has to remain observable so callers can tell "withdrawn" from
        // "never configured", which is a distinction a caller consulting getItemFamilyPolicy() needs to
        // make. Only its items are dropped, below.
        List<ItemFamilyPolicy> familyPolicies = new ArrayList<>();
        Set<EntityId> disabledFamilies = new HashSet<>();
        for (Map.Entry<EntityId, ItemFamilyPolicy> entry : winnerByFamily.entrySet()) {
            if (Booleans.isTrue(entry.getValue().isDisabled()))
                disabledFamilies.add(entry.getKey());
            familyPolicies.add(entry.getValue());
        }

        // 3) Item policies: drop the disabled families, then apply each family's replacement floor.
        // The floor is the level of the winning family policy when it declares replacesWiderScopes —
        // anything declared more widely than that stops applying. Without the flag the floor is 0 and
        // the sets merge, which is the historical behaviour. Survivors are grouped per item, keeping
        // EVERY scope's row: step 4 resolves across them. LinkedHashMap keeps the query's
        // family.ord/item.ord ordering, which callers rely on.
        Map<EntityId, List<ItemPolicy>> rowsByItem = new LinkedHashMap<>();
        for (ItemPolicy ip : itemPolicies) {
            Item item = ip.getItem();
            EntityId familyId = item == null ? null : item.getFamilyId();
            if (familyId != null && disabledFamilies.contains(familyId))
                continue;
            if (scopeLevel(ip.getScope()) < replacementFloor(winnerByFamily.get(familyId)))
                continue;
            EntityId itemId = ip.getItemId();
            if (itemId == null)
                continue;
            rowsByItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(ip);
        }

        // 4) One fully-resolved policy per item, across both specificity axes.
        List<ItemPolicy> itemPoliciesResolved = new ArrayList<>();
        for (List<ItemPolicy> itemRows : rowsByItem.values()) {
            Item item = itemRows.get(0).getItem();
            EntityId familyId = item == null ? null : item.getFamilyId();
            List<ItemFamilyPolicy> familyRows = familyId == null ? java.util.Collections.emptyList()
                : rowsByFamily.getOrDefault(familyId, java.util.Collections.emptyList());
            itemPoliciesResolved.add(resolveItemAcrossScopes(itemRows, familyRows));
        }

        resolvedItemFamilyPolicies = familyPolicies;
        resolvedItemPolicies = itemPoliciesResolved;
    }

    /**
     * The fields an ItemFamilyPolicy can supply a default for: those declared on BOTH entities where
     * the family's value means the same as the item's.
     *
     * Deliberately an explicit list rather than every shared field — scope and noticeLabel are on
     * both and must never cross over (the first would misreport which scope won, and a family notice
     * is not an item notice). Add a field here only when the family declaring it means "unless the
     * item says otherwise".
     */
    private static final String[] FAMILY_DEFAULTABLE_FIELDS = {
        ItemPolicy.applicableToInPerson, ItemPolicy.applicableToOnline,
        ItemPolicy.childAllowed, ItemPolicy.youngAdultAllowed, ItemPolicy.adultAllowed,
        ItemPolicy.minDay, ItemPolicy.wholeEvent,
        ItemPolicy.earlyAccommodationAllowed, ItemPolicy.lateAccommodationAllowed,
    };

    /**
     * Resolve one item into a single fully-resolved policy, across BOTH specificity axes at once:
     *
     *   item@event -> family@event -> item@eventType -> family@eventType -> item@general -> family@general
     *
     * Scope is the primary axis: anything set at a narrower scope beats anything set at a wider one,
     * whether it was said about the item or about its family. Within one scope the item wins, being
     * the more specific statement — which is how a dormitory keeps its own 7-night minimum against
     * its family's "whole event only" declared at that same scope.
     *
     * Resolving item-then-family instead (ignoring scope) reads naturally but is wrong here: an
     * organization-wide item value would silently beat an event's family rule, so declaring anything
     * on a narrow family would appear to do nothing until every wider item row was blanked by hand.
     *
     * Enriches the narrowest item row in place and returns it, so it keeps its id and scope (the
     * booking forms read scope.site off it) — see mergeFamilyRowsAcrossScopes for why that is safe.
     */
    private static ItemPolicy resolveItemAcrossScopes(List<ItemPolicy> itemRows, List<ItemFamilyPolicy> familyRows) {
        itemRows.sort((r1, r2) -> scopeLevel(r2.getScope()) - scopeLevel(r1.getScope()));
        ItemPolicy narrowest = itemRows.get(0);
        try (ThreadLocalEntityLoadingContext ignored = ThreadLocalEntityLoadingContext.open(true)) {
            // Everything the item itself says, narrowest scope first.
            for (int i = 1; i < itemRows.size(); i++) {
                ItemPolicy wider = itemRows.get(i);
                for (Object field : wider.getLoadedFields()) {
                    if (narrowest.getFieldValue(field) == null) {
                        Object widerValue = wider.getFieldValue(field);
                        if (widerValue != null)
                            narrowest.setFieldValue(field, widerValue);
                    }
                }
            }
            if (familyRows.isEmpty())
                return narrowest;
            // Then the family, but only where it outranks what the item said — a family at a
            // narrower scope beats an item value set wider.
            for (String field : FAMILY_DEFAULTABLE_FIELDS) {
                int itemLevel = 0;
                for (ItemPolicy row : itemRows)
                    if (row.getFieldValue(field) != null)
                        itemLevel = Math.max(itemLevel, scopeLevel(row.getScope()));
                ItemFamilyPolicy best = null;
                for (ItemFamilyPolicy row : familyRows) {
                    if (row.getFieldValue(field) == null || scopeLevel(row.getScope()) <= itemLevel)
                        continue;
                    if (best == null || scopeLevel(row.getScope()) > scopeLevel(best.getScope()))
                        best = row;
                }
                if (best != null)
                    narrowest.setFieldValue(field, best.getFieldValue(field));
            }
        }
        return narrowest;
    }

    /**
     * Resolve one family's rows — each matching scope declares at most one — into a single policy:
     * the narrowest scope's row, with every field it leaves null filled in from the next widest
     * scope that sets one. This is what lets an organization state a family's rules once and an
     * event override a single field without restating the rest.
     *
     * The returned instance IS the narrowest row, enriched in place. That is safe: the aggregate is
     * a read-only snapshot rebuilt on every load, into a store that rebuildEntities always layers
     * above the event's (a plain EntityStore, never an UpdateStore). The writes are nonetheless
     * wrapped in an entity-loading context — the same one QueryResultToEntitiesMapper uses when it
     * populates these very fields — so they could not register as modifications even if an
     * UpdateStore were ever passed to the public rebuildEntities(EntityStore). Keeping the narrowest
     * row's identity is what callers expect: getScope() still reports the scope that won.
     *
     * Fields that are NOT NULL in the database (disabled, replacesWiderScopes, applicableTo* and the
     * dayVisitor pair) can never read as null, so they always resolve to the narrowest row and never
     * inherit. That is exactly right for the two that are statements about their own scope rather
     * than inheritable values, and it makes this a no-op for the rest.
     */
    private static ItemFamilyPolicy mergeFamilyRowsAcrossScopes(List<ItemFamilyPolicy> familyRows) {
        if (familyRows.size() == 1)
            return familyRows.get(0);
        // Narrowest first. The sort is stable and the query orders by id, so rows at the same level
        // (two policies for one family at one scope level — a configuration error) keep the lowest
        // id, which is what the previous resolution picked.
        familyRows.sort((r1, r2) -> scopeLevel(r2.getScope()) - scopeLevel(r1.getScope()));
        ItemFamilyPolicy narrowest = familyRows.get(0);
        try (ThreadLocalEntityLoadingContext ignored = ThreadLocalEntityLoadingContext.open(true)) {
            for (int i = 1; i < familyRows.size(); i++) {
                ItemFamilyPolicy wider = familyRows.get(i);
                for (Object field : wider.getLoadedFields()) {
                    if (narrowest.getFieldValue(field) != null)
                        continue; // the narrower scope has spoken
                    Object widerValue = wider.getFieldValue(field);
                    if (widerValue != null)
                        narrowest.setFieldValue(field, widerValue);
                }
            }
        }
        return narrowest;
    }

    /** Item policies for the family are dropped below this level. 0 = keep them all (merge). */
    private static int replacementFloor(ItemFamilyPolicy winningFamilyPolicy) {
        if (winningFamilyPolicy == null || !Booleans.isTrue(winningFamilyPolicy.isReplacesWiderScopes()))
            return 0;
        return scopeLevel(winningFamilyPolicy.getScope());
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

    public List<ItemPolicy> getItemPolicies(KnownItemFamily knownItemFamily) {
        return getItemPolicies().stream()
            .filter(ip -> ip.getItem().getKnownItemFamily() == knownItemFamily)
            .collect(Collectors.toList());
    }

    public List<ItemPolicy> getDietItemPolicies() {
        return getItemPolicies(KnownItemFamily.DIET);
    }

    public List<ItemPolicy> getTranslationItemPolicies() {
        return getItemPolicies(KnownItemFamily.TRANSLATION);
    }

    public ItemPolicy getSharingAccommodationItemPolicy() {
        return Collections.findFirst(getItemPolicies(KnownItemFamily.ACCOMMODATION), ip -> Booleans.isTrue(ip.getItem().isShare_mate()));
    }

    /**
     * All share-mate accommodation ItemPolicies (item.share_mate == true), in policy
     * order. The booking form surfaces one "Share Accommodation" option per entry, so a
     * sharer can pick the type they're joining (e.g. "Sharing a room" vs "Sharing a
     * pre-erected tent"), each with its own rate/constraints.
     */
    public List<ItemPolicy> getSharingAccommodationItemPolicies() {
        return getItemPolicies(KnownItemFamily.ACCOMMODATION).stream()
            .filter(ip -> Booleans.isTrue(ip.getItem().isShare_mate()))
            .collect(Collectors.toList());
    }

    public List<ItemPolicy> getDiscoveryItemPolicies() {
        return getItemPolicies(KnownItemFamily.DISCOVERY);
    }

    public ItemFamilyPolicy getItemFamilyPolicy(KnownItemFamily knownItemFamily) {
        return getItemFamilyPolicies().stream()
                .filter(ifp -> ifp.getKnownItemFamily() == knownItemFamily)
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

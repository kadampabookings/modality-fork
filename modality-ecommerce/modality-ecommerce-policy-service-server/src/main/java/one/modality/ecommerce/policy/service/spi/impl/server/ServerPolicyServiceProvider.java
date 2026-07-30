package one.modality.ecommerce.policy.service.spi.impl.server;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.entity.DqlQueries;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.ScheduledItem;
import one.modality.ecommerce.policy.service.LoadPolicyArgument;
import one.modality.ecommerce.policy.service.PolicyAggregate;
import one.modality.ecommerce.policy.service.spi.PolicyServiceProvider;

import java.time.LocalDate;

/**
 * @author Bruno Salmon
 */
public final class ServerPolicyServiceProvider implements PolicyServiceProvider {

    // ── Shared DQL fragments for scheduled items queries (loadPolicy & loadAvailabilities) ──

    // Labels are selected as BARE FKs everywhere below: the domain model auto-loads a bare Label
    // FK's language columns (Label foreignFields rule — the persistent terms of Label.text's
    // per-language switch, all 9 languages since the `el` branch was added to that switch), so no
    // explicit label.(de,el,…,zht) expansion is needed.

    // Per-rc availability — reserved-partition model (docs/pool-allocation-removal-plan.md, replaces
    // PoolAllocation):
    //  • public beds = rc.max − rc.maxReserved, bookable online iff rc.online. documentLine.reserved
    //    is the partition marker: reserved-bed bookings (reserved=true) never count against the
    //    public partition. documentLine.pool is informative only (the reason, mirroring rc.pool).
    //  • greatest(..., 0) floors the reserved-partition arithmetic at 0: a room whose reserved
    //    partition overlaps an unreserved booking (e.g. maxReserved=1 + 1 public booking on a
    //    1-bed room) must not go negative and cancel out other rooms' availability (found on the
    //    staging rehearsal: ITTP female availability summed to 0 because of two such rooms).
    //  • least(...) then caps by physical occupancy (max − ALL live lines), deliberately NOT
    //    floored: a physically overbooked room contributes negatively so that its overflow
    //    guests consume the pool's remaining free beds (US NEDC twins: 3 free rooms + 1 room
    //    overbooked by 2 → 4 bookable beds, not 6 — matches the former PoolAllocation figures).
    // Both booking sums come from the single-pass `sums` LATERAL below; this expression is pure
    // arithmetic on them, so repeating it in the 4 gender/ordination sum args costs nothing.
    private static final String RC_AVAIL_EXPR =
        "(!rc.online ? 0 : least(greatest(rc.max - coalesce(rc.maxReserved,0) - sums.unreservedQty, 0), rc.max - sums.totalQty))";

    // CTEs + SELECT fields + availability subquery (via LATERAL) + FROM + common WHERE conditions
    private static final String SCHEDULED_ITEMS_DQL_BASE =
        "with e as (select coalesce(repeatedEvent,id) as finalEvent,startDate,endDate,preDate,postDate,venue from Event where id=$1)" +
        // Event-part date windows, resolved ONCE. MATERIALIZED is load-bearing: PG12+ inlines a
        // single-reference CTE back into the correlated EXISTS below, re-joining boundaries and
        // scheduled items for EVERY candidate row (231 evaluations / 4.8k buffers for event 1898).
        // The scheduledItem hops use ?. so a boundary carrying an explicit date with no
        // scheduledItem keeps its window (the old inner-join traversal silently dropped such
        // event parts — latent only, no such rows exist today).
        ", ep as materialized (select coalesce(startBoundary.date, startBoundary.scheduledItem?.date) as bstart, coalesce(endBoundary.date, endBoundary.scheduledItem?.date) as bend from EventPart where event=(select e.finalEvent from e))" +
        // Applicable resource configurations, resolved ONCE (materialized, same reason as ep).
        // The availability subquery below used to re-derive this set per scheduled item — scan the
        // site's ~60 resources, probe rc(resource,item), re-run the event-override anti-join —
        // 16.5k index probes / 36k buffers for event 1898 to rediscover a ~68-row set. Folded in
        // here: the resource join (for the site), the event-override rule (an event configuration
        // replaces the resource's global config for this event, resource-scoped — see the WHERE
        // comment below), and the site scope. Scope = sites of this event's bookable scheduled
        // items (NOT just the venue — event-bound items can sit at other sites); date params $2/$3
        // are ignored here (harmless superset), and the si.date-correlated start/end filters stay
        // in the subquery.
        ", rc as materialized (select x.resource.site.id as rcSite, x.item, x.online, x.allowsMale, x.allowsFemale, x.allowsLay, x.allowsOrdained, x.max, x.maxReserved, x.startDate, x.endDate" +
        " from ResourceConfiguration x" +
        // Event config overrides global: an event configuration (event=$1) replaces the resource's
        // global config for the event's duration — and may change the resource's item and/or
        // capacity (e.g. a 'Cabin' offered as a 'Standard twin' during the event). So a global
        // config (event=null) is dropped whenever the resource has ANY event config for this
        // event, regardless of item (resource-scoped, NOT resource+item): a resource is a single
        // physical unit with only one applicable config per day.
        " where (x.event=$1 or x.event=null and !exists(select ResourceConfiguration where resource=x.resource and event=$1))" +
        " and exists(select ScheduledItem si2 where bookableScheduledItem=id and si2.site=x.resource.site and (si2.event=(select e.finalEvent from e) or si2.event=null and si2.site=(select e.venue from e))))" +
        " select name,label,comment,site.(name,terminal,selfArranged,label),arrivalSite.(name,terminal,selfArranged,label),item.(name,label,perResourceLabel,code,temporal,family.(code,name,label,ord),capacity,share_mate,breakfastIncluded,ord),date,startTime,endTime,timeline?.(site,item,startTime,endTime),cancelled,resource,buddha.hyt" +
        // Availability: for each applicable configuration (from the rc CTE above, matched on the
        // scheduled item's site & item), LATERAL computes availability once, then distributes it
        // to 4 categories. This replaces the former scheduled_resource grid: rc is matched to the
        // scheduled item directly, so no scheduled_resource rows need to exist. Scanning the
        // materialized ~68-row CTE per item replaced the old per-item resource fan-out
        // (16.5k probes / 36k buffers → in-memory scans; 53ms → 25ms for event 1898).
        // `from rc rc`: the FROM-with-LATERAL grammar requires an explicit alias, and it must
        // equal the CTE name — As-aliased CTE columns (rcSite) resolve against the CTE alias.
        ",(select [" +
        "sum(!rc.(allowsMale and allowsLay) ? 0 : " + RC_AVAIL_EXPR + ")," +       // lay male
        "sum(!rc.(allowsFemale and allowsLay) ? 0 : " + RC_AVAIL_EXPR + ")," +     // lay female
        "sum(!rc.(allowsMale and allowsOrdained) ? 0 : " + RC_AVAIL_EXPR + ")," +  // monk
        "sum(!rc.(allowsFemale and allowsOrdained) ? 0 : " + RC_AVAIL_EXPR + ")" + // nun
        "] from rc rc" +
        // Both booking sums in ONE pass over this rc's live attendances (full-select LATERAL — a
        // one-row aggregate, so the cross join never drops rc rows and Postgres cannot pull it up:
        // it runs exactly once per rc row). unreservedQty excludes reserved-bed bookings (they never
        // count against the public partition), totalQty is ALL live lines for physical occupancy.
        // Bookings are counted via (scheduledItem=si and documentLine.resourceConfiguration=rc) — exactly
        // equivalent to the former Attendance.scheduledResource pointer, but without the scheduled_resource table.
        ", lateral (select" +
        " coalesce(sum(!documentLine.reserved ? documentLine.quantity : 0),0) as unreservedQty," +
        " coalesce(sum(documentLine.quantity),0) as totalQty" +
        " from Attendance where scheduledItem=si and present and documentLine.(resourceConfiguration=rc and !frontend_released)) sums" +
        // Configurations applicable to this scheduled item: same site & item. (The event-override
        // and site-scope rules are already folded into the rc CTE.)
        " where rc.rcSite=si.site and rc.item=si.item" +
        // Date scope: global configs start/stop over time; event configs are time-scoped by the event itself (no
        // dates → the null-open-ended test below always passes). Mirrors kbs_overlaps(si.date,si.date,start,end).
        " and (rc.startDate=null or rc.startDate<=si.date) and (rc.endDate=null or rc.endDate>=si.date)" +
        // group by rc.item → exactly one group when configs exist for this item, and zero groups (→ null array,
        // meaning "not resource-managed") for items that have no resource configuration.
        " group by rc.item)" +
        " as " + ScheduledItem.maleFemaleAvailabilities +
        " from ScheduledItem si" +
        " where bookableScheduledItem=id" +
        // bound to this event (or its repeatedEvent), or unbound but happening at the event venue during the event period.
        // e is read via scalar sub-selects, NOT joined in FROM: joined, this OR is a join clause that Postgres only
        // applies AFTER all the display joins have run over every scheduled item in the DB (the bookableScheduledItem=id
        // column=column predicate gets a default 0.5% selectivity estimate — ~122 rows instead of ~22k — which makes
        // that full-scan pipeline look cheap, and no statistics object can fix a bare col=col clause). As scalar
        // InitPlan params the OR stays a restriction clause the planner turns into a BitmapOr on the partial index
        // scheduled_item_self_bookable_event_site_date_idx (V0049), so only this event's ~300 rows reach the display
        // joins: 310ms → 80ms for event 1898.
        " and (si.event = (select e.finalEvent from e)" +
        "      or si.event=null and si.site = (select e.venue from e) and (si.date >= (select coalesce(e.preDate, e.startDate) from e) and si.date <= (select coalesce(e.postDate, e.endDate) from e) or exists(select ep where si.date>=ep.bstart and si.date<=ep.bend)))";
    // Accommodation filter appended by each caller

    // ItemPolicy exists check (shared by both acco filters)
    private static final String ACCO_ITEM_POLICY_EXISTS =
        "exists(select ItemPolicy ip where item=si.item and scope.(site=si.site and (event=null or event=$1) and (eventType=null or (select type=ip.scope.eventType from Event where id=$1))))";

    private static final String SCHEDULED_ITEMS_DQL_ORDER_BY =
        " order by site?.ord,site.id,item.family.id,item?.ord,item.id,date";

    @Override
    public Future<PolicyAggregate> loadPolicy(LoadPolicyArgument argument) {
        return resolveEventPk(argument.getEventPk())
            .compose(resolvedEventPk -> loadPolicyForResolvedEventPk(resolvedEventPk, argument));
    }

    private Future<PolicyAggregate> loadPolicyForResolvedEventPk(Object resolvedEventPk, LoadPolicyArgument argument) {
        // Managing the case of recurring event only for now
        Number eventPk = Numbers.toShortestNumber(resolvedEventPk);
        LocalDate startDate = argument.getStartDate();
        LocalDate endDate = argument.getEndDate();
        Object accoPk = Numbers.toShortestNumber(argument.getAccommodationItemPk());
        return QueryService.executeQueryBatch(
                new Batch<>(new QueryArgument[]{
                    // 0 - Loading event
                    DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                        "select name, slug, label, state, type.(bookingForm.code,category,supportEmail,noTermsAcceptance), themeBaseColor, themeAccentColor, themeBorderColor, themeStrongBackground, themeSurfaceColor, theme.(baseColor,accentColor,borderColor,strongBackground,surfaceColor), venue.(name,label,address), startDate, endDate, shortDescriptionLabel, longDescriptionLabel, currency.symbol, organization.(includeTeachingsInAccommodationPricesByDefault, currency.symbol, country.(currency.symbol, mainLanguage.iso_639_1), privacyUrlLabel, timezone, language.iso_639_1, supportEmail, inPersonTermsLabel, onlineTermsLabel), openingDate, bookingProcessStart, audioClosingDate, timezone, noAccountBooking, inPersonAllowed, onlineAllowed, vodEnabled, earlyBird, teacher.(name,label)" +
                        // Series = shared content of a term batch of sibling events (GP classes): title/description
                        // labels, colour palette, and the id for the cover-image fallback chain. Event-level fields
                        // override field by field; the client resolvers consult series.* only where the event is unset.
                        ", series.(label, shortDescriptionLabel, longDescriptionLabel, themeBaseColor, themeAccentColor, themeBorderColor, themeStrongBackground, themeSurfaceColor, theme.(baseColor,accentColor,borderColor,strongBackground,surfaceColor))" +
                        ", inPersonTermsLabel,onlineTermsLabel,termsUrlEn" +
                        ", date_part('epoch', openingDate - now()) as " + Event.secondsToOpeningDateAtLoadingTime +
                        ", date_part('epoch', coalesce(bookingProcessStart, openingDate) - now()) as " + Event.secondsToBookingProcessStartAtLoadingTime +
                        " from Event" + " where id=$1", eventPk),
                    // 1 - Loading scheduled items (of this event or of the repeated event if set)
                    // $1=eventPk, $2=startDate (null → no date filter), $3=endDate, $4=accommodationItemPk (null → item-policy check)
                    DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                        SCHEDULED_ITEMS_DQL_BASE +
                        // Accommodation filter: when $4 provided use the specific item, else fall back to the item-policy check
                        " and (si.item.family.code!='acco' or " + ACCO_ITEM_POLICY_EXISTS + " or si.item=$4)" +
                        // Date range filter: limit to volunteer's stay dates when $2/$3 provided
                        " and ($2::date=null or si.date>=$2) and ($3::date=null or si.date<=$3)" +
                        SCHEDULED_ITEMS_DQL_ORDER_BY, eventPk, startDate, endDate, accoPk)
                    // 2 - Loading scheduled boundaries (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent from Event where id=$1)" +
                    " select event,scheduledItem,timeline.(startTime,endTime),atStartTime,date" +
                    " from ScheduledBoundary sb, e where sb.event = e.finalEvent" +
                    " order by scheduledItem.date", eventPk)
                    // 3 - Loading event parts (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent from Event where id=$1)" +
                    " select event,name,label,startBoundary,endBoundary,accommodationChangeAllowed,hyt" +
                    " from EventPart epa, e where epa.event = e.finalEvent" +
                    " order by startBoundary.id", eventPk)
                    // 4 - Loading event selections (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent from Event where id=$1)" +
                    " select event,name,label,inPerson,online,part1,part2,part3,part4,part5" +
                    " from EventSelection es, e where es.event = e.finalEvent" +
                    " order by id", eventPk) // Will introduce an ord later
                    // 5 - Loading event phases (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent from Event where id=$1)" +
                    " select event,name,label,startBoundary,endBoundary" +
                    " from EventPhase eph, e where eph.event = e.finalEvent" +
                    " order by id", eventPk)
                    // 6 - Loading phase coverages (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent from Event where id=$1)" +
                    " select event,name,label,phase1,phase2,phase3,phase4" +
                    " from EventPhaseCoverage epc, e where epc.event = e.finalEvent" +
                    " order by id", eventPk) // Will introduce an ord later
                    // 7 - Loading item family policy (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent,coalesce(repeatedEvent?.type,type) as finalEventType,organization,venue.organization as venue_organization from Event where id=$1)" +
                    " select scope.(organization,site,eventType,event)" +
                    ",itemFamily.ord" +
                    ",applicableToInPerson,applicableToOnline,includedByDefault,askDietForBreakfast,dayVisitorBreakfastAllowed,dayVisitorDinnerAllowed,childAllowed,youngAdultAllowed,adultAllowed" +
                    ",disabled,replacesWiderScopes,displayTimes,minDay,wholeEvent,earlyAccommodationAllowed,lateAccommodationAllowed" +
                    ",eventPhaseCoverage1,eventPhaseCoverage2,eventPhaseCoverage3,eventPhaseCoverage4" +
                    ",noticeLabel,prerequisiteDescriptionLabel,prerequisiteConfirmationLabel" +
                    " from ItemFamilyPolicy ifp, e where ifp.scope.(" +
                    " (organization = e.organization or organization=e.venue_organization)" +
                    " and (site = null or site?.event = null or site?.event = e.finalEvent)" +
                    " and (eventType = null or eventType = e.finalEventType)" +
                    " and (event = null or event = e.finalEvent)" +
                    " )" +
                    " order by itemFamily.ord,id", eventPk)
                    // 8 - Loading item policies (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent,coalesce(repeatedEvent?.type,type) as finalEventType,organization,venue.organization as venue_organization from Event where id=$1)" +
                    " select scope.(organization,site,eventType,event)" +
                    ",item.(name,label,code,temporal,family.(code,name,label,ord),capacity,share_mate,breakfastIncluded,ord)" +
                    ",applicableToInPerson,applicableToOnline,descriptionLabel,titleLabel,noticeLabel,minDay,wholeEvent,default,genderInfoRequired,earlyAccommodationAllowed,lateAccommodationAllowed,minOccupancy,forceSoldOut,autoBookItem,childAllowed,youngAdultAllowed,adultAllowed" +
                    " from ItemPolicy ip, e where ip.scope.(" +
                    " (organization = e.organization or organization=e.venue_organization)" +
                    " and (site = null or site?.event = null or site?.event = e.finalEvent)" +
                    " and (eventType = null or eventType = e.finalEventType)" +
                    " and (event = null or event = e.finalEvent)" +
                    " )" +
                    " order by item.family.ord,item.ord,id", eventPk)
                    // 9 - Loading rates (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent,coalesce(repeatedEvent?.type,type) as finalEventType,organization,startDate,endDate,venue,venue.organization as venue_organization from Event where id=$1)" +
                    " select site,item,withItem,withAccommodation,earlyBird,breakfastIncluded,price,perDay,perPerson,applicableToInPerson,applicableToOnline,arrivingOrLeaving,facilityFee_price,facilityFee_discount,startDate,endDate,onDate,offDate,minDeposit" +
                    ",cutoffDate,minDeposit2" +
                    ",age1_max,age1_price,age1_discount,age2_max,age2_price,age2_discount" +
                    ",resident_price,resident_discount,resident2_price,resident2_discount" +
                    " from Rate r, e where (" +
                    // Sites dedicated to this event
                    "r.site.event = e.finalEvent" +
                    // or rates explicitly bound to this event. These are intentionally event-specific
                    // (e.g. post-event tour transport dated on the event's postDate, the day after endDate),
                    // so they must load regardless of site and of the in-event-date guard below — which only
                    // spans startDate..endDate and would otherwise drop them.
                    " or r.event = e.finalEvent" +
                    // or global sites of the organization with scheduled items over the period of the event
                    " or r.site.(event = null and (id=e.venue or organization=e.organization or organization=e.venue_organization) and (!r.item.temporal and !r.perDay or exists(select ScheduledItem si where si.site=r.site and si.item=r.item and si.date>=e.startDate and si.date<=e.endDate)))" +
                    "    and (r.event = null or r.event = e.finalEvent)" +
                    "    and (r.eventType = null or r.eventType = e.finalEventType)" +
                    ")" +
                    // Note: TeachingsPricing relies on the following order to work properly
                    " order by site,item,perDay desc,startDate,endDate,price", eventPk)
                    // 10 - Registration's manual sold-out overrides. The row's presence forces the item
                    // sold out; there is no flag to read. Bound to the event itself, NOT the repeated
                    // event: it is operational state about this run, not configuration inherited from
                    // the series.
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select item,site from SoldOutItem where event=$1", eventPk)
                }))
            .map(batch -> new PolicyAggregate(
                batch.get(0),
                batch.get(1),
                batch.get(2),
                batch.get(3),
                batch.get(4),
                batch.get(5),
                batch.get(6),
                batch.get(7),
                batch.get(8),
                batch.get(9),
                batch.get(10)
            ));
    }

    @Override
    public Future<QueryResult> loadAvailabilities(LoadPolicyArgument argument) {
        return resolveEventPk(argument.getEventPk())
            .compose(resolvedEventPk -> QueryService.executeQuery(
                DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    SCHEDULED_ITEMS_DQL_BASE +
                    " and (si.item.family.code!='acco' or " + ACCO_ITEM_POLICY_EXISTS + ")" +
                    SCHEDULED_ITEMS_DQL_ORDER_BY, resolvedEventPk)
            ));
    }

    // Resolves a raw event PK that may be either a numeric ID or a slug (URL-friendly
    // event identifier) to a value usable in DQL `where id=$1` lookups. Numeric Strings
    // and Numbers pass through; non-numeric Strings are looked up via Event.slug.
    private static Future<Object> resolveEventPk(Object rawEventPk) {
        if (rawEventPk == null || rawEventPk instanceof Number) {
            return Future.succeededFuture(rawEventPk);
        }
        if (!(rawEventPk instanceof String s)) {
            return Future.succeededFuture(rawEventPk);
        }
        Long parsedId = Numbers.parseLong(s);
        if (parsedId != null) {
            return Future.succeededFuture(parsedId);
        }
        return QueryService.executeQuery(
                DqlQueries.newQueryArgumentForDefaultDataSource(
                    "select id from Event where slug=$1", s))
            .compose(result -> result.getRowCount() == 0
                ? Future.failedFuture(new IllegalArgumentException("No event found for slug: " + s))
                : Future.succeededFuture(result.getValue(0, 0)));
    }

}

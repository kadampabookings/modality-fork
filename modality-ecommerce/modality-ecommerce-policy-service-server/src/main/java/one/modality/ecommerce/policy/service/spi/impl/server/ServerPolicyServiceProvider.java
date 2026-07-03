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

    // Per-language Label columns to expand wherever a label is shown to the public booker, so the
    // client can localise the text instead of falling back to the raw (English) name. Matches the
    // LABEL_I18N_FIELDS set in kbs3-react/shared/src/utils/label-utils.ts.
    private static final String LABEL_I18N_COLS = "de,el,en,es,fr,pt,vi,zhs,zht";
    private static final String LABEL_I18N = "label.(" + LABEL_I18N_COLS + ")"; // for an FK literally named "label"

    // CTEs + SELECT fields + availability subquery (via LATERAL) + FROM + common WHERE conditions
    private static final String SCHEDULED_ITEMS_DQL_BASE =
        "with e as (select coalesce(repeatedEvent,id) as finalEvent,startDate,endDate,preDate,postDate,venue from Event where id=$1)" +
        ", ep as (select startBoundary,endBoundary from EventPart where event=(select e.finalEvent from e))" +
        " select name," + LABEL_I18N + ",comment,site.(name,terminal,selfArranged," + LABEL_I18N + "),arrivalSite.(name,terminal,selfArranged," + LABEL_I18N + "),item.(name," + LABEL_I18N + ",perResourceLabel,code,temporal,family.(code,name,label,ord),capacity,share_mate,breakfastIncluded,ord),date,startTime,endTime,timeline?.(site,item,startTime,endTime),cancelled,resource,buddha.hyt" +
        // Availability: for each applicable ResourceConfiguration rc (resolved on the fly by matching the
        // scheduled item's site & item, with the event config winning over the global one), LATERAL computes
        // availability once, then distributes it to 4 categories. This replaces the former scheduled_resource
        // grid: rc is joined to the scheduled item directly, so no scheduled_resource rows need to exist.
        ",(select [" +
        "sum(!rc.(allowsMale and allowsLay) ? 0 : lat.avail)," +       // lay male
        "sum(!rc.(allowsFemale and allowsLay) ? 0 : lat.avail)," +     // lay female
        "sum(!rc.(allowsMale and allowsOrdained) ? 0 : lat.avail)," +  // monk
        "sum(!rc.(allowsFemale and allowsOrdained) ? 0 : lat.avail)" + // nun
        "] from ResourceConfiguration rc" +
        // avail per resource configuration — reserved-partition model (docs/pool-allocation-removal-plan.md,
        // replaces PoolAllocation):
        //  • public beds = rc.max − rc.maxReserved, bookable online iff rc.online. documentLine.reserved
        //    is the partition marker: reserved-bed bookings (reserved=true) never count against the
        //    public partition. documentLine.pool is informative only (the reason, mirroring rc.pool).
        //  • least(...) caps by physical occupancy (max − ALL live lines): an overbooked reserved
        //    partition can never push public availability above the room's real free beds.
        // Bookings are counted via (scheduledItem=si and documentLine.resourceConfiguration=rc) — exactly
        // equivalent to the former Attendance.scheduledResource pointer, but without the scheduled_resource table.
        // The whole expression is wrapped in a correlated sub-SELECT (rooted on ResourceConfiguration via
        // id=rc) so bare field references resolve — the LATERAL body has no FROM (null domain class), which
        // otherwise fails translation with "Domain class 'null' not found".
        ", lateral (select (select !online ? 0 : least(" +
        "max - coalesce(maxReserved,0)" +
        " - coalesce((select sum(documentLine.quantity) from Attendance where scheduledItem=si and present and documentLine.(resourceConfiguration=rc and !frontend_released and !reserved)), 0)" +
        ", max" +
        " - coalesce((select sum(documentLine.quantity) from Attendance where scheduledItem=si and present and documentLine.(resourceConfiguration=rc and !frontend_released)), 0))" +
        " from ResourceConfiguration where id=rc)" +
        " as avail) lat" +
        // Configurations applicable to this scheduled item: same site & item.
        " where rc.resource.site=si.site and rc.item=si.item" +
        // Event config overrides global: an event configuration (event=$1) replaces the resource's global config
        // for the event's duration — and may change the resource's item and/or capacity (e.g. a 'Cabin' offered as
        // a 'Standard twin' during the event). So a global config (event=null) is dropped whenever the resource has
        // ANY event config for this event, regardless of item (resource-scoped, NOT resource+item): a resource is a
        // single physical unit with only one applicable config per day. Matches the original SR-based behaviour.
        " and (rc.event=$1" +
        " or rc.event=null and !exists(select ResourceConfiguration where resource=rc.resource and event=$1))" +
        // Date scope: global configs start/stop over time; event configs are time-scoped by the event itself (no
        // dates → the null-open-ended test below always passes). Mirrors kbs_overlaps(si.date,si.date,start,end).
        " and (rc.startDate=null or rc.startDate<=si.date) and (rc.endDate=null or rc.endDate>=si.date)" +
        // group by rc.item → exactly one group when configs exist for this item, and zero groups (→ null array,
        // meaning "not resource-managed") for items that have no resource configuration.
        " group by rc.item)" +
        " as " + ScheduledItem.maleFemaleAvailabilities +
        " from ScheduledItem si, e" +
        " where bookableScheduledItem=id" +
        // bound to this event (or its repeatedEvent), or unbound but happening at the event venue during the event period
        " and (si.event = e.finalEvent" +
        "      or si.event=null and si.site = e.venue and (si.date >= coalesce(e.preDate, e.startDate) and si.date <= coalesce(e.postDate, e.endDate) or exists(select ep where si.date>=coalesce(ep.startBoundary.date, ep.startBoundary.scheduledItem.date) and si.date<=coalesce(ep.endBoundary.date, ep.endBoundary.scheduledItem.date))))";
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
                        "select name, slug, " + LABEL_I18N + ", state, type.(bookingForm.code,category), themeBaseColor, themeAccentColor, themeBorderColor, themeStrongBackground, themeSurfaceColor, theme.(baseColor,accentColor,borderColor,strongBackground,surfaceColor), venue.(name," + LABEL_I18N + ",address), startDate, endDate, shortDescriptionLabel.(" + LABEL_I18N_COLS + "), longDescriptionLabel.(" + LABEL_I18N_COLS + "), currency.symbol, organization.(includeTeachingsInAccommodationPricesByDefault, currency.symbol, country.currency.symbol, privacyUrlLabel, timezone), openingDate, bookingProcessStart, audioClosingDate, timezone, noAccountBooking, inPersonAllowed, onlineAllowed, vodEnabled, earlyBird, teacher.(name," + LABEL_I18N + ")" +
                        ", inPersonTermsLabel.(" + LABEL_I18N_COLS + "),onlineTermsLabel.(" + LABEL_I18N_COLS + "),termsUrlEn" +
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
                    " select event,name,label,inPerson,online,part1,part2,part3" +
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
                    ",applicableToInPerson,applicableToOnline,includedByDefault,askDietForBreakfast,dayVisitorDinnerAllowed,childAllowed,youngAdultAllowed,adultAllowed" +
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
                    ",item.(name," + LABEL_I18N + ",code,temporal,family.(code,name,label,ord),capacity,share_mate,breakfastIncluded,ord)" +
                    ",applicableToInPerson,applicableToOnline,descriptionLabel,titleLabel,noticeLabel,minDay,default,genderInfoRequired,earlyAccommodationAllowed,lateAccommodationAllowed,minOccupancy,forceSoldOut,autoBookItem,childAllowed,youngAdultAllowed,adultAllowed" +
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
                    " select site,item,withItem,withAccommodation,earlyBird,breakfastIncluded,price,perDay,perPerson,applicableToInPerson,applicableToOnline,facilityFee_price,facilityFee_discount,startDate,endDate,onDate,offDate,minDeposit" +
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
                batch.get(9)
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

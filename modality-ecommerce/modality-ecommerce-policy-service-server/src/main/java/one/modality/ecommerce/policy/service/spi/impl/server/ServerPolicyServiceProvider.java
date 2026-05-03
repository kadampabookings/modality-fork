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

    // CTEs + SELECT fields + availability subquery (via LATERAL) + FROM + common WHERE conditions
    private static final String SCHEDULED_ITEMS_DQL_BASE =
            "with e as (select coalesce(repeatedEvent,id) as finalEvent,startDate,endDate,preDate,postDate,venue from Event where id=$1)" +
            ", ep as (select startBoundary,endBoundary from EventPart where event=(select e.finalEvent from e))" +
            " select name,label,comment,site.name,item.(name,label,perResourceLabel,code,temporal,family.(code,name,label,ord),capacity,share_mate,ord),date,startTime,timeline?.(site,item,startTime,endTime),cancelled,resource" +
            // Availability: for each ScheduledResource sr, LATERAL computes availability once, then distributes to 4 categories
            ",(select [" +
            "sum(!sr.configuration.(allowsMale and allowsLay) ? 0 : lat.avail)," +       // lay male
            "sum(!sr.configuration.(allowsFemale and allowsLay) ? 0 : lat.avail)," +     // lay female
            "sum(!sr.configuration.(allowsMale and allowsOrdained) ? 0 : lat.avail)," +  // monk
            "sum(!sr.configuration.(allowsFemale and allowsOrdained) ? 0 : lat.avail)" + // nun
            "] from ScheduledResource sr" +
            ", lateral (select coalesce(" +
            "(select pa.quantity" +
            " - coalesce((select sum(documentLine.quantity) from Attendance where scheduledResource=sr and present and documentLine.(!frontend_released and pool=pa.pool)), 0)" +
            " from PoolAllocation pa" +
            " where resource=sr.configuration.resource and publicBookingEnabled and pool.allowsPublic and (event=$1 or event=null)" +
            " order by event desc nulls last" +
            " limit 1)" +
            ", 0) as avail) lat" +
            " where scheduledItem=si and exists(select PoolAllocation where resource=sr.configuration.resource and pool.allowsPublic and (event = null or event=$1))" +
            " group by scheduledItem)" +
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

    // Pool allocation exists check (shared by both acco filters)
    private static final String ACCO_POOL_EXISTS =
            "exists(select ScheduledResource sr where scheduledItem=si and exists(select PoolAllocation where resource=sr.configuration.resource and publicBookingEnabled and pool.allowsPublic and event=$1))";

    private static final String SCHEDULED_ITEMS_DQL_ORDER_BY =
            " order by site?.ord,site.id,item.family.id,item?.ord,item.id,date";

    @Override
    public Future<PolicyAggregate> loadPolicy(LoadPolicyArgument argument) {
        // Managing the case of recurring event only for now
        Number eventPk = Numbers.toShortestNumber(argument.getEventPk());
        LocalDate startDate = argument.getStartDate();
        LocalDate endDate = argument.getEndDate();
        Object accoPk = Numbers.toShortestNumber(argument.getAccommodationItemPk());
        return QueryService.executeQueryBatch(
                new Batch<>(new QueryArgument[]{
                    // 0 - Loading event
                    DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                        "select name, label, type.bookingForm.code, theme.code, venue.(name,label), startDate, endDate, shortDescriptionLabel, longDescriptionLabel, currency.symbol, organization.(currency.symbol, country.currency.symbol, privacyUrlLabel, timezone), openingDate, bookingProcessStart, timezone, noAccountBooking, inPersonAllowed, onlineAllowed, vodEnabled" +
                        ", inPersonTermsLabel,onlineTermsLabel,termsUrlEn" +
                        ", date_part('epoch', openingDate - now()) as " + Event.secondsToOpeningDateAtLoadingTime +
                        ", date_part('epoch', coalesce(bookingProcessStart, openingDate) - now()) as " + Event.secondsToBookingProcessStartAtLoadingTime +
                        " from Event" + " where id=$1", eventPk),
                    // 1 - Loading scheduled items (of this event or of the repeated event if set)
                    // $1=eventPk, $2=startDate (null → no date filter), $3=endDate, $4=accommodationItemPk (null → pool allocation check)
                    DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                        SCHEDULED_ITEMS_DQL_BASE +
                        // Accommodation filter: when $4 provided use the specific item, else fall back to pool allocation check
                        " and (si.item.family.code!='acco' or " + ACCO_ITEM_POLICY_EXISTS + " or ($4::int=null ? " + ACCO_POOL_EXISTS + " : si.item=$4))" +
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
                    " select event,name,label,startBoundary,endBoundary,accommodationChangeAllowed" +
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
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent,coalesce(repeatedEvent?.type,type) as finalEventType,organization from Event where id=$1)" +
                    " select scope.(organization,site,eventType,event)" +
                    ",itemFamily.ord" +
                    ",applicableToInPerson,applicableToOnline,includedByDefault" +
                    ",eventPhaseCoverage1,eventPhaseCoverage2,eventPhaseCoverage3,eventPhaseCoverage4" +
                    ",noticeLabel,prerequisiteDescriptionLabel,prerequisiteConfirmationLabel" +
                    " from ItemFamilyPolicy ifp, e where ifp.scope.(" +
                    " organization = e.organization" +
                    " and (site = null or site?.event = null or site?.event = e.finalEvent)" +
                    " and (eventType = null or eventType = e.finalEventType)" +
                    " and (event = null or event = e.finalEvent)" +
                    " )" +
                    " order by itemFamily.ord,id", eventPk)
                    // 8 - Loading item policies (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent,coalesce(repeatedEvent?.type,type) as finalEventType,organization from Event where id=$1)" +
                    " select scope.(organization,site,eventType,event)" +
                    ",item.(name,label,code,temporal,family.(code,name,label,ord),capacity,share_mate,ord)" +
                    ",applicableToInPerson,applicableToOnline,descriptionLabel,noticeLabel,minDay,default,genderInfoRequired,earlyAccommodationAllowed,lateAccommodationAllowed,minOccupancy,forceSoldOut" +
                    " from ItemPolicy ip, e where ip.scope.(" +
                    " organization = e.organization" +
                    " and (site = null or site?.event = null or site?.event = e.finalEvent)" +
                    " and (eventType = null or eventType = e.finalEventType)" +
                    " and (event = null or event = e.finalEvent)" +
                    " )" +
                    " order by item.family.ord,item.ord,id", eventPk)
                    // 9 - Loading rates (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "with e as (select coalesce(repeatedEvent,id) as finalEvent,coalesce(repeatedEvent?.type,type) as finalEventType,organization,startDate,endDate,venue from Event where id=$1)" +
                    " select site,item,price,perDay,perPerson,applicableToInPerson,applicableToOnline,facilityFee_price,facilityFee_discount,startDate,endDate,onDate,offDate,minDeposit" +
                    ",cutoffDate,minDeposit2" +
                    ",age1_max,age1_price,age1_discount,age2_max,age2_price,age2_discount" +
                    ",resident_price,resident_discount,resident2_price,resident2_discount" +
                    " from Rate r, e where (" +
                    // Sites dedicated to this event
                    "r.site.event = e.finalEvent" +
                    // or global sites of the organization with scheduled items over the period of the event
                    " or r.site.(event = null and (id=e.venue or organization=e.organization) and (!r.item.temporal and !r.perDay or exists(select ScheduledItem si where si.site=r.site and si.item=r.item and si.date>=e.startDate and si.date<=e.endDate)))" +
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
        return QueryService.executeQuery(
            DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                SCHEDULED_ITEMS_DQL_BASE +
                " and (si.item.family.code!='acco' or " + ACCO_ITEM_POLICY_EXISTS + " or " + ACCO_POOL_EXISTS + ")" +
                SCHEDULED_ITEMS_DQL_ORDER_BY, argument.getEventPk())
        );
    }

}

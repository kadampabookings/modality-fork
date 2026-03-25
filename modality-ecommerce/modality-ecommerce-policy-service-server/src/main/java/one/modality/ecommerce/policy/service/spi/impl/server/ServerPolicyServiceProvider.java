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
                        "select name, label, type, venue.(name,label), startDate, endDate, shortDescriptionLabel, longDescriptionLabel, termsUrlEn, currency.symbol, organization.(currency.symbol, country.currency.symbol, privacyUrlLabel, timezone), openingDate, bookingProcessStart, timezone, noAccountBooking, inPersonAllowed, onlineAllowed, vodEnabled" +
                        ", date_part('epoch', openingDate - now()) as " + Event.secondsToOpeningDateAtLoadingTime +
                        ", date_part('epoch', coalesce(bookingProcessStart, openingDate) - now()) as " + Event.secondsToBookingProcessStartAtLoadingTime +
                        " from Event" + " where id=$1", eventPk),
                    // 1 - Loading scheduled items (of this event or of the repeated event if set)
                    // $1=eventPk, $2=startDate (null → no date filter), $3=endDate, $4=accommodationItemPk (null → pool allocation check)
                    DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                        "select name,label,comment,site.name,item.(name,label,code,temporal,family.(code,name,label,ord),capacity,share_mate,ord),date,startTime,timeline.(site,item,startTime,endTime),cancelled,resource" +
                        // We also compute the remaining available space for guests
                        ",(select [" +
                        // male availability
                        "sum(!sr.configuration.allowsMale ? 0 :" +
                        " coalesce((select quantity from PoolAllocation where resource=sr.configuration.resource and publicBookingEnabled and pool.allowsPublic and event=$1 limit 1), 0)" +
                        " - coalesce((select sum(documentLine.quantity) from Attendance where scheduledResource=sr and present and documentLine.(!frontend_released and (pool = null or pool.allowsPublic))), 0)" +
                        ")," +
                        // female availability
                        "sum(!sr.configuration.allowsFemale ? 0 :" +
                        " coalesce((select quantity from PoolAllocation where resource=sr.configuration.resource and publicBookingEnabled and pool.allowsPublic and event=$1 limit 1), 0)" +
                        " - coalesce((select sum(documentLine.quantity) from Attendance where scheduledResource=sr and present and documentLine.(!frontend_released and (pool = null or pool.allowsPublic))), 0)" +
                        ")] from ScheduledResource sr" +
                        // We consider only the resources allocated to the general guest pool for this event
                        " where scheduledItem=si and exists(select PoolAllocation where resource=sr.configuration.resource and publicBookingEnabled and pool.allowsPublic and event=$1)" +
                        " group by scheduledItem)" +
                        " as " + ScheduledItem.maleFemaleAvailabilities +
                        " from ScheduledItem si" + " where" +
                        // Only bookable items
                        " bookableScheduledItem=id" +
                        // bound to this event
                        " and (select si.event = coalesce(e.repeatedEvent, e) " +
                        // or not bound to an event but happening in the event venue with over the period of the event
                        "      or si.event=null and si.site = e.venue and (si.date >= coalesce(e.preDate, e.startDate) and si.date <= coalesce(e.postDate, e.endDate) or exists(select EventPart ep where ep.event=e and si.date>=coalesce(ep.startBoundary.date, ep.startBoundary.scheduledItem.date) and si.date<=coalesce(ep.endBoundary.date, ep.endBoundary.scheduledItem.date))) from Event e where id=$1)" +
                        // Accommodation filter: when $4 provided use the specific item, else fall back to pool allocation check - Also loading dormitory (398) for MKMC Festival preparation events (61)
                        " and (si.item.family.code!='acco' or exists(select Event where id=$1 and type=61) and si.item=398 or ($4::int=null ? exists(select ScheduledResource sr where scheduledItem=si and exists(select PoolAllocation where resource=sr.configuration.resource and publicBookingEnabled and pool.allowsPublic and event=$1)) : si.item=$4))" +
                        // Date range filter: limit to volunteer's stay dates when $2/$3 provided
                        " and ($2::date=null or si.date>=$2) and ($3::date=null or si.date<=$3)" +
                        " order by site?.ord,item?.ord,date", eventPk, startDate, endDate, accoPk)
                    // 2 - Loading scheduled boundaries (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select event,scheduledItem,timeline.(startTime,endTime),atStartTime,date" +
                    " from ScheduledBoundary sb" + " where (select sb.event = coalesce(e.repeatedEvent, e) from Event e where id=$1)" +
                    " order by scheduledItem.date", eventPk)
                    // 3 - Loading event parts (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select event,name,label,startBoundary,endBoundary,accommodationChangeAllowed" +
                    " from EventPart epa" + " where (select epa.event = coalesce(e.repeatedEvent, e) from Event e where id=$1)" +
                    " order by startBoundary.id", eventPk)
                    // 4 - Loading event selections (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select event,name,label,inPerson,online,part1,part2,part3" +
                    " from EventSelection es" + " where (select es.event = coalesce(e.repeatedEvent, e) from Event e where id=$1)" +
                    " order by id", eventPk) // Will introduce an ord later
                    // 5 - Loading event phases (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select event,name,label,startBoundary,endBoundary" +
                    " from EventPhase eph" + " where (select eph.event = coalesce(e.repeatedEvent, e) from Event e where id=$1)" +
                    " order by id", eventPk)
                    // 6 - Loading phase coverages (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select event,name,label,phase1,phase2,phase3,phase4" +
                    " from EventPhaseCoverage epc" + " where (select epc.event = coalesce(e.repeatedEvent, e) from Event e where id=$1)" +
                    " order by id", eventPk) // Will introduce an ord later
                    // 7 - Loading item policies (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select scope.(organization,site,eventType,event)" +
                    ",itemFamily.ord" +
                    ",eventPhaseCoverage1,eventPhaseCoverage2,eventPhaseCoverage3,eventPhaseCoverage4" +
                    ",noticeLabel,prerequisiteDescriptionLabel,prerequisiteConfirmationLabel" +
                    " from ItemFamilyPolicy ifp" + " where (select ifp.scope.(" +
                    " organization = e.organization" +
                    " and (site = null or site?.event = null or site?.event = coalesce(e.repeatedEvent, e))" +
                    " and (eventType = null or eventType = coalesce(e.repeatedEvent.type, e.type))" +
                    " and (event = null or event = coalesce(e.repeatedEvent, e))" +
                    " ) from Event e where id=$1)" +
                    " order by itemFamily.ord,id", eventPk)
                    // 8 - Loading item policies (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select scope.(organization,site,eventType,event)" +
                    ",item.(name,label,code,temporal,family.(code,name,label,ord),capacity,share_mate,ord)" +
                    ",descriptionLabel,noticeLabel,minDay,default,genderInfoRequired,earlyAccommodationAllowed,lateAccommodationAllowed,minOccupancy,forceSoldOut" +
                    " from ItemPolicy ip" + " where (select ip.scope.(" +
                    " organization = e.organization" +
                    " and (site = null or site?.event = null or site?.event = coalesce(e.repeatedEvent, e))" +
                    " and (eventType = null or eventType = coalesce(e.repeatedEvent.type, e.type))" +
                    " and (event = null or event = coalesce(e.repeatedEvent, e))" +
                    " ) from Event e where id=$1)" +
                    " order by item.family.ord,item.ord,id", eventPk)
                    // 9 - Loading rates (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select site,item,price,perDay,perPerson,applicableToInPerson,applicableToOnline,facilityFee_price,facilityFee_discount,startDate,endDate,onDate,offDate,minDeposit" +
                    ",cutoffDate,minDeposit2" +
                    ",age1_max,age1_price,age1_discount,age2_max,age2_price,age2_discount" +
                    ",resident_price,resident_discount,resident2_price,resident2_discount" +
                    " from Rate r" + " where (" +
                    // Sites dedicated to this event
                    "select r.site.event = coalesce(e.repeatedEvent, e)" +
                    // or global sites of the organization with scheduled items over the period of the event
                    " or r.site.(event = null and organization=e.organization and exists(select ScheduledItem si where si.site=r.site and si.item=r.item and si.date>=e.startDate and si.date<=e.endDate))" +
                    " from Event e where id=$1)" +
                    // Note: TeachingsPricing relies on the following order to work properly
                    " order by site,item,perDay desc,startDate,endDate,price", eventPk)
                    // 10 (deprecated) - Loading bookable periods (of this event or of the repeated event if set)
                    , DqlQueries.newQueryArgumentForDefaultDataSourceWithMetadata(
                    "select startScheduledItem,endScheduledItem,name,label" +
                    " from BookablePeriod bp" + " where (select bp.event = coalesce(e.repeatedEvent, e) from Event e where id=$1)" +
                    " order by startScheduledItem.date,endScheduledItem.date", eventPk)
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
        return QueryService.executeQuery(
            DqlQueries.newQueryArgumentForDefaultDataSource(
                "select name,label,comment,site.name,item.(name,label,code,temporal,family.(code,name,label,ord),capacity,share_mate,ord),date,startTime,timeline.(site,item,startTime,endTime),cancelled,resource" +
                // We also compute the remaining available space for guests
                ",(select [" +
                // male availability
                "sum(!sr.configuration.allowsMale ? 0 :" +
                " coalesce((select quantity from PoolAllocation where resource=sr.configuration.resource and publicBookingEnabled and pool.allowsPublic and event=$1 limit 1), 0)" +
                " - coalesce((select sum(documentLine.quantity) from Attendance where scheduledResource=sr and present and documentLine.(!frontend_released and (pool = null or pool.allowsPublic))), 0)" +
                ")," +
                // female availability
                "sum(!sr.configuration.allowsFemale ? 0 :" +
                " coalesce((select quantity from PoolAllocation where resource=sr.configuration.resource and publicBookingEnabled and pool.allowsPublic and event=$1 limit 1), 0)" +
                " - coalesce((select sum(documentLine.quantity) from Attendance where scheduledResource=sr and present and documentLine.(!frontend_released and (pool = null or pool.allowsPublic))), 0)" +
                ")] from ScheduledResource sr" +
                // We consider only the resources allocated to the general guest pool for this event
                " where scheduledItem=si and exists(select PoolAllocation where resource=sr.configuration.resource and publicBookingEnabled and pool.allowsPublic and event=$1)" +
                " group by scheduledItem)" +
                " as " + ScheduledItem.maleFemaleAvailabilities +
                " from ScheduledItem si" + " where bookableScheduledItem=id and (select si.event = coalesce(e.repeatedEvent, e) or si.event=null and si.timeline?.site?.organization = e.organization and (si.date >= e.startDate and si.date <= e.endDate or exists(select EventPart ep where ep.event=e and si.date>=coalesce(ep.startBoundary.date, ep.startBoundary.scheduledItem.date) and si.date<=coalesce(ep.endBoundary.date, ep.endBoundary.scheduledItem.date))) from Event e where id=$1)" +
                " order by site?.ord,item?.ord,date", argument.getEventPk())
        );
    }
}

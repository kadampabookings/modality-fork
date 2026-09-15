package one.modality.ecommerce.shared.pricecalculator;

import dev.webfx.platform.util.Booleans;
import dev.webfx.platform.util.Objects;
import dev.webfx.platform.util.collection.Collections;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.DocumentLine;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.Item;
import one.modality.base.shared.entities.Person;
import one.modality.base.shared.entities.Rate;
import one.modality.base.shared.entities.Site;
import one.modality.base.shared.entities.SiteItem;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityId;
import one.modality.base.shared.entities.util.Rates;
import one.modality.ecommerce.document.service.DocumentAggregate;
import one.modality.ecommerce.document.service.events.registration.documentline.PriceDocumentLineEvent;
import one.modality.ecommerce.policy.service.PolicyAggregate;

import java.time.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Bruno Salmon
 */
public final class SiteItemBill {

    /** Deposit percentage when a rate leaves it unset (the default of compute_rate_min_deposit). */
    private static final int DEFAULT_MIN_DEPOSIT_PERCENT = 25;

    private final SiteItem siteItem;
    private final PriceDocumentLineEvent priceDocumentLineEvent;
    private final List<AttendanceBill> attendanceBills = new ArrayList<>();
    private boolean childRateApplied;

    private int totalPrice = -1;
    private int minDeposit = -1;
    // Pass that set the price in the last real-total computation (true per-day, false fixed, null no
    // rate applied), reused by the min-deposit computation once totalPassRun is set.
    private Boolean totalPassPerDayWins;
    private boolean totalPassRun;

    SiteItemBill(SiteItem siteItem, PriceDocumentLineEvent priceDocumentLineEvent) {
        this.siteItem = siteItem;
        this.priceDocumentLineEvent = priceDocumentLineEvent;
    }

    public SiteItem getSiteItem() {
        return siteItem;
    }

    public List<AttendanceBill> getAttendanceBills() {
        return attendanceBills;
    }

    public int getTotalPrice() {
        return totalPrice;
    }

    public int getMinDeposit() {
        return minDeposit;
    }

    public boolean isChildRateApplied() {
        return childRateApplied;
    }

    void addAttendanceBill(AttendanceBill attendanceBill) {
        attendanceBills.add(attendanceBill);
    }

    void sortAttendanceBillsByDate() {
        attendanceBills.sort((ba1, ba2) -> {
            int result = ba1.getDate().compareTo(ba2.getDate());
            /* Commented for now in KBS3
            if (result == 0) {
                var tr1 = ba1.documentLine.timeRange;
                var tr2 = ba2.documentLine.timeRange;
                if (tr1 && tr2)
                    result = tr1.getInterval()[0] - tr2.getInterval()[0];
                else
                    result = tr1 ? -1 : tr2 ? 1 : 0;
            }*/
            return result;
        });
    }

    int computePrice(DocumentBill documentBill, boolean minDeposit) {
        int price;
        Number price_custom = priceDocumentLineEvent != null ? priceDocumentLineEvent.getPrice_custom() : null;
        Number price_discount = priceDocumentLineEvent != null ? priceDocumentLineEvent.getPrice_discount() : null;
        if (price_custom != null && !minDeposit)
            price = price_custom.intValue();
        else if (Objects.areEquals(price_discount, 100, true))
            price = 0;
        else {
            // The per-day and fixed passes are priced separately and one of them sets the price. The min
            // deposit must come from that same pass: the lower of the two deposits could come from rates
            // that did not set the price (compute_document_prices takes each deposit from the rate that
            // priced it). The min-deposit computation reuses the pass chosen by the last real-total one,
            // decided under the same pricing state (getRatePrice sets childRateApplied), and prices the
            // totals itself only when no total was computed first.
            Boolean perDayWins;
            int perDayTotal = 0, fixedTotal = 0;
            if (minDeposit && totalPassRun)
                perDayWins = totalPassPerDayWins;
            else {
                List<Rate> perDayPicked = new ArrayList<>();
                List<Rate> fixedPicked = new ArrayList<>();
                perDayTotal = computeBlockPriceWithRates(documentBill, false, true, perDayPicked);
                fixedTotal = computeBlockPriceWithRates(documentBill, false, false, fixedPicked);
                perDayWins = perDayPassWins(perDayTotal, fixedTotal, perDayPicked, fixedPicked);
                if (!minDeposit) {
                    totalPassPerDayWins = perDayWins;
                    totalPassRun = true;
                }
            }
            if (perDayWins == null)
                price = 0;
            else if (minDeposit) {
                // Both deposit passes run, in the original order, so the attendance prices they leave
                // behind are the same as before.
                int perDayDeposit = computeBlockPriceWithRates(documentBill, true, true, null);
                int fixedDeposit = computeBlockPriceWithRates(documentBill, true, false, null);
                price = perDayWins ? perDayDeposit : fixedDeposit;
            } else
                price = perDayWins ? perDayTotal : fixedTotal;
            if (price_discount != null)
                price = price * (100 - price_discount.intValue()) / 100;
            if (price_custom != null)
                price = Math.min(price, price_custom.intValue());
        }
        // KBS3 breakfast credit: when this is the breakfast bill, forgive the breakfasts already
        // paid for through accommodation nights that include breakfast (general rule + room/tent sharing).
        price = applyBreakfastCredit(documentBill, price);
        if (minDeposit)
            this.minDeposit = price;
        else
            this.totalPrice = price;
        return price;
    }

    /**
     * Applies the breakfast credit to this bill's computed price when this is the breakfast bill
     * (item code "BKF"). A breakfast is free for each accommodation night whose booking includes
     * breakfast: we forgive min(B, N), where B is the number of breakfasts in this bill and N the
     * number of accommodation nights in the document whose line was captured with breakfastIncluded
     * (see {@link DocumentLine#isBreakfastIncluded()}, set at booking from the item or the owner's rate).
     * <p>
     * No event gating is needed: KBS2 bookings capture breakfastIncluded = false (their rates and
     * sharing items are false), so N stays 0 and nothing is forgiven. The reduction is proportional
     * to B, so with the usual uniform breakfast price it forgives exactly `forgiven` breakfasts.
     */
    private int applyBreakfastCredit(DocumentBill documentBill, int price) {
        if (price <= 0 || attendanceBills.isEmpty())
            return price;
        Item breakfastItem = attendanceBills.get(0).getDocumentLine().getItem();
        if (breakfastItem == null || !"BKF".equals(breakfastItem.getCode())) // not the breakfast bill
            return price;
        int breakfasts = attendanceBills.size();
        int forgiven = Math.min(breakfasts, countBreakfastIncludedNights(documentBill));
        if (forgiven <= 0)
            return price;
        return price - price * forgiven / breakfasts; // proportional credit (integer arithmetic)
    }

    /** Counts the document's nights whose line was captured with breakfastIncluded = true. */
    private static int countBreakfastIncludedNights(DocumentBill documentBill) {
        int nights = 0;
        for (SiteItemBill bill : documentBill.getSiteItemBills())
            for (AttendanceBill ab : bill.getAttendanceBills())
                if (Booleans.isTrue(ab.getDocumentLine().isBreakfastIncluded()))
                    nights++;
        return nights;
    }

    /**
     * Prices this bill with either the per-day or the fixed rates; Integer.MIN_VALUE when none applies.
     * When {@code pickedRatesOut} is not null, the rates chosen for the attendances are added to it.
     */
    private int computeBlockPriceWithRates(DocumentBill documentBill, boolean minDeposit, boolean perDayRates, List<Rate> pickedRatesOut) {
        List<AttendanceBill> bas = attendanceBills;
        int blockLength = bas.size();
        int remainingDays = blockLength;
        if (remainingDays == 0)
            return 0;
        int consumedDays = 0;
/* Commented for now in KBS3
        if (bill.update) {
            for (var i = 0; i < blockLength; i++) {
                var dl = bas[i].documentLine;
                dl.price = 0;
                dl.rounded = false;
            }
        }
*/
        LocalDate firstDay = Collections.first(bas).getDate();
        LocalDate lastDay = Collections.last(bas).getDate();
        DocumentAggregate documentAggregate = documentBill.getDocumentAggregate();
        PolicyAggregate policyAggregate = documentAggregate.getPolicyAggregate();
        boolean inPerson = documentAggregate.getDocument().isInPerson();
        Instant creationInstant = Objects.coalesce(documentAggregate.getDocument().getCreationDate(), Instant.now());
        Event event = documentAggregate.getEvent();
        ZoneId eventZoneId = event == null ? null : event.getEventZoneId();
        if (eventZoneId == null)
            eventZoneId = ZoneOffset.UTC;
        LocalDateTime creationDateTime = LocalDateTime.ofInstant(creationInstant, eventZoneId);
        LocalDate creationDate = creationDateTime.toLocalDate();
        boolean documentEarlyBird = Booleans.isTrue(documentAggregate.getDocument().isEarlyBird());
        List<Rate> rates = policyAggregate.filterRatesStreamOfSiteAndItem(siteItem.getSite(), siteItem.getItem(), perDayRates)
            // Only rates compute_document_prices would consider for this booking (rate_matches_document).
            // The policy loader is broader: it also loads a repeated event's rates and every rate on the
            // event's own sites.
            .filter(r -> matchesBookedEvent(r, event))
            .filter(r -> inPerson ? r.isApplicableToInPerson() : r.isApplicableToOnline())
            .filter(r -> !Booleans.isTrue(r.isEarlyBird()) || (documentEarlyBird && !documentBill.ignoreEarlyBirdRates))
            .filter(r -> Rates.isAndApplicableAtDateAndOverPeriod(r, creationDateTime, firstDay, lastDay))
            .collect(Collectors.toCollection(ArrayList::new));
        // Most specific first, then lower id. The loop below keeps the first of equally priced
        // candidates, and the chosen rate also supplies the min deposit: a "Day Course" copy of a
        // generic meal rate exists precisely to require a different deposit, so it must win its tie.
        // Same order as the rate ORDER BY of compute_document_prices (V0092).
        rates.sort(SiteItemBill::compareRateSpecificity);
        // Stay length each rate's minDay is compared with: fixed for this block, so computed once per rate.
        Map<Rate, Integer> rateBlockLengths = new IdentityHashMap<>();
        for (Rate rate : rates)
            rateBlockLengths.put(rate, rateBlockLength(bas, rate.getStartDate(), rate.getEndDate(), Objects.coalesce(rate.getMinDay(), 1)));
        boolean thisItemTemporal = Booleans.isTrue(siteItem.getItem().isTemporal());
        // Pre-compute attendance dates for each withItem referenced by the rates (once, before
        // the while loop). IdentityHashMap is correct here: within the same policy store, the
        // same entity always has the same object reference.
        Map<Item, Set<LocalDate>> withItemDateCache = new IdentityHashMap<>();
        for (Rate rate : rates) {
            Item wi = rate.getWithItem();
            if (wi != null)
                withItemDateCache.computeIfAbsent(wi, k -> collectAttendanceDates(k, documentBill));
        }
        // Accommodation night dates for the per-day withAccommodation residency match (see loop below).
        Set<LocalDate> accoNights = collectAccommodationNights(documentBill);
        int price = Integer.MIN_VALUE;
        if (!rates.isEmpty()) {
            //rates.sort(function (r1, r2) { return (r1.perDay ? 1 : r1.maxDay ) - (r2.perDay ? 1 : r2.maxDay);});
            while (remainingDays > 0) {
                // selecting the cheapest rate for the next attendances
                PriceMemo cheapest = null;
                PriceMemo second = null;
                for (Rate rate : rates) {
                    // Ignoring rates for long stay discounts (if requested)
                    if (documentBill.ignoreLongStayDiscount && !rate.isPerDay()) // assuming that a rate not per day is a long stay discount TODO: check this more carefully
                        continue;
                    // Ignoring expired rates (such as early birds discounts)
                    LocalDate offDate = rate.getOffDate();
                    if (offDate != null && creationDate.isAfter(offDate))
                        continue;
                    // Ignoring rates that are not in the range of dates
                    LocalDate startDate = rate.getStartDate();
                    LocalDate endDate = rate.getEndDate();
                    if (startDate != null || endDate != null) {
                        LocalDate date = bas.get(consumedDays).getDate();
                        if (startDate != null && date.isBefore(startDate) || endDate != null && date.isAfter(endDate))
                            continue;
                    }
                    // arrivingOrLeaving: rate applies only on the arrival/departure edge of a contiguous
                    // run of this item's attendances (mirrors compute_document_prices). An interior day -
                    // same document line and a <=1 day gap on both sides - is skipped; the first and last
                    // attendance are always edges.
                    if (Booleans.isTrue(rate.isArrivingOrLeaving()) && consumedDays > 0 && consumedDays < bas.size() - 1) {
                        AttendanceBill prev = bas.get(consumedDays - 1), cur = bas.get(consumedDays), next = bas.get(consumedDays + 1);
                        boolean edge = !Entities.samePrimaryKey(prev.getDocumentLine(), cur.getDocumentLine())
                                    || !Entities.samePrimaryKey(next.getDocumentLine(), cur.getDocumentLine())
                                    || cur.getDate().toEpochDay() - prev.getDate().toEpochDay() > 1
                                    || next.getDate().toEpochDay() - cur.getDate().toEpochDay() > 1;
                        if (!edge)
                            continue;
                    }
                    // withItem: rate only applies when the companion item is also booked.
                    // Skipped entirely when ignoreWithItemRates is set (standard price baseline).
                    Item withItem = rate.getWithItem();
                    if (withItem != null && documentBill.ignoreWithItemRates)
                        continue;
                    Set<LocalDate> withItemDates = null;
                    boolean withItemTemporal = false;
                    if (withItem != null) {
                        withItemDates = withItemDateCache.get(withItem);
                        if (withItemDates == null || withItemDates.isEmpty())
                            continue;
                        withItemTemporal = Booleans.isTrue(withItem.isTemporal());
                        if (thisItemTemporal && withItemTemporal) {
                            if (!withItemDates.contains(bas.get(consumedDays).getDate()))
                                continue;
                        }
                    }
                    // withAccommodation: residential/non-residential per-day match. null = applies to all;
                    // true = only when resident this day; false = only when not. Resident on teaching day D
                    // when a live accommodation night was booked on D or D-1 (a night dated N covers the
                    // evening of N and the morning of N+1). Non-temporal items have no day, so they fall
                    // back to document-level accommodation existence.
                    Boolean withAcco = rate.isWithAccommodation();
                    if (withAcco != null) {
                        boolean resident;
                        if (thisItemTemporal) {
                            LocalDate d = bas.get(consumedDays).getDate();
                            resident = accoNights.contains(d) || accoNights.contains(d.minusDays(1));
                        } else {
                            resident = !accoNights.isEmpty();
                        }
                        if (withAcco != resident)
                            continue;
                    }
                    // For per-person rates, multiply by shareOwnerQuantity from the matching DocumentLine.
                    var quantity = rate.isPerPerson() ? getShareOwnerQuantity(documentAggregate) : 1;
                    int ratePrice = getRatePrice(rate, documentAggregate) * quantity;
                    int minDay = Objects.coalesce(rate.getMinDay(), 1);
                    int maxDay = rate.isPerDay() ? 1 : Objects.coalesce(rate.getMaxDay(), 10000);
                    // minDay, as compute_document_prices applies it: the stay is counted within the rate's
                    // date range, and a stay shorter than minDay drops the rate when minDayCeiling is false.
                    if (rateBlockLengths.get(rate) < minDay) {
                        if (Boolean.FALSE.equals(rate.isMinDayCeiling()))
                            continue;
                        // When a rate defines a new lower daily price that applies after a minimum of days (ex: 30% discount when >= 14 days),
                        // we need to ensure that people approaching that number of days (ex: 12 or 13 days)
                        // don't pay more with the previous rate than people staying that minimum of days (ex: 14 days)
                        // In other words, we need to put an upper limit for such people, equals to the price that is applied at that minimum of days
                        if (maxDay == 1) { // So if the block is less than the rate min day,
                            ratePrice = ratePrice * minDay; // we transform the daily rate into a fixed rate with the upper limit
                            maxDay = minDay; // that applies over that period
                        }
                    }
                    int consumableDays = Math.min(remainingDays, maxDay);
                    // Cap by the rate's endDate: without this a 3-day rate ending on July 26 would claim all 4
                    // days of a July 24-27 block and win on daily price while leaving July 27 uncovered
                    // (same as the React SiteItemBill).
                    if (endDate != null) {
                        int daysInRange = 0;
                        for (int j = consumedDays; j < bas.size() && !bas.get(j).getDate().isAfter(endDate); j++)
                            daysInRange++;
                        consumableDays = Math.min(consumableDays, daysInRange);
                        if (consumableDays == 0)
                            continue;
                    }
                    // withItem temporal overlap: per-day → cap to consecutive overlap;
                    // fixed → all-or-nothing (skip if companion doesn't cover all days).
                    if (withItem != null && thisItemTemporal && withItemTemporal) {
                        int overlap = 0;
                        for (int j = consumedDays; j < consumedDays + consumableDays; j++) {
                            if (withItemDates.contains(bas.get(j).getDate())) overlap++;
                            else break;
                        }
                        if (perDayRates) {
                            consumableDays = Math.min(consumableDays, overlap);
                            if (consumableDays == 0) continue;
                        } else {
                            if (overlap < consumableDays) continue;
                        }
                    }
                    int dailyPrice = ratePrice / consumableDays;
                    // Ugly workaround for Online January retreat 2021 because this price algorithm is not always correct.
                    // Ex: 1 week (actually 8 days): £70, 2 weeks (actually 15 days): £120, 3 weeks (actually 22 days): £180
                    // => For the 3-weeks case, this price algorithm computes £190 instead of £180 because it considers
                    // the £120 rate is the cheaper (because £120 / 15 < £180 / 22) and then add £70 for the remaining days.
                    /* Commented in KBS3
                    if (rate.id === 27510 && remainingDays === maxDay) // £120 rate with 22 remaining days
                        dailyPrice = ratePrice / (maxDay + 1);*/ // Changing the daily price comparison to £180 / 23 to make it the cheapest
                    PriceMemo memo = new PriceMemo(rate, dailyPrice, ratePrice, consumableDays);
                    // Prefer the candidate covering more days, then the lower daily price compared exactly:
                    // int daily prices can round to false ties, which the specificity order would then settle
                    // for a dearer rate. Same rule as the React SiteItemBill: a rate covering 4 days at £21
                    // beats one covering 3 days at £15 for a 4-day block, which would leave a day to price
                    // with a second rate.
                    if (cheapest == null)
                        cheapest = memo;
                    else if (consumableDays > cheapest.consumableDays() || consumableDays == cheapest.consumableDays() && isCheaperPerDay(memo, cheapest)) {
                        second = cheapest;
                        cheapest = memo;
                    } else if (second == null || isCheaperPerDay(memo, second))
                        second = memo;
                }
                if (cheapest == null) // Happens when no rate is finally applicable
                    break;
                if (pickedRatesOut != null)
                    pickedRatesOut.add(cheapest.rate());
                // applying the found cheapest rate on the next consumable days (applicable for this rate)
                var remainingPrice = cheapest.price();
                if (second == null)
                    second = cheapest;
                var fullAttendancePrice = second.price() / second.consumableDays();
                for (int i = 0; i < cheapest.consumableDays(); i++) {
                    AttendanceBill ba = bas.get(consumedDays + i);
                    ba.price = i == cheapest.consumableDays() - 1 ? remainingPrice : Math.min(fullAttendancePrice, remainingPrice);
                    /*if (update)
                        ba.documentLine.price += ba.price;*/
                    remainingPrice -= ba.price;
                }
                // updating the block price
                int deltaPrice = cheapest.price();
                if (minDeposit) {
                    int minDepositPercent = rateMinDepositPercent(cheapest.rate(), LocalDate.now());
                    deltaPrice = deltaPrice * minDepositPercent / 100;
                }
                if (price == Integer.MIN_VALUE)
                    price = deltaPrice;
                else
                    price += deltaPrice;
                // marking progress
                consumedDays += cheapest.consumableDays();
                remainingDays -= cheapest.consumableDays();
            }
        }
        /* Commented in KBS3 for now
        var roundingFactor = bill.document.event.optionRoundingFactor;
        if (update && roundingFactor) { // rounding document lines prices if a rounding factor is specified for the event
            price = 0; // also recomputing the total block price
            for (i = 0; i < blockLength; i++) {
                dl = bas[i].documentLine;
                if (!dl.rounded) {
                    var item = dl.getItemOption().item;
                    var fcode = item.family.code;
                    if (fcode !== 'acco' && fcode !== 'tax')
                        dl.price = Math.round(dl.price / roundingFactor) * roundingFactor;
                    price += dl.price;
                    dl.rounded = true;
                }
            }
        }*/
        return price;
    }

    /**
     * Whether the per-day pass sets this bill's price rather than the fixed pass; null when neither
     * pass found an applicable rate. The cheaper pass wins. An exact tie follows the candidate order of
     * compute_document_prices, which keeps the first of equally priced rates: the higher price per max
     * day first, then the more specific rate, and the per-day pass when those are equal too.
     */
    private static Boolean perDayPassWins(int perDayTotal, int fixedTotal, List<Rate> perDayPicked, List<Rate> fixedPicked) {
        if (perDayTotal == Integer.MIN_VALUE && fixedTotal == Integer.MIN_VALUE)
            return null;
        if (fixedTotal == Integer.MIN_VALUE)
            return true;
        if (perDayTotal == Integer.MIN_VALUE)
            return false;
        if (perDayTotal != fixedTotal)
            return perDayTotal < fixedTotal;
        int perDayKey = maxOrderKey(perDayPicked), fixedKey = maxOrderKey(fixedPicked);
        if (perDayKey != fixedKey)
            return perDayKey > fixedKey;
        return maxSpecificity(fixedPicked) <= maxSpecificity(perDayPicked);
    }

    /**
     * Highest first ORDER BY key of compute_document_prices among a pass's rates: the price divided by
     * the rate's max days (1 per day, maxDay or 1 when fixed), in integer division like the SQL. It
     * reads the listed price, where the SQL uses the booker's unit price after discounts.
     */
    private static int maxOrderKey(List<Rate> rates) {
        int max = Integer.MIN_VALUE;
        for (Rate rate : rates) {
            int days = Booleans.isTrue(rate.isPerDay()) ? 1 : Objects.coalesce(rate.getMaxDay(), 1);
            max = Math.max(max, Objects.coalesce(rate.getPrice(), 0) / days);
        }
        return max;
    }

    /** Whether memo prices its days more cheaply than other, compared exactly (no int rounding). */
    private static boolean isCheaperPerDay(PriceMemo memo, PriceMemo other) {
        return (long) memo.price() * other.consumableDays() < (long) other.price() * memo.consumableDays();
    }

    /**
     * Event criteria of rate_matches_document: a rate bound to an event applies only to that event, a
     * rate bound to an event type only to events of that type (never to an event without a type).
     * Nothing is filtered when the booked event is unknown.
     */
    private static boolean matchesBookedEvent(Rate rate, Event bookedEvent) {
        if (bookedEvent == null)
            return true;
        // samePrimaryKey compares numeric keys by value (an Integer and a Long id can hold the same key)
        EntityId rateEventId = rate.getEventId();
        if (rateEventId != null && !Entities.samePrimaryKey(rateEventId, bookedEvent))
            return false;
        EntityId rateEventTypeId = rate.getEventTypeId();
        return rateEventTypeId == null || Entities.samePrimaryKey(rateEventTypeId, bookedEvent.getTypeId()); // false when the event has no type
    }

    /** How specifically a rate is bound: 2 to an event, 1 to an event type, 0 generic. */
    private static int rateSpecificity(Rate rate) {
        if (rate.getEventId() != null)
            return 2;
        if (rate.getEventTypeId() != null)
            return 1;
        return 0;
    }

    /** Highest specificity among the rates a pass picked (0 when it picked none). */
    private static int maxSpecificity(List<Rate> rates) {
        int max = 0;
        for (Rate rate : rates)
            max = Math.max(max, rateSpecificity(rate));
        return max;
    }

    /**
     * Orders rate candidates for tie-breaking: the more specific rate first, then the lower id - the
     * same keys as the rate ORDER BY of compute_document_prices (V0092). Only the order among equally
     * priced candidates matters, since the selection loop compares prices strictly.
     */
    private static int compareRateSpecificity(Rate r1, Rate r2) {
        int result = Integer.compare(rateSpecificity(r2), rateSpecificity(r1));
        return result != 0 ? result : Long.compare(primaryKeyAsLong(r1), primaryKeyAsLong(r2));
    }

    private static long primaryKeyAsLong(Rate rate) {
        Object primaryKey = Entities.getPrimaryKey(rate);
        return primaryKey instanceof Number ? ((Number) primaryKey).longValue() : 0;
    }

    /**
     * The stay length compared with a rate's minDay (rate_block_length in compute_document_prices):
     * once the block reaches minDay, days outside the rate's [startDate, endDate] are not counted -
     * e.g. a discount valid 3-11 August with minDay 9 ignores a free day on 2 August.
     */
    private static int rateBlockLength(List<AttendanceBill> bas, LocalDate startDate, LocalDate endDate, int minDay) {
        if (bas.size() < minDay)
            return bas.size();
        int length = 0;
        for (AttendanceBill ba : bas) {
            LocalDate date = ba.getDate();
            if ((startDate == null || !date.isBefore(startDate)) && (endDate == null || !date.isAfter(endDate)))
                length++;
        }
        return length;
    }

    /**
     * The rate's min deposit percentage on a given day, as compute_rate_min_deposit computes it: before
     * cutoffDate minDeposit applies, then minDeposit2 until cutoffDate2, and so on up to minDeposit6; an
     * unset cutoff date ends the sequence. An unset percentage falls back to the default. A negative
     * value, which compute_document_prices reads as a fixed amount, is not supported here.
     */
    private static int rateMinDepositPercent(Rate rate, LocalDate date) {
        LocalDate[] cutoffDates = { rate.getCutoffDate(), rate.getCutoffDate2(), rate.getCutoffDate3(), rate.getCutoffDate4(), rate.getCutoffDate5() };
        Integer[] minDeposits = { rate.getMinDeposit(), rate.getMinDeposit2(), rate.getMinDeposit3(), rate.getMinDeposit4(), rate.getMinDeposit5(), rate.getMinDeposit6() };
        int tier = 0;
        while (tier < cutoffDates.length && cutoffDates[tier] != null && !date.isBefore(cutoffDates[tier]))
            tier++;
        return Objects.coalesce(minDeposits[tier], DEFAULT_MIN_DEPOSIT_PERCENT);
    }

    /** Collects all attendance dates for the given item across all site/item bills in the document. */
    private static Set<LocalDate> collectAttendanceDates(Item withItem, DocumentBill documentBill) {
        Set<LocalDate> dates = new HashSet<>();
        for (SiteItemBill bill : documentBill.getSiteItemBills()) {
            if (Entities.samePrimaryKey(bill.getSiteItem().getItem(), withItem)) {
                for (AttendanceBill ab : bill.getAttendanceBills()) {
                    dates.add(ab.getDate());
                }
            }
        }
        return dates;
    }

    /**
     * Accommodation night dates (each dated by its check-in day N) across all live 'acco'-family
     * bills, for the per-day withAccommodation residency match. Cancelled lines never become bills
     * (Kbs2PriceAlgorithm), so these are all live nights. The "acco" literal matches
     * KnownItemFamily.ACCOMMODATION and the SQL engine's 'acco' check, avoiding a module dependency
     * on modality-base-shared-knownitems.
     */
    private static Set<LocalDate> collectAccommodationNights(DocumentBill documentBill) {
        Set<LocalDate> nights = new HashSet<>();
        for (SiteItemBill bill : documentBill.getSiteItemBills()) {
            Item item = bill.getSiteItem().getItem();
            if (item != null && item.getFamily() != null && "acco".equals(item.getFamily().getCode())) {
                for (AttendanceBill ab : bill.getAttendanceBills())
                    nights.add(ab.getDate());
            }
        }
        return nights;
    }

    /** Returns shareOwnerQuantity from the DocumentLine matching this bill's site+item, or 1 if not set. */
    private int getShareOwnerQuantity(DocumentAggregate documentAggregate) {
        Site site = siteItem.getSite();
        Item item = siteItem.getItem();
        return documentAggregate.getDocumentLines().stream()
                .filter(dl -> Entities.samePrimaryKey(dl.getSite(), site) && Entities.samePrimaryKey(dl.getItem(), item))
                .map(dl -> dl.getShareOwnerQuantity())
                .filter(q -> q != null && q > 0)
                .findFirst()
                .orElse(1);
    }

    private int getRatePrice(Rate rate, DocumentAggregate documentAggregate) {
        int price = rate.getPrice();
        Document document = documentAggregate.getDocument();
        Person person = document.getPerson();
        Integer age = document.getAge();
        if (age == null && person != null) {
            age = person.getAge();
            if (age == null) {
                LocalDate birthDate = person.getBirthDate();
                if (birthDate != null) {
                    LocalDate startDate = documentAggregate.getEvent().getStartDate();
                    age = birthDate.until(startDate).getYears();
                }
            }
        }
        boolean unemployed = Booleans.isTrue(document.isUnemployed());
        if (person != null && Booleans.isTrue(person.isUnemployed()))
            unemployed = true;
        boolean facilityFee = Booleans.isTrue(document.isPersonFacilityFee());
        if (person != null && Booleans.isTrue(person.isFacilityFee()))
            facilityFee = true;
        /*var workingVisit = document.person_workingVisit || document.person && document.person.workingVisit;
        var guest = document.person_guest || document.person && document.person.guest;
        var resident = document.person_resident || document.person && document.person.resident;
        var resident2 = document.person_resident2 || document.person && document.person.resident2;
        var discoveryReduced = document.person_discoveryReduced || document.person && document.person.discoveryReduced;
        var discovery = document.person_discovery || document.person && document.person.discovery;*/
        if (age != null) {
            if (rate.getAge1Max() != null && age <= rate.getAge1Max()) {
                price = rate.getAge1Price() != null ? rate.getAge1Price() : price * (100 - rate.getAge1Discount()) / 100;
                childRateApplied = true; // To improve as it's not yet certain at this point this rate will be finally applied
            } else if (rate.getAge2Max() != null && age <= rate.getAge2Max()) {
                price = rate.getAge2Price() != null ? rate.getAge2Price() : price * (100 - rate.getAge2Discount()) / 100;
                childRateApplied = true; // To improve as it's not yet certain at this point this rate will be finally applied
            } else if (rate.getAge3Max() != null && age <= rate.getAge3Max()) {
                price = rate.getAge3Price() != null ? rate.getAge3Price() : price * (100 - rate.getAge3Discount()) / 100;
                childRateApplied = true; // To improve as it's not yet certain at this point this rate will be finally applied
            }
        }
        if (!childRateApplied) {
            /*if (workingVisit && (rate.workingVisit_price || rate.workingVisit_discount))
                price = (rate.workingVisit_price || rate.workingVisit_price === 0) ? rate.workingVisit_price : price * (100 - rate.workingVisit_discount) / 100;
            else if (guest && (rate.guest_price || rate.guest_discount))
                price = (rate.guest_price || rate.guest_price === 0) ? rate.guest_price : price * (100 - rate.guest_discount) / 100;
            else if (resident && (rate.resident_price || rate.resident_discount))
                price = (rate.resident_price || rate.resident_price === 0) ? rate.resident_price : price * (100 - rate.resident_discount) / 100;
            else if (resident2 && (rate.resident2_price || rate.resident2_discount))
                price = (rate.resident2_price || rate.resident2_price === 0) ? rate.resident2_price : price * (100 - rate.resident2_discount) / 100;
            else if (discoveryReduced && (rate.discoveryReduced_price || rate.discoveryReduced_discount))
                price = (rate.discoveryReduced_price || rate.discoveryReduced_price === 0) ? rate.discoveryReduced_price : price * (100 - rate.discoveryReduced_discount) / 100;
            else if (discovery && (rate.discovery_price || rate.discovery_discount))
                price = (rate.discovery_price || rate.discovery_price === 0) ? rate.discovery_price : price * (100 - rate.discovery_discount) / 100;
            else*/
            if (unemployed && (rate.getUnemployedPrice() != null || rate.getUnemployedDiscount() != null))
                price = rate.getUnemployedPrice() != null ? rate.getUnemployedPrice() : price * (100 - rate.getUnemployedDiscount()) / 100;
            else if (facilityFee && (rate.getFacilityFeePrice() != null || rate.getFacilityFeeDiscount() != null))
                price = rate.getFacilityFeePrice() != null ? rate.getFacilityFeePrice() : price * (100 - rate.getFacilityFeeDiscount()) / 100;
        }
        return price;
    }

}

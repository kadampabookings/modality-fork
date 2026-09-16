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
    /** Specificity of a run of attendances no rate applies to. */
    private static final int NO_RATE_SPECIFICITY = -1;

    private final SiteItem siteItem;
    private final PriceDocumentLineEvent priceDocumentLineEvent;
    private final List<AttendanceBill> attendanceBills = new ArrayList<>();
    private boolean childRateApplied;

    private int totalPrice = -1;
    private int minDeposit = -1;
    // Specificity runs of the last real-total computation, with the pass that set each run's price (true
    // per-day, false fixed, null no rate applied). The min-deposit computation reuses these decisions when
    // it finds the same runs.
    private List<SpecificityRun> totalRuns;
    private Boolean[] totalRunPerDayWins;

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
            // Attendances are priced in runs of consecutive days whose most specific applicable rates share
            // one specificity, each run using only rates of that specificity: as in compute_document_prices, a
            // rate bound to the event wins over one bound to its event type, which wins over a generic rate,
            // whatever their prices. Within a run the per-day and fixed passes are priced separately and one
            // of them sets the run's price; the min deposit comes from that same pass, since the lower of the
            // two deposits could come from rates that did not set the price. The min-deposit computation
            // reuses the passes chosen by the last real-total one for the same runs, decided under the same
            // pricing state (getRatePrice sets childRateApplied), and prices the totals itself only otherwise.
            price = 0;
            RateContext context = buildRateContext(documentBill);
            if (context != null) {
                List<SpecificityRun> runs = specificityRuns(documentBill, context);
                Boolean[] cachedDecisions = minDeposit && sameRuns(runs, totalRuns) ? totalRunPerDayWins : null;
                Boolean[] decisions = new Boolean[runs.size()];
                for (int index = 0; index < runs.size(); index++) {
                    SpecificityRun run = runs.get(index);
                    if (run.specificity == NO_RATE_SPECIFICITY)
                        continue;
                    Boolean perDayWins;
                    int perDayTotal = 0, fixedTotal = 0;
                    if (cachedDecisions != null)
                        perDayWins = cachedDecisions[index];
                    else {
                        List<Rate> perDayPicked = new ArrayList<>(), fixedPicked = new ArrayList<>();
                        RunPrice perDayRun = computeRunPrice(documentBill, context, run, false, true, perDayPicked);
                        RunPrice fixedRun = computeRunPrice(documentBill, context, run, false, false, fixedPicked);
                        perDayTotal = perDayRun.price;
                        fixedTotal = fixedRun.price;
                        perDayWins = perDayPassWins(perDayRun, fixedRun, perDayPicked, fixedPicked);
                    }
                    decisions[index] = perDayWins;
                    if (perDayWins == null)
                        continue;
                    if (minDeposit) {
                        // Both deposit passes run, in the original order, so the attendance prices they leave
                        // behind are the same as before.
                        int perDayDeposit = computeRunPrice(documentBill, context, run, true, true, null).price;
                        int fixedDeposit = computeRunPrice(documentBill, context, run, true, false, null).price;
                        price += perDayWins ? perDayDeposit : fixedDeposit;
                    } else
                        price += perDayWins ? perDayTotal : fixedTotal;
                }
                if (!minDeposit) {
                    totalRuns = runs;
                    totalRunPerDayWins = decisions;
                }
            }
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
     * The bill's candidate rates (per-day and fixed) and what checking them needs, or null when there is
     * nothing to price (no attendance, or no policy).
     */
    private RateContext buildRateContext(DocumentBill documentBill) {
        List<AttendanceBill> bas = attendanceBills;
        if (bas.isEmpty())
            return null;
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
        if (policyAggregate == null)
            return null;
        boolean inPerson = documentAggregate.getDocument().isInPerson();
        Instant creationInstant = Objects.coalesce(documentAggregate.getDocument().getCreationDate(), Instant.now());
        Event event = documentAggregate.getEvent();
        ZoneId eventZoneId = event == null ? null : event.getEventZoneId();
        if (eventZoneId == null)
            eventZoneId = ZoneOffset.UTC;
        LocalDateTime creationDateTime = LocalDateTime.ofInstant(creationInstant, eventZoneId);
        boolean documentEarlyBird = Booleans.isTrue(documentAggregate.getDocument().isEarlyBird());
        // Rates for this site/item, both per-day and fixed (the runs and passes select among them)
        List<Rate> rates = policyAggregate.filterRatesStreamOfSiteAndItem(siteItem.getSite(), siteItem.getItem())
            // Only rates compute_document_prices would consider for this booking (rate_matches_document).
            // The policy loader is broader: it also loads a repeated event's rates and every rate on the
            // event's own sites.
            .filter(r -> matchesBookedEvent(r, event))
            .filter(r -> inPerson ? r.isApplicableToInPerson() : r.isApplicableToOnline())
            .filter(r -> !Booleans.isTrue(r.isEarlyBird()) || (documentEarlyBird && !documentBill.ignoreEarlyBirdRates))
            .filter(r -> Rates.isAndApplicableAtDateAndOverPeriod(r, creationDateTime, firstDay, lastDay))
            .collect(Collectors.toCollection(ArrayList::new));
        rates.sort(SiteItemBill::compareRateSpecificity);
        // Day limits are fixed for this block, so computed once per rate.
        Map<Rate, RateDayLimits> rateLimits = new IdentityHashMap<>();
        for (Rate rate : rates)
            rateLimits.put(rate, rateDayLimits(rate, rateBlockLength(bas, rate.getStartDate(), rate.getEndDate(), Objects.coalesce(rate.getMinDay(), 1))));
        // Pre-compute attendance dates for each withItem referenced by the rates. IdentityHashMap is
        // correct here: within the same policy store, the same entity always has the same object reference.
        Map<Item, Set<LocalDate>> withItemDateCache = new IdentityHashMap<>();
        for (Rate rate : rates) {
            Item wi = rate.getWithItem();
            if (wi != null)
                withItemDateCache.computeIfAbsent(wi, k -> collectAttendanceDates(k, documentBill));
        }
        return new RateContext(rates, rateLimits, creationDateTime.toLocalDate(), Booleans.isTrue(siteItem.getItem().isTemporal()),
            withItemDateCache, collectAccommodationNights(documentBill));
    }

    /**
     * Splits the bill's attendances into runs of consecutive days sharing the same top specificity. A day's
     * specificity is that of its most specific applicable rate, except that a rate the stay is too short for
     * only caps the price: it never raises the day above the most specific other rate, and sets the day's
     * specificity only when no other rate applies. A day no rate applies to gets NO_RATE_SPECIFICITY.
     */
    private List<SpecificityRun> specificityRuns(DocumentBill documentBill, RateContext context) {
        List<SpecificityRun> runs = new ArrayList<>();
        for (int day = 0; day < attendanceBills.size(); day++) {
            int topSpecificity = NO_RATE_SPECIFICITY, topCeilingSpecificity = NO_RATE_SPECIFICITY;
            for (Rate rate : context.rates) {
                if (rateCoverage(documentBill, context, rate, day, day + 1) == 0)
                    continue;
                if (context.limitsOf(rate).belowMinDay)
                    topCeilingSpecificity = Math.max(topCeilingSpecificity, rateSpecificity(rate));
                else
                    topSpecificity = Math.max(topSpecificity, rateSpecificity(rate));
            }
            int specificity = topSpecificity != NO_RATE_SPECIFICITY ? topSpecificity : topCeilingSpecificity;
            SpecificityRun lastRun = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (lastRun != null && lastRun.specificity == specificity)
                lastRun.end = day + 1;
            else
                runs.add(new SpecificityRun(day, day + 1, specificity));
        }
        return runs;
    }

    /**
     * Prices a run of attendances with its rates of one kind (per-day or fixed) that compete in the run
     * (see competesInRun). The price is Integer.MIN_VALUE when none applies to the run's first day; the
     * pass stops at the first attendance none applies to, which coveredDays reports. When
     * {@code pickedRatesOut} is not null, the chosen rates are added to it.
     */
    private RunPrice computeRunPrice(DocumentBill documentBill, RateContext context, SpecificityRun run, boolean minDeposit, boolean perDayRates, List<Rate> pickedRatesOut) {
        List<AttendanceBill> bas = attendanceBills;
        DocumentAggregate documentAggregate = documentBill.getDocumentAggregate();
        int price = Integer.MIN_VALUE;
        int consumedDays = run.start;
        while (consumedDays < run.end) {
            // selecting the cheapest rate for the next attendances
            PriceMemo cheapest = null;
            PriceMemo second = null;
            for (Rate rate : context.rates) {
                if (Booleans.isTrue(rate.isPerDay()) != perDayRates || !competesInRun(context, rate, run))
                    continue;
                int consumableDays = rateCoverage(documentBill, context, rate, consumedDays, run.end);
                if (consumableDays == 0)
                    continue;
                // For per-person rates, multiply by shareOwnerQuantity from the matching DocumentLine.
                int quantity = Booleans.isTrue(rate.isPerPerson()) ? getShareOwnerQuantity(documentAggregate) : 1;
                RateDayLimits limits = context.limitsOf(rate);
                int ratePrice = getRatePrice(rate, documentAggregate) * quantity * limits.priceFactor;
                int dailyPrice = ratePrice / consumableDays;
                // Ugly workaround for Online January retreat 2021 because this price algorithm is not always correct.
                // Ex: 1 week (actually 8 days): £70, 2 weeks (actually 15 days): £120, 3 weeks (actually 22 days): £180
                // => For the 3-weeks case, this price algorithm computes £190 instead of £180 because it considers
                // the £120 rate is the cheaper (because £120 / 15 < £180 / 22) and then add £70 for the remaining days.
                /* Commented in KBS3
                if (rate.id === 27510 && remainingDays === maxDay) // £120 rate with 22 remaining days
                    dailyPrice = ratePrice / (maxDay + 1);*/ // Changing the daily price comparison to £180 / 23 to make it the cheapest
                PriceMemo memo = new PriceMemo(rate, dailyPrice, ratePrice, consumableDays, limits.priceFactor != 1);
                if (cheapest == null)
                    cheapest = memo;
                else if (isBetterCandidate(memo, cheapest)) {
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
        return new RunPrice(price, consumedDays - run.start);
    }

    /**
     * How many consecutive attendances from {@code dayIndex} (and before {@code runEnd}) the rate can
     * price, or 0 when it does not apply to that attendance.
     */
    private int rateCoverage(DocumentBill documentBill, RateContext context, Rate rate, int dayIndex, int runEnd) {
        List<AttendanceBill> bas = attendanceBills;
        boolean perDay = Booleans.isTrue(rate.isPerDay());
        // Ignoring rates for long stay discounts (if requested)
        if (documentBill.ignoreLongStayDiscount && !perDay) // assuming that a rate not per day is a long stay discount TODO: check this more carefully
            return 0;
        // Ignoring expired rates (such as early birds discounts)
        LocalDate offDate = rate.getOffDate();
        if (offDate != null && context.creationDate.isAfter(offDate))
            return 0;
        // Ignoring rates that are not in the range of dates
        LocalDate date = bas.get(dayIndex).getDate();
        LocalDate startDate = rate.getStartDate();
        LocalDate endDate = rate.getEndDate();
        if (startDate != null && date.isBefore(startDate) || endDate != null && date.isAfter(endDate))
            return 0;
        // arrivingOrLeaving: rate applies only on the arrival/departure edge of a contiguous
        // run of this item's attendances (mirrors compute_document_prices). An interior day -
        // same document line and a <=1 day gap on both sides - is skipped; the first and last
        // attendance are always edges.
        if (Booleans.isTrue(rate.isArrivingOrLeaving()) && dayIndex > 0 && dayIndex < bas.size() - 1) {
            AttendanceBill prev = bas.get(dayIndex - 1), cur = bas.get(dayIndex), next = bas.get(dayIndex + 1);
            boolean edge = !Entities.samePrimaryKey(prev.getDocumentLine(), cur.getDocumentLine())
                        || !Entities.samePrimaryKey(next.getDocumentLine(), cur.getDocumentLine())
                        || cur.getDate().toEpochDay() - prev.getDate().toEpochDay() > 1
                        || next.getDate().toEpochDay() - cur.getDate().toEpochDay() > 1;
            if (!edge)
                return 0;
        }
        // withItem: rate only applies when the companion item is also booked.
        // Skipped entirely when ignoreWithItemRates is set (standard price baseline).
        Item withItem = rate.getWithItem();
        if (withItem != null && documentBill.ignoreWithItemRates)
            return 0;
        Set<LocalDate> withItemDates = null;
        boolean withItemTemporal = false;
        if (withItem != null) {
            withItemDates = context.withItemDateCache.get(withItem);
            if (withItemDates == null || withItemDates.isEmpty())
                return 0;
            withItemTemporal = Booleans.isTrue(withItem.isTemporal());
            if (context.thisItemTemporal && withItemTemporal && !withItemDates.contains(date))
                return 0;
        }
        // withAccommodation: residential/non-residential per-day match. null = applies to all;
        // true = only when resident this day; false = only when not. Resident on teaching day D
        // when a live accommodation night was booked on D or D-1 (a night dated N covers the
        // evening of N and the morning of N+1). Non-temporal items have no day, so they fall
        // back to document-level accommodation existence.
        Boolean withAcco = rate.isWithAccommodation();
        if (withAcco != null) {
            boolean resident = context.thisItemTemporal
                ? context.accoNights.contains(date) || context.accoNights.contains(date.minusDays(1))
                : !context.accoNights.isEmpty();
            if (withAcco != resident)
                return 0;
        }
        // A stay shorter than minDay drops the rate when minDayCeiling is false (see rateDayLimits)
        RateDayLimits limits = context.limitsOf(rate);
        if (limits.belowMinDay && Boolean.FALSE.equals(rate.isMinDayCeiling()))
            return 0;
        int consumableDays = Math.min(runEnd - dayIndex, limits.maxDay);
        // Cap by the rate's endDate: without this a 3-day rate ending on July 26 would claim all 4
        // days of a July 24-27 block and win on daily price while leaving July 27 uncovered
        // (same as the React SiteItemBill).
        if (endDate != null) {
            int daysInRange = 0;
            for (int j = dayIndex; j < runEnd && !bas.get(j).getDate().isAfter(endDate); j++)
                daysInRange++;
            consumableDays = Math.min(consumableDays, daysInRange);
        }
        // withItem temporal overlap: per-day → cap to consecutive overlap; fixed → all-or-nothing over the
        // whole block, as with_item_block_applicable does in compute_document_prices (skip unless the
        // companion is attended on every date of the stay).
        if (withItemDates != null && context.thisItemTemporal && withItemTemporal) {
            if (perDay) {
                int overlap = 0;
                for (int j = dayIndex; j < dayIndex + consumableDays; j++) {
                    if (withItemDates.contains(bas.get(j).getDate())) overlap++;
                    else break;
                }
                consumableDays = Math.min(consumableDays, overlap);
            } else {
                for (AttendanceBill ba : bas)
                    if (!withItemDates.contains(ba.getDate()))
                        return 0;
            }
        }
        return consumableDays;
    }

    /**
     * Whether {@code memo} should replace {@code current} as the chosen candidate. Prefer the candidate
     * covering more days, then the lower daily price compared exactly (int daily prices can round to
     * false ties). Same rule as the React SiteItemBill: a rate covering 4 days at £21 beats one covering
     * 3 days at £15 for a 4-day block, which would leave a day to price with a second rate. A ceiling is
     * compared on daily price only: the days it covers come from its cap, not from a rate covering the
     * stay, and compute_document_prices lets it win only once it is cheaper.
     */
    private static boolean isBetterCandidate(PriceMemo memo, PriceMemo current) {
        if (!memo.ceiling() && !current.ceiling() && memo.consumableDays() != current.consumableDays())
            return memo.consumableDays() > current.consumableDays();
        return isCheaperPerDay(memo, current);
    }

    /**
     * Whether the per-day pass sets a run's price rather than the fixed pass; null when neither pass found
     * an applicable rate. A pass that stopped short leaves days unpriced, so the pass pricing more of the
     * run wins, then the cheaper one. An exact tie follows the candidate order of compute_document_prices:
     * the more specifically bound rates, then the higher price per max day, then the per-day pass.
     */
    private static Boolean perDayPassWins(RunPrice perDay, RunPrice fixed, List<Rate> perDayPicked, List<Rate> fixedPicked) {
        if (perDay.coveredDays == 0 && fixed.coveredDays == 0)
            return null;
        if (perDay.coveredDays != fixed.coveredDays)
            return perDay.coveredDays > fixed.coveredDays;
        if (perDay.price != fixed.price)
            return perDay.price < fixed.price;
        int perDaySpecificity = maxSpecificity(perDayPicked), fixedSpecificity = maxSpecificity(fixedPicked);
        if (perDaySpecificity != fixedSpecificity)
            return perDaySpecificity > fixedSpecificity;
        return maxOrderKey(perDayPicked) >= maxOrderKey(fixedPicked);
    }

    /** Highest binding specificity (rateSpecificity) among a pass's rates; -1 when there are none. */
    private static int maxSpecificity(List<Rate> rates) {
        int max = NO_RATE_SPECIFICITY;
        for (Rate rate : rates)
            max = Math.max(max, rateSpecificity(rate));
        return max;
    }

    /**
     * Highest price-per-max-day ORDER BY key of compute_document_prices among a pass's rates: the price
     * divided by the rate's max days (1 per day, maxDay or 1 when fixed), in integer division like the
     * SQL. It reads the listed price, where the SQL uses the booker's unit price after discounts.
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

    /** Whether two lists of runs have the same boundaries and specificities. */
    private static boolean sameRuns(List<SpecificityRun> runs, List<SpecificityRun> other) {
        if (other == null || runs.size() != other.size())
            return false;
        for (int i = 0; i < runs.size(); i++) {
            SpecificityRun run = runs.get(i), otherRun = other.get(i);
            if (run.start != otherRun.start || run.end != otherRun.end || run.specificity != otherRun.specificity)
                return false;
        }
        return true;
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

    /**
     * Whether a rate competes in a run: a rate bound as specifically as the run, or a rate the stay is too
     * short for (it only caps the price) bound at least as specifically - as compute_document_prices lets a
     * ceiling compete at the lower of its own specificity and the most specific other applicable rate.
     */
    private static boolean competesInRun(RateContext context, Rate rate, SpecificityRun run) {
        int specificity = rateSpecificity(rate);
        return context.limitsOf(rate).belowMinDay ? specificity >= run.specificity : specificity == run.specificity;
    }

    /**
     * Orders rate candidates by specificity, then by lower id, the lower id deciding exact ties (the last key
     * of compute_document_prices' ORDER BY).
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
     * A rate's day limits given its rateBlockLength, applying minDay as compute_document_prices does: when the
     * stay, counted within the rate's dates, is shorter than minDay, the rate is dropped if minDayCeiling is
     * false (see rateCoverage); otherwise a per-day rate becomes a fixed price capped at minDay days, so
     * people just short of the minimum never pay more than those reaching it (ex: a 30% discount from 14
     * days caps the price of 12 or 13 days at the price of 14).
     */
    private static RateDayLimits rateDayLimits(Rate rate, int rateBlockLength) {
        int minDay = Objects.coalesce(rate.getMinDay(), 1);
        boolean belowMinDay = rateBlockLength < minDay;
        int maxDay = Booleans.isTrue(rate.isPerDay()) ? 1 : Objects.coalesce(rate.getMaxDay(), 10000);
        boolean capped = belowMinDay && maxDay == 1;
        return new RateDayLimits(capped ? minDay : maxDay, belowMinDay, capped ? minDay : 1);
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

    /** The bill's candidate rates and what checking them needs, computed once per computePrice call. */
    private static final class RateContext {
        /** Per-day and fixed rates of this site/item matching the booking, sorted by compareRateSpecificity. */
        final List<Rate> rates;
        /** Day limits of each rate for this bill. */
        final Map<Rate, RateDayLimits> rateLimits;
        /** Document creation date in the event time zone (now for a booking not created yet). */
        final LocalDate creationDate;
        final boolean thisItemTemporal;
        final Map<Item, Set<LocalDate>> withItemDateCache;
        /** Accommodation night dates, for withAccommodation. */
        final Set<LocalDate> accoNights;

        RateContext(List<Rate> rates, Map<Rate, RateDayLimits> rateLimits, LocalDate creationDate, boolean thisItemTemporal, Map<Item, Set<LocalDate>> withItemDateCache, Set<LocalDate> accoNights) {
            this.rates = rates;
            this.rateLimits = rateLimits;
            this.creationDate = creationDate;
            this.thisItemTemporal = thisItemTemporal;
            this.withItemDateCache = withItemDateCache;
            this.accoNights = accoNights;
        }

        /** The rate's day limits for this bill (all context rates have them). */
        RateDayLimits limitsOf(Rate rate) {
            return rateLimits.get(rate);
        }
    }

    /**
     * Consecutive attendances, [start, end) indexes in the bill, whose most specific applicable rates share
     * the same specificity (see specificityRuns); NO_RATE_SPECIFICITY when no rate applies to them.
     */
    private static final class SpecificityRun {
        final int start;
        int end;
        final int specificity;

        SpecificityRun(int start, int end, int specificity) {
            this.start = start;
            this.end = end;
            this.specificity = specificity;
        }
    }

    /** A pass's price for a run, and how many of the run's attendances it priced. */
    private static final class RunPrice {
        final int price;
        final int coveredDays;

        RunPrice(int price, int coveredDays) {
            this.price = price;
            this.coveredDays = coveredDays;
        }
    }

    /** A rate's day limits for this bill (see rateDayLimits). */
    private static final class RateDayLimits {
        final int maxDay;
        /** The stay, counted within the rate's dates, is shorter than the rate's minDay. */
        final boolean belowMinDay;
        /** Multiplier of the rate price: minDay when a per-day rate becomes a capped price, 1 otherwise. */
        final int priceFactor;

        RateDayLimits(int maxDay, boolean belowMinDay, int priceFactor) {
            this.maxDay = maxDay;
            this.belowMinDay = belowMinDay;
            this.priceFactor = priceFactor;
        }
    }

}

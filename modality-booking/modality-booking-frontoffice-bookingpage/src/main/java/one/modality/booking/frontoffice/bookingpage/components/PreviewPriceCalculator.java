package one.modality.booking.frontoffice.bookingpage.components;

import one.modality.base.shared.entities.AttendanceMode;
import one.modality.base.shared.entities.Item;
import one.modality.base.shared.entities.ScheduledItem;
import one.modality.base.shared.entities.SiteItem;
import one.modality.base.shared.entities.util.ScheduledItems;
import one.modality.base.shared.knownitems.KnownItemFamily;
import one.modality.booking.client.workingbooking.WorkingBooking;
import one.modality.ecommerce.policy.service.PolicyAggregate;
import one.modality.ecommerce.shared.pricecalculator.DocumentBill;
import one.modality.ecommerce.shared.pricecalculator.SiteItemBill;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for calculating preview prices using temporary WorkingBookings.
 *
 * <p>This class implements the "temporary WorkingBooking" pattern for calculating
 * accurate preview prices before items are actually booked. The pattern ensures
 * that preview prices shown to users match the actual prices they'll be charged,
 * including all discounts and rate variations.</p>
 *
 * <p>Pattern usage:</p>
 * <pre>{@code
 * int price = PreviewPriceCalculator.calculateAccommodationPrice(
 *     policyAggregate,
 *     accommodationItem,
 *     arrivalDate,
 *     departureDate,
 *     AttendanceMode.IN_PERSON
 * );
 * }</pre>
 *
 * <p>Benefits over manual calculation:</p>
 * <ul>
 *   <li>Accurate pricing including long-stay discounts</li>
 *   <li>Correct rate variations by date (early arrival, late departure)</li>
 *   <li>Person-specific discounts (age, facility fee, unemployed)</li>
 *   <li>Consistency between preview and actual prices</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see WorkingBooking
 */
public final class PreviewPriceCalculator {

    private PreviewPriceCalculator() {
        // Utility class - prevent instantiation
    }

    /**
     * Calculates the total price for an accommodation option over a date range.
     *
     * @param policyAggregate the policy containing rates and scheduled items
     * @param accommodationItem the accommodation Item to price
     * @param arrivalDate the arrival date (inclusive)
     * @param departureDate the departure date (inclusive)
     * @param attendanceMode IN_PERSON or ONLINE
     * @return the calculated total price in cents
     */
    public static int calculateAccommodationPrice(
            PolicyAggregate policyAggregate,
            Item accommodationItem,
            LocalDate arrivalDate,
            LocalDate departureDate,
            AttendanceMode attendanceMode) {

        if (policyAggregate == null || accommodationItem == null) {
            return 0;
        }

        // Create temporary WorkingBooking
        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, attendanceMode);

        // Find and book accommodation scheduled items for the date range
        List<ScheduledItem> accommodationItems = filterScheduledItemsForDateRange(
            policyAggregate.getScheduledItems(),
            accommodationItem,
            arrivalDate,
            departureDate
        );

        if (!accommodationItems.isEmpty()) {
            tempBooking.bookScheduledItems(accommodationItems, true);
        }

        return tempBooking.getLatestBookingPriceCalculator().calculateTotalPrice();
    }

    /**
     * Calculates the total price for accommodation + teaching over a date range.
     *
     * @param policyAggregate the policy containing rates and scheduled items
     * @param accommodationItem the accommodation Item to price (null for day visitor)
     * @param arrivalDate the arrival date (inclusive)
     * @param departureDate the departure date (inclusive)
     * @param attendanceMode IN_PERSON or ONLINE
     * @return the calculated total price in cents
     */
    public static int calculateAccommodationWithTeachingPrice(
            PolicyAggregate policyAggregate,
            Item accommodationItem,
            LocalDate arrivalDate,
            LocalDate departureDate,
            AttendanceMode attendanceMode) {

        if (policyAggregate == null) {
            return 0;
        }

        // Create temporary WorkingBooking
        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, attendanceMode);

        // Book teaching items for the date range
        List<ScheduledItem> teachingItems = filterScheduledItemsByFamilyAndDateRange(
            policyAggregate.getScheduledItems(),
            KnownItemFamily.TEACHING,
            arrivalDate,
            departureDate
        );
        if (!teachingItems.isEmpty()) {
            tempBooking.bookScheduledItems(teachingItems, true);
        }

        // Book accommodation items (if not day visitor)
        if (accommodationItem != null) {
            List<ScheduledItem> accommodationItems = filterScheduledItemsForDateRange(
                policyAggregate.getScheduledItems(),
                accommodationItem,
                arrivalDate,
                departureDate
            );
            if (!accommodationItems.isEmpty()) {
                tempBooking.bookScheduledItems(accommodationItems, true);
            }
        }

        return tempBooking.getLatestBookingPriceCalculator().calculateTotalPrice();
    }

    /**
     * Calculates the price for a specific item over a date range.
     *
     * @param policyAggregate the policy containing rates and scheduled items
     * @param item the Item to price
     * @param dates the list of dates to book
     * @param attendanceMode IN_PERSON or ONLINE
     * @return the calculated price in cents
     */
    public static int calculateItemPrice(
            PolicyAggregate policyAggregate,
            Item item,
            List<LocalDate> dates,
            AttendanceMode attendanceMode) {

        if (policyAggregate == null || item == null || dates == null || dates.isEmpty()) {
            return 0;
        }

        // Create temporary WorkingBooking
        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, attendanceMode);

        // Find scheduled items for the given item and dates
        List<ScheduledItem> scheduledItems = policyAggregate.getScheduledItems().stream()
            .filter(si -> si.getItem() != null && si.getItem().getPrimaryKey().equals(item.getPrimaryKey()))
            .filter(si -> si.getDate() != null && dates.contains(si.getDate()))
            .collect(Collectors.toList());

        if (!scheduledItems.isEmpty()) {
            tempBooking.bookScheduledItems(scheduledItems, true);
        }

        return tempBooking.getLatestBookingPriceCalculator().calculateTotalPrice();
    }

    /**
     * Calculates the price for scheduled items directly.
     *
     * @param policyAggregate the policy containing rates
     * @param scheduledItems the scheduled items to price
     * @param attendanceMode IN_PERSON or ONLINE
     * @return the calculated price in cents
     */
    public static int calculateScheduledItemsPrice(
            PolicyAggregate policyAggregate,
            List<ScheduledItem> scheduledItems,
            AttendanceMode attendanceMode) {

        if (policyAggregate == null || scheduledItems == null || scheduledItems.isEmpty()) {
            return 0;
        }

        // Create temporary WorkingBooking
        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, attendanceMode);
        tempBooking.bookScheduledItems(scheduledItems, true);

        return tempBooking.getLatestBookingPriceCalculator().calculateTotalPrice();
    }

    /**
     * Calculates the price with facility fee discount (member rate).
     *
     * @param policyAggregate the policy containing rates and scheduled items
     * @param scheduledItems the scheduled items to price
     * @param attendanceMode IN_PERSON or ONLINE
     * @param applyFacilityFee true to apply facility fee discount (member rate)
     * @return the calculated price in cents
     */
    public static int calculateScheduledItemsPrice(
            PolicyAggregate policyAggregate,
            List<ScheduledItem> scheduledItems,
            AttendanceMode attendanceMode,
            boolean applyFacilityFee) {

        if (policyAggregate == null || scheduledItems == null || scheduledItems.isEmpty()) {
            return 0;
        }

        // Create temporary WorkingBooking
        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, attendanceMode);
        tempBooking.bookScheduledItems(scheduledItems, true);

        if (applyFacilityFee) {
            tempBooking.applyFacilityFeeRate(true);
        }

        return tempBooking.getLatestBookingPriceCalculator().calculateTotalPrice();
    }

    // === Full Booking Price ===

    /**
     * Calculates the total price for a complete booking: teaching + meals + accommodation.
     *
     * <p>Creates a temporary WorkingBooking and books all three item families
     * for the date range, then returns the combined total including all discounts.</p>
     *
     * @param policyAggregate   the policy containing rates and scheduled items
     * @param accommodationItem the accommodation Item to price (null for day visitor)
     * @param mealItems         the meal Items to include (e.g., lunch, dinner); null or empty to skip meals
     * @param arrivalDate       the arrival date (inclusive)
     * @param departureDate     the departure date (inclusive)
     * @param attendanceMode    IN_PERSON or ONLINE
     * @return the calculated total price in cents
     */
    public static int calculateFullBookingPrice(
            PolicyAggregate policyAggregate,
            Item accommodationItem,
            List<Item> mealItems,
            LocalDate arrivalDate,
            LocalDate departureDate,
            AttendanceMode attendanceMode) {

        if (policyAggregate == null) {
            return 0;
        }

        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, attendanceMode);

        // Book teaching items for the date range
        List<ScheduledItem> teachingItems = filterScheduledItemsByFamilyAndDateRange(
            policyAggregate.getScheduledItems(), KnownItemFamily.TEACHING, arrivalDate, departureDate);
        if (!teachingItems.isEmpty()) {
            tempBooking.bookScheduledItems(teachingItems, true);
        }

        // Book accommodation items (if not day visitor)
        if (accommodationItem != null) {
            List<ScheduledItem> accomItems = filterScheduledItemsForDateRange(
                policyAggregate.getScheduledItems(), accommodationItem, arrivalDate, departureDate);
            if (!accomItems.isEmpty()) {
                tempBooking.bookScheduledItems(accomItems, true);
            }
        }

        // Book meal items
        if (mealItems != null) {
            for (Item mealItem : mealItems) {
                List<ScheduledItem> mealScheduled = filterScheduledItemsForDateRange(
                    policyAggregate.getScheduledItems(), mealItem, arrivalDate, departureDate);
                if (!mealScheduled.isEmpty()) {
                    tempBooking.bookScheduledItems(mealScheduled, true);
                }
            }
        }

        return tempBooking.getLatestBookingPriceCalculator().calculateTotalPrice();
    }

    // === Price Breakdown ===

    /**
     * Result of a price breakdown calculation, mapping each booked item to its total price.
     * Prices are in cents and include all applicable discounts.
     */
    public static final class PriceBreakdown {
        private final int totalPrice;
        private final Map<Object, Integer> itemPrices;

        PriceBreakdown(int totalPrice, Map<Object, Integer> itemPrices) {
            this.totalPrice = totalPrice;
            this.itemPrices = itemPrices;
        }

        /** The combined total price of all items. */
        public int getTotalPrice() { return totalPrice; }

        /** Price for a specific item, or 0 if not found. */
        public int getItemPrice(Item item) {
            return item != null ? itemPrices.getOrDefault(item.getPrimaryKey(), 0) : 0;
        }

        /** Price for a specific item by primary key, or 0 if not found. */
        public int getItemPrice(Object itemPrimaryKey) {
            return itemPrices.getOrDefault(itemPrimaryKey, 0);
        }

        /** All item primary keys in this breakdown. */
        public Set<Object> getItemKeys() { return itemPrices.keySet(); }
    }

    /**
     * Calculates prices broken down by item for a set of items over a date range.
     *
     * <p>Creates a temporary WorkingBooking, books all provided items, then
     * extracts the per-item price from the DocumentBill's SiteItemBills.</p>
     *
     * @param policyAggregate the policy containing rates and scheduled items
     * @param items           the Items to price
     * @param arrivalDate     the arrival date (inclusive)
     * @param departureDate   the departure date (inclusive)
     * @param attendanceMode  IN_PERSON or ONLINE
     * @return a PriceBreakdown containing per-item prices and total
     */
    public static PriceBreakdown calculateItemPriceBreakdown(
            PolicyAggregate policyAggregate,
            List<Item> items,
            LocalDate arrivalDate,
            LocalDate departureDate,
            AttendanceMode attendanceMode) {

        if (policyAggregate == null || items == null || items.isEmpty()) {
            return new PriceBreakdown(0, Collections.emptyMap());
        }

        WorkingBooking tempBooking = new WorkingBooking(policyAggregate, attendanceMode);

        // Book all items for the date range
        for (Item item : items) {
            List<ScheduledItem> scheduledItems = filterScheduledItemsForDateRange(
                policyAggregate.getScheduledItems(), item, arrivalDate, departureDate);
            if (!scheduledItems.isEmpty()) {
                tempBooking.bookScheduledItems(scheduledItems, true);
            }
        }

        // Compute the DocumentBill to get per-SiteItem breakdown
        DocumentBill documentBill = tempBooking.getLatestBookingPriceCalculator().computeDocumentBill();
        if (documentBill == null) {
            return new PriceBreakdown(0, Collections.emptyMap());
        }

        // getTotalPrice() triggers lazy computation of SiteItemBill prices
        int totalPrice = documentBill.getTotalPrice();

        // Extract per-item prices from SiteItemBills
        Map<Object, Integer> itemPrices = new HashMap<>();
        for (SiteItemBill siteItemBill : documentBill.getSiteItemBills()) {
            SiteItem siteItem = siteItemBill.getSiteItem();
            if (siteItem != null && siteItem.getItem() != null) {
                Object itemKey = siteItem.getItem().getPrimaryKey();
                itemPrices.merge(itemKey, siteItemBill.getTotalPrice(), Integer::sum);
            }
        }

        return new PriceBreakdown(totalPrice, itemPrices);
    }

    // === Helper Methods ===

    /**
     * Filters scheduled items for a specific item and date range.
     */
    private static List<ScheduledItem> filterScheduledItemsForDateRange(
            List<ScheduledItem> allItems,
            Item item,
            LocalDate startDate,
            LocalDate endDate) {

        if (allItems == null || item == null) {
            return List.of();
        }

        return allItems.stream()
            .filter(si -> si.getItem() != null && si.getItem().getPrimaryKey().equals(item.getPrimaryKey()))
            .filter(si -> isDateInRange(si.getDate(), startDate, endDate))
            .collect(Collectors.toList());
    }

    /**
     * Filters scheduled items by item family and date range.
     */
    private static List<ScheduledItem> filterScheduledItemsByFamilyAndDateRange(
            List<ScheduledItem> allItems,
            KnownItemFamily family,
            LocalDate startDate,
            LocalDate endDate) {

        if (allItems == null) {
            return List.of();
        }

        return ScheduledItems.filterFamily(allItems.stream(), family)
            .filter(si -> isDateInRange(si.getDate(), startDate, endDate))
            .collect(Collectors.toList());
    }

    /**
     * Checks if a date is within a range (inclusive).
     */
    private static boolean isDateInRange(LocalDate date, LocalDate startDate, LocalDate endDate) {
        if (date == null) {
            return false;
        }
        if (startDate == null && endDate == null) {
            return true;
        }
        if (startDate != null && date.isBefore(startDate)) {
            return false;
        }
        if (endDate != null && date.isAfter(endDate)) {
            return false;
        }
        return true;
    }
}

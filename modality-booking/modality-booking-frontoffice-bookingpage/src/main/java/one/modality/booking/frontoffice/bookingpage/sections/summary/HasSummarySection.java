package one.modality.booking.frontoffice.bookingpage.sections.summary;

import one.modality.booking.frontoffice.bookingpage.BookingFormSection;

import java.time.LocalDate;

/**
 * Interface for the "Summary" section of a booking form.
 * This section displays a review of the booking before submission,
 * including attendee info, event details, and price breakdown.
 *
 * @author Bruno Salmon
 * @see BookingFormSection
 */
public interface HasSummarySection extends BookingFormSection {

    /**
     * Represents a line item in the price breakdown.
     * Supports flexible display of ItemFamily + Item names.
     */
    class PriceLine {
        private final String familyName;
        private final String itemName;
        private final String dates;
        private final int amount;

        /**
         * Creates a price line with separate family and item names.
         *
         * @param familyName ItemFamily name (can be null)
         * @param itemName Item name
         * @param dates date range string (can be null)
         * @param amount price in cents
         */
        public PriceLine(String familyName, String itemName, String dates, int amount) {
            this.familyName = familyName;
            this.itemName = itemName;
            this.dates = dates;
            this.amount = amount;
        }

        public String getFamilyName() { return familyName; }
        public String getItemName() { return itemName; }
        public String getDates() { return dates; }
        public int getAmount() { return amount; }
    }

    /**
     * Types of additional options.
     */
    enum AdditionalOptionType {
        AUDIO_RECORDING,
        MEAL,
        PARKING,
        OTHER
    }

    /**
     * Represents an additional option selected for the booking.
     */
    class AdditionalOption {
        private final AdditionalOptionType type;
        private final String name;
        private final String description;

        public AdditionalOption(AdditionalOptionType type, String name, String description) {
            this.type = type;
            this.name = name;
            this.description = description;
        }

        public AdditionalOptionType getType() { return type; }
        public String getName() { return name; }
        public String getDescription() { return description; }
    }

    /**
     * Sets the attendee name to display.
     */
    void setAttendeeName(String name);

    /**
     * Sets the attendee email to display.
     */
    void setAttendeeEmail(String email);

    /**
     * Sets the event name to display.
     */
    void setEventName(String name);

    /**
     * Sets the event date range to display.
     */
    void setEventDates(LocalDate start, LocalDate end);

    /**
     * Sets the rate type label (e.g., "Member", "Standard").
     */
    void setRateType(String rateType);

    /**
     * Adds a price line item to the breakdown with separate family and item names.
     *
     * @param familyName ItemFamily name (can be null)
     * @param itemName Item name
     * @param dates date range string (can be null)
     * @param amount price in cents
     */
    void addPriceLine(String familyName, String itemName, String dates, int amount);

    /**
     * Clears all price lines.
     */
    void clearPriceLines();

    /**
     * Adds a generic additional option.
     */
    void addAdditionalOption(AdditionalOptionType type, String name, String description);

    /**
     * Clears all additional options.
     */
    void clearAdditionalOptions();

    /**
     * Refreshes the price breakdown display.
     */
    void refreshPriceBreakdown();
}

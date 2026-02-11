package one.modality.booking.frontoffice.bookingpage.sections.options;

import one.modality.base.shared.entities.BookablePeriod;
import one.modality.booking.frontoffice.bookingpage.BookingFormSection;

import java.util.function.Consumer;

/**
 * Interface for a rate type/pricing section of a booking form.
 * This section displays the programme info (title, dates) and pricing information.
 *
 * <p>The section supports:</p>
 * <ul>
 *   <li>Rate type selection (standard vs member pricing)</li>
 *   <li>Programme/bookable period display</li>
 *   <li>Price calculation from daily rates</li>
 * </ul>
 *
 * @author Bruno Salmon
 * @see BookingFormSection
 */
public interface HasRateTypeSection extends BookingFormSection {

    /**
     * Rate types for pricing tiers.
     */
    enum RateType {
        STANDARD("standard"),
        MEMBER("member");

        private final String id;

        RateType(String id) {
            this.id = id;
        }

        public String getId() { return id; }
    }

    /**
     * Returns the currently selected rate type.
     */
    RateType getSelectedRateType();

    void setOnPackageSelected(Consumer<BookablePeriod> handler);

    /**
     * Sets the callback for when the rate type changes.
     */
    void setOnRateTypeChanged(Consumer<RateType> handler);

    /**
     * Sets the displayed price for Standard rate.
     * Price should be calculated externally using WorkingBooking + PriceCalculator.
     *
     * <p>Default implementation does nothing. Override in implementations
     * that support externally calculated prices.</p>
     *
     * @param priceInCents the price in cents
     */
    default void setStandardPrice(int priceInCents) {
        // Default: no-op. Override in implementations that support external price setting.
    }

    /**
     * Sets the displayed price for Member rate.
     * Price should be calculated externally using WorkingBooking + PriceCalculator.
     *
     * <p>Default implementation does nothing. Override in implementations
     * that support externally calculated prices.</p>
     *
     * @param priceInCents the price in cents
     */
    default void setMemberPrice(int priceInCents) {
        // Default: no-op. Override in implementations that support external price setting.
    }
}

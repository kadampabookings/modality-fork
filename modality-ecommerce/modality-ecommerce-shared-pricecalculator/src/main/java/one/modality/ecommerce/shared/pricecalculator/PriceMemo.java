package one.modality.ecommerce.shared.pricecalculator;

import one.modality.base.shared.entities.Rate;

/**
 * A candidate rate for the next attendances. {@code ceiling} marks a per-day rate turned into a price capped
 * at its minDay days, because the stay is shorter than minDay.
 *
 * @author Bruno Salmon
 */
@SuppressWarnings("unusable-by-js")
record PriceMemo(Rate rate, int dailyPrice, int price, int consumableDays, boolean ceiling) {
}

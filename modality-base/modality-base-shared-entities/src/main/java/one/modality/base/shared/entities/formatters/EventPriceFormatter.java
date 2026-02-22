package one.modality.base.shared.entities.formatters;

import one.modality.base.shared.domainmodel.formatters.PriceFormatter;
import one.modality.base.shared.entities.Country;
import one.modality.base.shared.entities.Currency;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.Organization;

/**
 * @author Bruno Salmon
 */
public final class EventPriceFormatter extends PriceFormatter {

    public EventPriceFormatter(Event event) {
        super(getEventCurrencySymbol(event));
    }

    public static Currency getEventCurrency(Event event) {
        if (event == null) return null;

        // Apply fallback logic similar to PolicyAggregate
        Currency currency = event.getCurrency();
        if (currency == null) {

            Organization organization = event.getOrganization();
            if (organization != null) {
                currency = organization.getCurrency();
                if (currency == null) {
                    Country country = organization.getCountry();
                    if (country != null) {
                        currency = country.getCurrency();
                    }
                }
            }
        }

        return currency;
    }

    public static String getEventCurrencySymbol(Event event) {
        Currency eventCurrency = getEventCurrency(event);
        return eventCurrency != null ? eventCurrency.getSymbol() : null;
    }

    public static String formatWithCurrency(Object value, Event event) {
        return formatWithCurrency(value, getEventCurrencySymbol(event));
    }

    public static String format(Object value, Event event, boolean withCurrency) {
        return formatWithCurrency(value, withCurrency ? getEventCurrencySymbol(event) : "");
    }
}

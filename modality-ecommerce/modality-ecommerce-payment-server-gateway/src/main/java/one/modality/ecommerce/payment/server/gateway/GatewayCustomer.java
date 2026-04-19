package one.modality.ecommerce.payment.server.gateway;

/**
 * @author Bruno Salmon
 */
public record GatewayCustomer(
    String id,
    String firstName,
    String lastName,
    String email,
    String phone,
    String address,
    String city,
    String zipCode,
    String state,
    String country,
    String countryCode  // ISO 3166-1 alpha-2 (e.g. "GB"), may be null
) {}

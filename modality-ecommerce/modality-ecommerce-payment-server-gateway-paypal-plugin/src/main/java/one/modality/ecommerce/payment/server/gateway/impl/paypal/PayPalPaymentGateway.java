package one.modality.ecommerce.payment.server.gateway.impl.paypal;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.fetch.Fetch;
import dev.webfx.platform.fetch.FetchOptions;
import dev.webfx.platform.fetch.Headers;
import dev.webfx.platform.fetch.Response;
import dev.webfx.platform.resource.Resource;
import dev.webfx.platform.util.uuid.Uuid;
import one.modality.ecommerce.payment.*;
import one.modality.ecommerce.payment.server.gateway.*;
import one.modality.ecommerce.payment.server.gateway.impl.util.RestApiOneTimeHtmlResponsesCache;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static one.modality.ecommerce.payment.server.gateway.impl.paypal.PayPalRestApiJob.*;

/**
 * PayPal payment gateway implementation using the PayPal Orders API v2.
 *
 * <p>Supports two flows, selected via {@link GatewayInitiatePaymentArgument#preferredFormType()}:
 *
 * <p><b>Redirected flow</b> ({@link PaymentFormType#REDIRECTED}):
 * <ol>
 *   <li>{@link #initiatePayment} — obtains an OAuth token, creates a PayPal order, and returns
 *       a redirect URL pointing to the PayPal approval page.</li>
 *   <li>The user approves the payment on PayPal and is redirected back to {@code returnUrl} with
 *       {@code ?token=ORDER_ID} appended by PayPal.</li>
 *   <li>{@link #completePayment} — captures the approved order using the {@code paypal_orderId}
 *       from the front-office payload and returns the final payment result.</li>
 * </ol>
 *
 * <p><b>Embedded flow</b> ({@link PaymentFormType#EMBEDDED}):
 * <ol>
 *   <li>{@link #initiatePayment} — obtains an OAuth token, creates a PayPal order, bakes the
 *       order ID into an HTML page that loads the PayPal JS SDK, caches it, and returns an
 *       embedded URL pointing to the one-time cache endpoint.</li>
 *   <li>The front-office loads that URL in an iFrame; the PayPal Buttons render inside it.
 *       When the user approves, the JS calls back with {@code paypal_orderId}.</li>
 *   <li>{@link #completePayment} — same as the redirected flow: captures the order.</li>
 * </ol>
 *
 * <p>Required account parameters: {@code client_id}, {@code client_secret}.
 *
 * <p>HTTP calls use {@link Fetch}, backed by the Vert.x WebClient on the server — fully
 * non-blocking on the Vert.x event loop, no extra threads required.
 *
 * @author Bruno Salmon
 */
public final class PayPalPaymentGateway implements PaymentGateway {

    private static final boolean DEBUG_LOG = true;

    static final String GATEWAY_NAME = "PayPal";

    static final String PAYPAL_LIVE_API_BASE_URL    = "https://api-m.paypal.com";
    static final String PAYPAL_SANDBOX_API_BASE_URL = "https://api-m.sandbox.paypal.com";

    // Well-known approval redirect URLs (avoids parsing the links[] array in the order response)
    private static final String PAYPAL_LIVE_APPROVE_BASE_URL    = "https://www.paypal.com/checkoutnow?token=";
    private static final String PAYPAL_SANDBOX_APPROVE_BASE_URL = "https://www.sandbox.paypal.com/checkoutnow?token=";

    // PayPal JS SDK — same URL for both sandbox and live; sandbox is selected by the client_id
    private static final String PAYPAL_SDK_URL = "https://www.paypal.com/sdk/js";

    // Source: https://developer.paypal.com/tools/sandbox/card-testing/
    // Expiry: any future date. CVV: any 3 digits (4 for Amex). Zip: not required by PayPal sandbox.
    private static final SandboxCard[] SANDBOX_CARDS = {
        new SandboxCard("Visa - Success",             "4032 0310 2374 3809", null, "123", null),
        new SandboxCard("Mastercard - Success",       "5425 2334 3010 9903", null, "123", null),
        new SandboxCard("Amex - Success",             "3742 5101 8720 0018", null, "1234", null),
        new SandboxCard("Discover - Success",         "6011 0009 9130 0009", null, "123", null),
        new SandboxCard("Visa - Insufficient funds",  "4000 0000 0000 0051", null, "123", null),
        new SandboxCard("Visa - Do not honor",        "4000 0000 0000 0002", null, "123", null),
        new SandboxCard("Visa - Card expired",        "4000 0000 0000 0069", null, "123", null),
        new SandboxCard("Visa - Invalid CVV",         "4000 0000 0000 0127", null, "123", null),
    };

    /**
     * Cache of React resume-payment URLs, keyed by moneyTransferId (String).
     * Populated at payment initiation so the server-side PayPal return handler can redirect the
     * browser back to the correct React page after capturing the order.
     */
    static final Map<String, String> REACT_RETURN_URL_CACHE = new ConcurrentHashMap<>();

    /**
     * Cache of React cancel URLs, keyed by moneyTransferId (String).
     * Populated at payment initiation so the server-side PayPal cancel handler can redirect the
     * browser back to the correct React page after recording the cancellation.
     */
    static final Map<String, String> REACT_CANCEL_URL_CACHE = new ConcurrentHashMap<>();

    private static final String HTML_TEMPLATE = Resource.getText(
        Resource.toUrl("modality-paypal-payment-form-iframe.html", PayPalPaymentGateway.class));

    // Seamless Card Fields script — renders PayPal card input fields directly (no button click needed).
    // Used when paymentMethodType == "CARD". Requires Advanced Credit and Debit Card Payments on the
    // merchant's PayPal account; falls back to the PayPal-hosted checkout redirect if not eligible.
    private static final String CARD_FIELDS_SCRIPT_TEMPLATE = Resource.getText(
        Resource.toUrl("modality-paypal-card-fields.js", PayPalPaymentGateway.class));

    private static final List<GatewayPaymentMethodInfo> SUPPORTED_METHODS = List.of(
        new GatewayPaymentMethodInfo(PaymentMethod.CARD,       PaymentFormType.EMBEDDED),
        new GatewayPaymentMethodInfo(PaymentMethod.PAYPAL,     PaymentFormType.EMBEDDED),
        // Google Pay and Apple Pay routed through PayPal use the same in-app SDK
        // params (orderId / clientId / currency) as the PAYPAL method — the React
        // side keys off `selectedMethodKey` to load the right SDK component
        // (paypal.Googlepay() / paypal.Applepay()) and open the wallet sheet.
        // The server doesn't differentiate at the order-creation level: PayPal
        // creates a single order regardless of which funding source ultimately
        // approves it. Eligibility (device support + PayPal merchant capability)
        // is filtered client-side via the Google Pay / Apple Pay JS APIs.
        new GatewayPaymentMethodInfo(PaymentMethod.GOOGLE_PAY, PaymentFormType.EMBEDDED),
        new GatewayPaymentMethodInfo(PaymentMethod.APPLE_PAY,  PaymentFormType.EMBEDDED)
    );

    @Override
    public String getName() {
        return GATEWAY_NAME;
    }

    @Override
    public List<GatewayPaymentMethodInfo> getSupportedPaymentMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Future<GatewayInitiatePaymentResult> initiatePayment(GatewayInitiatePaymentArgument argument) {
        try {
            String clientId     = argument.getRequiredAccountParameter("client_id");
            String clientSecret = argument.getRequiredAccountParameter("client_secret");
            boolean live        = argument.isLive();
            String apiBaseUrl   = live ? PAYPAL_LIVE_API_BASE_URL : PAYPAL_SANDBOX_API_BASE_URL;
            GatewayOrder order  = argument.order();

            if (DEBUG_LOG)
                Console.log("[PayPal][DEBUG] initiatePayment - amount=" + order.amount()
                    + ", currencyCode=" + argument.currencyCode() + ", live=" + live
                    + ", formType=" + argument.preferredFormType()
                    + ", paymentMethodType=" + argument.paymentMethod()
                    + ", orderName=" + order.longName());

            // Build server-side return/cancel URLs so we can update the DB before redirecting
            // back to React. The original React URLs are cached by moneyTransferId for later.
            String reactReturnUrl = argument.returnUrl();
            String reactCancelUrl = argument.cancelUrl();
            String reactOrigin    = extractOrigin(reactReturnUrl);
            String returnEndpoint  = live ? PAYPAL_LIVE_RETURN_ENDPOINT  : PAYPAL_SANDBOX_RETURN_ENDPOINT;
            String cancelEndpoint  = live ? PAYPAL_LIVE_CANCEL_ENDPOINT  : PAYPAL_SANDBOX_CANCEL_ENDPOINT;
            String serverReturnUrl = reactOrigin != null
                ? reactOrigin + returnEndpoint.replace(":moneyTransferId", argument.paymentId())
                : reactReturnUrl; // fallback: use React URL directly if origin cannot be extracted
            String serverCancelUrl = reactOrigin != null
                ? reactOrigin + cancelEndpoint.replace(":moneyTransferId", argument.paymentId())
                : reactCancelUrl;
            if (reactReturnUrl != null)
                REACT_RETURN_URL_CACHE.put(argument.paymentId(), reactReturnUrl);
            if (reactCancelUrl != null)
                REACT_CANCEL_URL_CACHE.put(argument.paymentId(), reactCancelUrl);

            return getAccessToken(apiBaseUrl, clientId, clientSecret)
                .compose(accessToken -> createPayPalOrderId(apiBaseUrl, accessToken, argument, order, serverReturnUrl, serverCancelUrl))
                .compose(orderId -> {
                    if (argument.preferredFormType() == PaymentFormType.EMBEDDED)
                        return Future.succeededFuture(buildEmbeddedResult(orderId, clientId, argument.currencyCode(), live, argument.paymentMethod()));
                    // REDIRECTED (default)
                    String approveUrl = (live ? PAYPAL_LIVE_APPROVE_BASE_URL : PAYPAL_SANDBOX_APPROVE_BASE_URL) + orderId;
                    if (DEBUG_LOG)
                        Console.log("[PayPal][DEBUG] initiatePayment - redirect approve URL: " + approveUrl);
                    return Future.succeededFuture(GatewayInitiatePaymentResult.createRedirectInitiatePaymentResult(live, approveUrl));
                });

        } catch (Exception e) {
            return Future.failedFuture(GATEWAY_NAME + " initiatePayment() failed: " + e.getMessage());
        }
    }

    /**
     * Maps our PaymentMethodType name to the PayPal JS SDK funding source constant name.
     * null → no fundingSource specified (PayPal shows all eligible methods).
     */
    private static String toPayPalFundingSource(PaymentMethod paymentMethodId) {
        if (paymentMethodId == null) return null;
        return switch (paymentMethodId) {
            case CARD       -> "paypal.FUNDING.CARD";
            case PAYPAL     -> "paypal.FUNDING.PAYPAL";
            case GOOGLE_PAY -> "paypal.FUNDING.GOOGLEPAY";
            case APPLE_PAY  -> "paypal.FUNDING.APPLEPAY";
            case VENMO      -> "paypal.FUNDING.VENMO";
            default         -> null;
        };
    }

    private static GatewayInitiatePaymentResult buildEmbeddedResult(String orderId, String clientId, String currencyCode, boolean live, PaymentMethod paymentMethodId) {
        String fallbackRedirectUrl = (live ? PAYPAL_LIVE_APPROVE_BASE_URL : PAYPAL_SANDBOX_APPROVE_BASE_URL) + orderId;

        // CARD: use PayPal Card Fields (seamless — fields render directly, no button click needed).
        // Requires Advanced Credit and Debit Card Payments on the merchant's PayPal account.
        // Falls back to the PayPal-hosted checkout redirect via fallbackRedirectUrl if not eligible.
        if (paymentMethodId == PaymentMethod.CARD) {
            // Both components needed: card-fields for the primary path, buttons for the fallback
            // when the merchant has not enabled Advanced Credit and Debit Card Payments.
            String paypalSdkUrl = PAYPAL_SDK_URL + "?client-id=" + clientId + "&currency=" + currencyCode + "&intent=capture&components=buttons,card-fields";
            String paymentFormContent = CARD_FIELDS_SCRIPT_TEMPLATE
                .replace("${paypal_orderId}", orderId)
                .replace("${paypal_sdkUrl}",  paypalSdkUrl);
            if (DEBUG_LOG)
                Console.log("[PayPal][DEBUG] initiatePayment - Card Fields seamless, fallback: " + fallbackRedirectUrl);
            return GatewayInitiatePaymentResult.createEmbeddedContentInitiatePaymentResult(live, true, paymentFormContent, false, live ? null : SANDBOX_CARDS)
                .withFallbackRedirectUrl(fallbackRedirectUrl);
        }

        // PAYPAL wallet: in-app SDK rendering — the client renders the PayPal Buttons widget
        // directly in the host page (same origin, no iframe). One user click on the PayPal
        // button opens the PayPal popup. The widget calls back via createOrder/onApprove,
        // and React forwards the approved order id to completePayment().
        //
        // GOOGLE_PAY / APPLE_PAY routed through PayPal: same in-app SDK params, but the
        // client loads the SDK with components=googlepay / applepay and uses
        // paypal.Googlepay() / paypal.Applepay() to open the native wallet sheet inside
        // the user-gesture click. The PayPal order id is captured the same way once the
        // wallet token is confirmed via paypal.<Wallet>().confirmOrder().
        if (paymentMethodId == PaymentMethod.PAYPAL
                || paymentMethodId == PaymentMethod.GOOGLE_PAY
                || paymentMethodId == PaymentMethod.APPLE_PAY) {
            if (DEBUG_LOG)
                Console.log("[PayPal][DEBUG] initiatePayment - in-app SDK (" + paymentMethodId + "), orderId=" + orderId + ", fallback: " + fallbackRedirectUrl);
            return GatewayInitiatePaymentResult.createPayPalInAppInitiatePaymentResult(
                live, orderId, clientId, currencyCode, fallbackRedirectUrl, live ? null : SANDBOX_CARDS);
        }

        // Other methods (e.g. funding-specific buttons): iframe with the PayPal Buttons SDK.
        // The wallet button click is inherent to PayPal's security model (popup requires a user gesture).
        String fundingSource = toPayPalFundingSource(paymentMethodId);
        // disable-funding=card prevents the card SDK components from loading when we want
        // PayPal wallet only. Note: "paypal" is NOT a valid disable-funding value per PayPal
        // docs — for that direction, fundingSource alone is sufficient to limit rendering.
        String disableFunding = "";
        String paypalSdkUrl = PAYPAL_SDK_URL + "?client-id=" + clientId + "&currency=" + currencyCode + "&intent=capture" + disableFunding;
        // ${fundingSource} sits at the top of the paypal.Buttons({}) config object so it is
        // processed first. It expands to "fundingSource: paypal.FUNDING.PAYPAL,\n                "
        // (with trailing newline+indent) or to "" when no specific method is requested.
        String fundingSourceSnippet = fundingSource != null
            ? "fundingSource: " + fundingSource + ",\n                "
            : "";
        String paymentFormContent = HTML_TEMPLATE
            .replace("${orderId}",       orderId)
            .replace("${paypalSdkUrl}",  paypalSdkUrl)
            .replace("${fundingSource}", fundingSourceSnippet);
        String htmlCacheKey = Uuid.randomUuid();
        RestApiOneTimeHtmlResponsesCache.registerOneTimeHtmlResponse(htmlCacheKey, paymentFormContent);
        String url = PAYPAL_LOAD_FORM_ENDPOINT.replace(":htmlCacheKey", htmlCacheKey);
        // The approval URL is the redirect fallback: if the embedded PayPal JS SDK is blocked
        // (e.g. by an ad blocker or corporate firewall), the user can still pay via redirect.
        if (DEBUG_LOG)
            Console.log("[PayPal][DEBUG] initiatePayment - embedded form URL: " + url + ", fallback: " + fallbackRedirectUrl);
        return GatewayInitiatePaymentResult.createEmbeddedUrlInitiatePaymentResult(live, false, url, true, live ? null : SANDBOX_CARDS)
            .withFallbackRedirectUrl(fallbackRedirectUrl);
    }

    static Future<String> getAccessToken(String apiBaseUrl, String clientId, String clientSecret) {
        String credentials = Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        return fetchPost(
            apiBaseUrl + "/v1/oauth2/token",
            "Basic " + credentials,
            "application/x-www-form-urlencoded",
            "grant_type=client_credentials"
        ).compose(response -> response.text().compose(responseBody -> {
            if (DEBUG_LOG)
                Console.log("[PayPal][DEBUG] getAccessToken - HTTP " + response.status() + ": " + responseBody);
            if (!response.ok())
                return Future.failedFuture("[PayPal] getAccessToken - HTTP " + response.status() + ": " + responseBody);
            ReadOnlyAstObject json = AST.parseObject(responseBody, "json");
            String accessToken = json.getString("access_token");
            if (accessToken == null)
                return Future.failedFuture("[PayPal] getAccessToken - access_token missing in response");
            return Future.succeededFuture(accessToken);
        }));
    }

    private static Future<String> createPayPalOrderId(String apiBaseUrl, String accessToken,
                                                       GatewayInitiatePaymentArgument argument,
                                                       GatewayOrder order,
                                                       String serverReturnUrl,
                                                       String serverCancelUrl) {
        String amountValue  = formatCentAmount(order.amount());
        String currencyCode = argument.currencyCode();
        // Use server-side URLs: the return handler captures the order then redirects to React;
        // the cancel handler records the cancellation in the DB then redirects to React.
        String returnUrl = serverReturnUrl;
        String cancelUrl = serverCancelUrl != null ? serverCancelUrl : returnUrl;

        // We intentionally omit the items[] breakdown from the purchase unit to avoid rounding
        // discrepancies that would cause PayPal to reject the order when item totals don't match exactly.
        StringBuilder requestBody = new StringBuilder("{")
            .append("\"intent\":\"CAPTURE\",")
            .append("\"purchase_units\":[{")
            .append("\"reference_id\":\"").append(jsonEscape(argument.paymentId())).append("\",")
            .append("\"description\":\"").append(jsonEscape(truncate(order.longName(), 127))).append("\",")
            .append("\"amount\":{\"currency_code\":\"").append(currencyCode).append("\",\"value\":\"").append(amountValue).append("\"}")
            .append("}],")
            .append("\"application_context\":{")
            .append("\"user_action\":\"PAY_NOW\",")
            .append("\"shipping_preference\":\"NO_SHIPPING\",")
            .append("\"return_url\":\"").append(jsonEscape(returnUrl)).append("\",")
            .append("\"cancel_url\":\"").append(jsonEscape(cancelUrl)).append("\"")
            .append("}");

        // Include payer info so PayPal can pre-fill the billing address in the card form
        GatewayCustomer customer = argument.customer();
        if (customer != null) {
            requestBody.append(",\"payer\":{");
            if (customer.firstName() != null || customer.lastName() != null) {
                requestBody.append("\"name\":{")
                    .append("\"given_name\":\"").append(jsonEscape(customer.firstName())).append("\",")
                    .append("\"surname\":\"").append(jsonEscape(customer.lastName())).append("\"")
                    .append("},");
            }
            if (customer.email() != null)
                requestBody.append("\"email_address\":\"").append(jsonEscape(customer.email())).append("\",");
            if (customer.phone() != null) {
                String digitsOnly = customer.phone().replaceAll("[^0-9]", "");
                if (!digitsOnly.isEmpty())
                    requestBody.append("\"phone\":{\"phone_type\":\"HOME\",\"phone_number\":{\"national_number\":\"").append(digitsOnly).append("\"}},");
            }
            if (customer.countryCode() != null) {
                requestBody.append("\"address\":{");
                if (customer.address() != null)
                    requestBody.append("\"address_line_1\":\"").append(jsonEscape(customer.address())).append("\",");
                if (customer.city() != null)
                    requestBody.append("\"admin_area_2\":\"").append(jsonEscape(customer.city())).append("\",");
                if (customer.state() != null)
                    requestBody.append("\"admin_area_1\":\"").append(jsonEscape(customer.state())).append("\",");
                if (customer.zipCode() != null)
                    requestBody.append("\"postal_code\":\"").append(jsonEscape(customer.zipCode())).append("\",");
                requestBody.append("\"country_code\":\"").append(jsonEscape(customer.countryCode())).append("\"")
                    .append("},");
            }
            // Remove trailing comma before closing payer object
            if (requestBody.charAt(requestBody.length() - 1) == ',')
                requestBody.deleteCharAt(requestBody.length() - 1);
            requestBody.append("}");
        }

        requestBody.append("}");

        if (DEBUG_LOG)
            Console.log("[PayPal][DEBUG] createPayPalOrderId - request: " + requestBody);

        return fetchPost(apiBaseUrl + "/v2/checkout/orders", "Bearer " + accessToken, "application/json", requestBody.toString())
            .compose(response -> response.text().compose(responseBody -> {
                if (DEBUG_LOG)
                    Console.log("[PayPal][DEBUG] createPayPalOrderId - HTTP " + response.status() + ": " + responseBody);
                if (!response.ok())
                    return Future.failedFuture("[PayPal] createPayPalOrderId - HTTP " + response.status() + ": " + responseBody);
                ReadOnlyAstObject json = AST.parseObject(responseBody, "json");
                String orderId = json.getString("id");
                if (orderId == null)
                    return Future.failedFuture("[PayPal] createPayPalOrderId - order id not found in response: " + responseBody);
                return Future.succeededFuture(orderId);
            }));
    }

    @Override
    public Future<GatewayCompletePaymentResult> completePayment(GatewayCompletePaymentArgument argument) {
        // After the user approves on PayPal (either via redirect or embedded buttons), the front-office
        // sends the ORDER_ID as "paypal_orderId" in the payload.
        try {
            String clientId     = argument.getAccountParameter("client_id");
            String clientSecret = argument.getAccountParameter("client_secret");
            boolean live        = argument.isLive();
            String apiBaseUrl   = live ? PAYPAL_LIVE_API_BASE_URL : PAYPAL_SANDBOX_API_BASE_URL;

            ReadOnlyAstObject payload = AST.parseObject(argument.payload(), "json");
            String paypalOrderId      = payload.getString("paypal_orderId");

            if (DEBUG_LOG)
                Console.log("[PayPal][DEBUG] completePayment - paypalOrderId=" + paypalOrderId + ", live=" + live);

            if (paypalOrderId == null)
                return Future.failedFuture("[PayPal] completePayment - paypal_orderId not found in payload");

            return getAccessToken(apiBaseUrl, clientId, clientSecret)
                .compose(accessToken -> captureOrder(apiBaseUrl, accessToken, paypalOrderId));

        } catch (Exception e) {
            return Future.failedFuture(GATEWAY_NAME + " completePayment() failed: " + e.getMessage());
        }
    }

    static Future<GatewayCompletePaymentResult> captureOrder(String apiBaseUrl, String accessToken, String orderId) {
        return fetchPost(
            apiBaseUrl + "/v2/checkout/orders/" + orderId + "/capture",
            "Bearer " + accessToken,
            "application/json",
            "{}"
        ).compose(response -> response.text().map(responseBody -> {
            if (DEBUG_LOG)
                Console.log("[PayPal][DEBUG] captureOrder - HTTP " + response.status() + ": " + responseBody);
            ReadOnlyAstObject json = AST.parseObject(responseBody, "json");
            if (response.ok()) {
                String orderStatus = json.getString("status");
                // Use capture ID as transactionRef (more specific than order ID).
                // Falls back to the order ID if the capture ID cannot be extracted.
                String captureId = extractFirstCaptureId(responseBody);
                String transactionRef = captureId != null ? captureId : orderId;
                PaymentStatus paymentStatus;
                PaymentFailureReason failureReason = null;
                if ("COMPLETED".equals(orderStatus)) {
                    paymentStatus = PaymentStatus.COMPLETED;
                } else if ("PAYER_ACTION_REQUIRED".equals(orderStatus)) {
                    paymentStatus = PaymentStatus.PENDING;
                } else {
                    paymentStatus = PaymentStatus.FAILED;
                    failureReason = mapPayPalErrorToFailureReason(json);
                }
                return new GatewayCompletePaymentResult(responseBody, transactionRef, orderStatus, paymentStatus, failureReason);
            } else {
                PaymentFailureReason failureReason = mapPayPalErrorToFailureReason(json);
                return new GatewayCompletePaymentResult(responseBody, null, null, PaymentStatus.FAILED, failureReason);
            }
        }));
    }

    /** Executes an async POST request via the WebFX Fetch API. */
    private static Future<Response> fetchPost(String url, String authHeader, String contentType, String body) {
        return Fetch.fetch(url, new FetchOptions()
            .setMethod("POST")
            .setHeaders(Headers.create()
                .append("Authorization", authHeader)
                .append("Content-Type", contentType)
                .append("Accept", "application/json"))
            .setBody(body));
    }

    /**
     * Extracts the first capture ID from a PayPal capture-order response body by navigating
     * {@code purchase_units[0].payments.captures[0].id} via a simple string search.
     */
    static String extractFirstCaptureId(String jsonBody) {
        int capturesPos = jsonBody.indexOf("\"captures\"");
        if (capturesPos < 0)
            return null;
        int idPos = jsonBody.indexOf("\"id\"", capturesPos);
        if (idPos < 0)
            return null;
        int colonPos = jsonBody.indexOf(":", idPos + 4);
        if (colonPos < 0)
            return null;
        int start = jsonBody.indexOf("\"", colonPos) + 1;
        int end   = jsonBody.indexOf("\"", start);
        if (start <= 0 || end <= start)
            return null;
        return jsonBody.substring(start, end);
    }

    static PaymentStatus mapPayPalCaptureStatusToPaymentStatus(String captureStatus) {
        if (captureStatus == null)
            return PaymentStatus.FAILED;
        return switch (captureStatus) {
            case "COMPLETED"                     -> PaymentStatus.COMPLETED;
            case "PENDING"                       -> PaymentStatus.PENDING;
            case "DECLINED", "FAILED", "VOIDED" -> PaymentStatus.FAILED;
            default                              -> PaymentStatus.FAILED;
        };
    }

    static PaymentStatus mapPayPalOrderStatusToPaymentStatus(String orderStatus) {
        if (orderStatus == null)
            return PaymentStatus.FAILED;
        return switch (orderStatus) {
            case "COMPLETED"                                               -> PaymentStatus.COMPLETED;
            case "APPROVED", "CREATED", "PAYER_ACTION_REQUIRED", "SAVED" -> PaymentStatus.PENDING;
            case "VOIDED"                                                  -> PaymentStatus.FAILED;
            default                                                        -> PaymentStatus.FAILED;
        };
    }

    static PaymentFailureReason mapPayPalErrorToFailureReason(ReadOnlyAstObject json) {
        if (json == null)
            return PaymentFailureReason.UNKNOWN_REASON;
        String name = json.getString("name");
        if (name == null)
            return PaymentFailureReason.UNKNOWN_REASON;
        return switch (name) {
            case "INSTRUMENT_DECLINED"                       -> PaymentFailureReason.DECLINED_BY_BANK;
            case "PAYER_CANNOT_PAY", "TRANSACTION_REFUSED"  -> PaymentFailureReason.DECLINED_BY_BANK;
            case "COMPLIANCE_VIOLATION"                      -> PaymentFailureReason.CARD_TYPE_NOT_ACCEPTED;
            case "RESOURCE_NOT_FOUND", "ORDER_NOT_APPROVED" -> PaymentFailureReason.GATEWAY_ERROR;
            default                                          -> PaymentFailureReason.UNKNOWN_REASON;
        };
    }

    /** Formats a minor-unit (cents) amount as a 2-decimal-place string for the PayPal Orders API. */
    static String formatCentAmount(long amountInCents) {
        return String.format("%.2f", amountInCents / 100.0);
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    /** Extracts the scheme+host (+ port if present) from a URL, e.g. "https://example.com:8080". */
    private static String extractOrigin(String url) {
        if (url == null) return null;
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return null;
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }
}

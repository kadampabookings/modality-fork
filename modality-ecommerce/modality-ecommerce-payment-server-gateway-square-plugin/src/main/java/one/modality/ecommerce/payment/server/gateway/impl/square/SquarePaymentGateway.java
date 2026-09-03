package one.modality.ecommerce.payment.server.gateway.impl.square;

import com.squareup.square.AsyncSquareClient;
import com.squareup.square.checkout.types.CreatePaymentLinkRequest;
import com.squareup.square.core.Environment;
import com.squareup.square.core.SquareApiException;
import com.squareup.square.types.*;
import com.squareup.square.types.Error;
import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.async.Promise;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.resource.Resource;
import dev.webfx.platform.util.uuid.Uuid;
import one.modality.ecommerce.payment.GatewayPaymentMethodInfo;
import one.modality.ecommerce.payment.PaymentFailureReason;
import one.modality.ecommerce.payment.PaymentFormType;
import one.modality.ecommerce.payment.PaymentMethod;
import one.modality.ecommerce.payment.PaymentStatus;
import one.modality.ecommerce.payment.SandboxCard;
import one.modality.ecommerce.payment.server.gateway.*;
import one.modality.ecommerce.payment.server.gateway.impl.util.RestApiOneTimeHtmlResponsesCache;

import java.util.ArrayList;
import java.util.List;

import static one.modality.ecommerce.payment.server.gateway.impl.square.SquareRestApiJob.SQUARE_PAYMENT_FORM_ENDPOINT;
import static one.modality.ecommerce.payment.server.gateway.impl.util.GatewayEmail.emailOrNull;

/**
 * @author Bruno Salmon
 */
public final class SquarePaymentGateway implements PaymentGateway {

    private static final boolean DEBUG_LOG = true;

    static final String GATEWAY_NAME = "Square";

    private static final String SQUARE_LIVE_WEB_PAYMENTS_SDK_URL = "https://web.squarecdn.com/v1/square.js";
    private static final String SQUARE_SANDBOX_WEB_PAYMENTS_SDK_URL = "https://sandbox.web.squarecdn.com/v1/square.js";

    private static final String CSS_TEMPLATE = Resource.getText(Resource.toUrl("square-payment-form.css", SquarePaymentGateway.class));
    private static final String SCRIPT_TEMPLATE = Resource.getText(Resource.toUrl("square-payment-form.js", SquarePaymentGateway.class));
    private static final String HTML_TEMPLATE = Resource.getText(Resource.toUrl("square-payment-form-iframe.html", SquarePaymentGateway.class))
        .replace("${square_paymentFormScript}", SCRIPT_TEMPLATE)
        .replace("${square_paymentFormCSS}", CSS_TEMPLATE);

    private static final SandboxCard[] SANDBOX_CARDS = {
        new SandboxCard("Visa - Success", "4111 1111 1111 1111", null, "111", "11111"),
        new SandboxCard("Mastercard - Success", "5105 1051 0510 5100", null, "111", "11111"),
        new SandboxCard("Discover - Success", "6011 0000 0000 0004", null, "111", "11111"),
        new SandboxCard("JCB - Success", "3569 9900 1009 5841", null, "111", null),
        new SandboxCard("American Express - Success", "6011 0000 0000 0004", null, "1111", "11111"),
        new SandboxCard("China Union Pay - Success", "6222 9888 1234 0000", null, "111", null),
        new SandboxCard("Square Gift Card - Success", "6011 0000 0000 0004", null, "111", "11111"),
        new SandboxCard("CVV incorrect", null, null, "911", null),
        new SandboxCard("Postal code incorrect", null, null, null, "99999"),
        new SandboxCard("Expiration date incorrect", null, "01/40", null, null),
        new SandboxCard("Declined number", "4000000000000002", null, null, null),
        new SandboxCard("On file auth declined", "4000000000000010", null, null, null),
        new SandboxCard("Visa - No challenge", "4800 0000 0000 0004", null, "111", "11111"),
        new SandboxCard("Mastercard - No challenge", "5222 2200 0000 0005", null, "111", "11111"),
        new SandboxCard("Discover EU - No challenge", "6011 0000 0020 1016", null, "111", "11111"),
        new SandboxCard("Visa EU - Verification code: 123456", "4310 0000 0020 1019", null, "1111", "11111"),
        new SandboxCard("Mastercard - Verification code: 123456", "5248 4800 0021 0026", null, "1111", "11111"),
        new SandboxCard("Mastercard EU - Verification code: 123456", "5500 0000 0020 1016", null, "1111", "11111"),
        new SandboxCard("American Express EU - Verification code: 123456", "3700 000002 01014", null, "1111", "11111"),
        new SandboxCard("Visa - Failed verification", "4811 1100 0000 0008", null, "1111", "11111")
    };

    private static final List<GatewayPaymentMethodInfo> SUPPORTED_METHODS = List.of(
        new GatewayPaymentMethodInfo(PaymentMethod.CARD,       PaymentFormType.EMBEDDED),
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
        boolean live = argument.isLive();
        try {
            // Reading the account parameters that have been loaded from the database by ServerPaymentServiceProvider
            String locationId = argument.getAccountParameter("location_id"); // KBS3
            if (locationId == null)
                locationId = argument.getAccountParameter("order.order.location_id"); // KBS2 (to remove later)
            if (locationId == null)
                throw new IllegalArgumentException("Missing required account parameter: location_id");
            // The currency comes from the order rather than the account, but a null reaches the
            // same template substitution below and fails the same opaque way.
            if (argument.currencyCode() == null)
                throw new IllegalArgumentException("Missing currency code for this payment");

            if (argument.preferredFormType() == PaymentFormType.EMBEDDED) {
                return initiatePaymentEmbedded(argument, locationId, live);
            }

            return initiatePaymentRedirect(argument, locationId, live);
        } catch (IllegalArgumentException e) {
            // Until now a missing account parameter reached String.replace() as a null
            // replacement and surfaced as
            //   NullPointerException: because "replacement" is null
            // from inside java.lang.String, naming neither the parameter nor the account — which
            // is what a guest booking on event 1902 produced on 2 Sep 2026. Every other gateway
            // (Stripe, PayPal, Authorize.net) already used the checked getter; Square was the
            // last one building its form from unchecked values.
            //
            // The live/test hint is worth logging because it is the usual reason a parameter set
            // comes back empty: live and test parameters are separate rows, and only the set
            // matching this payment is read. It goes to the server log, where the NPE used to
            // appear; the reply crosses the bus to the browser, so it says no more than its
            // siblings do.
            Console.error(GATEWAY_NAME + " initiatePayment() failed: " + e.getMessage()
                + " — this payment is " + (live ? "live" : "in testing mode") + ", so only GatewayParameter"
                + " rows with " + (live ? "live" : "test") + "=true are read, for this money account"
                + " or for its gateway company", e);
            return Future.failedFuture(GATEWAY_NAME + " initiatePayment() failed: " + e.getMessage());
        } catch (Exception e) {
            return Future.failedFuture(GATEWAY_NAME + " initiatePayment() failed: " + e.getMessage());
        }
    }

    private Future<GatewayInitiatePaymentResult> initiatePaymentEmbedded(GatewayInitiatePaymentArgument argument, String locationId, boolean live) {
        // Our Square gateway script implementation supports seamless integration.
        String appId = argument.getRequiredAccountParameter("app_id");
        boolean seamless = argument.favorSeamless()
            // && argument.isOriginOnHttps() // Maybe would be better to not use seamless integration on http, but commented for now as iFrame integration is not working well in browser (ex: WebPaymentForm fitHeight not working well)
            ;

        if (DEBUG_LOG)
            Console.log("[Square][DEBUG] initiatePayment - live = " + live + ", appId = " + appId + ", locationId = " + locationId + ", seamless = " + seamless);

        // Merchant's ISO 3166-1 alpha-2 country code, required by Square's paymentRequest()
        // for wallet methods (Apple Pay). Sourced from the event's organisation in the database.
        // Uppercased — Square's SDK rejects lowercase codes (e.g. 'gb') with InvalidPaymentRequestError.
        String countryCode = argument.merchantCountryCode();
        if (countryCode == null) countryCode = "US"; // safe fallback if organisation has no country set
        else countryCode = countryCode.toUpperCase();
        String paymentMethodId = (argument.paymentMethod() != null ? argument.paymentMethod() : PaymentMethod.CARD).name();

        String template = seamless ? SCRIPT_TEMPLATE : HTML_TEMPLATE;
        String paymentFormContent = template
            .replace("${modality_amount}", String.valueOf(argument.order().amount()))
            .replace("${modality_currencyCode}", argument.currencyCode())
            .replace("${modality_seamless}", String.valueOf(seamless))
            .replace("${square_webPaymentsSDKUrl}", live ? SQUARE_LIVE_WEB_PAYMENTS_SDK_URL : SQUARE_SANDBOX_WEB_PAYMENTS_SDK_URL)
            .replace("${square_appId}", appId)
            .replace("${square_locationId}", locationId)
            .replace("${square_countryCode}", countryCode)
            .replace("${modality_paymentMethodId}", paymentMethodId);
        if (DEBUG_LOG) {
            Console.log("[Square][DEBUG] initiatePayment - content = " + paymentFormContent);
        }
        SandboxCard[] sandboxCards = live ? null : SANDBOX_CARDS;

        GatewayInitiatePaymentResult embeddedResult;
        if (seamless) {
            embeddedResult = GatewayInitiatePaymentResult.createEmbeddedContentInitiatePaymentResult(live, true, paymentFormContent, false, sandboxCards);
        } else { // In other cases, we embed the page in a WebView/iFrame that can be loaded through https (assuming this server is on https)
            String htmlCacheKey = Uuid.randomUuid();
            RestApiOneTimeHtmlResponsesCache.registerOneTimeHtmlResponse(htmlCacheKey, paymentFormContent);
            String url = SQUARE_PAYMENT_FORM_ENDPOINT.replace(":htmlCacheKey", htmlCacheKey);
            embeddedResult = GatewayInitiatePaymentResult.createEmbeddedUrlInitiatePaymentResult(live, false, url, false, sandboxCards);
        }

        // Also create a Square-hosted checkout URL to use as a fallback if the embedded
        // form fails to load (e.g. Square CDN blocked by a browser extension).
        // If checkout URL creation fails we still return the embedded result without a fallback.
        return createSquareCheckoutUrl(argument, locationId, live)
            .map(embeddedResult::withFallbackRedirectUrl)
            .recover(err -> {
                Console.error("[Square] Failed to create fallback redirect URL for embedded form: " + err.getMessage());
                return Future.succeededFuture(embeddedResult);
            });
    }

    private Future<GatewayInitiatePaymentResult> initiatePaymentRedirect(GatewayInitiatePaymentArgument argument, String locationId, boolean live) {
        if (DEBUG_LOG) {
            GatewayOrder modalityOrder = argument.order();
            Console.log("[Square][DEBUG] initiatePaymentRedirect - amount = " + modalityOrder.amount() + ", currencyCode = " + argument.currencyCode() + ", live = " + live + ", locationId = " + locationId + ", orderName = " + modalityOrder.longName());
        }
        return createSquareCheckoutUrl(argument, locationId, live)
            .map(checkoutUrl -> GatewayInitiatePaymentResult.createRedirectInitiatePaymentResult(live, checkoutUrl));
    }

    /** Creates a Square-hosted checkout URL for the given payment argument. */
    private Future<String> createSquareCheckoutUrl(GatewayInitiatePaymentArgument argument, String locationId, boolean live) {
        String accessToken = argument.getAccountParameter("access_token");
        AsyncSquareClient client = AsyncSquareClient.builder()
            .environment(live ? Environment.PRODUCTION : Environment.SANDBOX)
            .token(accessToken)
            .build();

        if (DEBUG_LOG)
            Console.log("[Square][DEBUG] createSquareCheckoutUrl - live = " + live + ", accessToken prefix = " + accessTokenPrefix(accessToken) + ", locationId = " + locationId);

        // Build the order with line items
        List<OrderLineItem> lineItems = new ArrayList<>();
        for (GatewayItem modalityItem : argument.order().items()) {
            lineItems.add(OrderLineItem.builder()
                .quantity(String.valueOf(modalityItem.quantity()))
                .uid(modalityItem.id())
                .name(modalityItem.longName()) // Obligatory
                .basePriceMoney(Money.builder()
                    .amount(modalityItem.amount())
                    .currency(Currency.valueOf(argument.currencyCode()))
                    .build())
                .build()
            );
        }

        Order order = Order.builder()
            .locationId(locationId)
            .referenceId(argument.paymentId()) // This is where we pass the paymentId to our webhook
            .lineItems(lineItems)
            .build();

        CreatePaymentLinkRequest request = CreatePaymentLinkRequest.builder()
            .idempotencyKey(Uuid.randomUuid())
            .order(order)
            .checkoutOptions(CheckoutOptions.builder()
                .allowTipping(false)
                .redirectUrl(argument.returnUrl())
                .build())
            .build();

        Promise<String> promise = Promise.promise();
        client.checkout()
            .paymentLinks()
            .create(request)
            .thenAccept(response -> {
                PaymentLink paymentLink = response.getPaymentLink().orElse(null);
                if (paymentLink != null) {
                    String checkoutUrl = paymentLink.getUrl().orElse(null);
                    if (checkoutUrl != null) {
                        if (DEBUG_LOG) {
                            Console.log("[Square][DEBUG] createSquareCheckoutUrl - checkout URL created: " + checkoutUrl);
                        }
                        promise.complete(checkoutUrl);
                    } else {
                        promise.fail("[Square] createSquareCheckoutUrl - Payment link URL is null in response");
                    }
                } else {
                    promise.fail("[Square] createSquareCheckoutUrl - Payment link is null in response");
                }
            })
            .exceptionally(ex -> {
                promise.fail(generateErrorMessage(ex, "createSquareCheckoutUrl"));
                return null;
            });

        return promise.future();
    }

    @Override
    public Future<GatewayCompletePaymentResult> completePayment(GatewayCompletePaymentArgument argument) {
        Promise<GatewayCompletePaymentResult> promise = Promise.promise();
        boolean live = argument.isLive();
        String accessToken = argument.accessToken();
        AsyncSquareClient client = AsyncSquareClient.builder()
            .environment(live ? Environment.PRODUCTION : Environment.SANDBOX)
            .token(accessToken)
            .build();
        ReadOnlyAstObject payload = AST.parseObject(argument.payload(), "json");
        Long amount = payload.getLong("modality_amount");
        String currencyCode = payload.getString("modality_currencyCode");
        String locationId = payload.getString("square_locationId");
        String idempotencyKey = payload.getString("square_idempotencyKey");
        String sourceId = payload.getString("square_sourceId");
        String verificationToken = payload.getString("square_verificationToken");
        if (DEBUG_LOG) {
            Console.log("[Square][DEBUG] completePayment - live = " + live + ", accessToken prefix = " + accessTokenPrefix(accessToken) + ", amount = " + amount + ", currencyCode = " + currencyCode + ", locationId = " + locationId + ", idempotencyKey = " + idempotencyKey + ", sourceId = " + sourceId + ", verificationToken = " + verificationToken);
        }

        // Note + referenceId give the Square dashboard something better than
        // "Custom Amount". `note` is the human-readable description shown on
        // the payment detail page (max 500 chars). `referenceId` is the
        // merchant-side identifier (max 40 chars, no spaces or '#'), useful
        // for cross-referencing with our DB / webhooks.
        String note = truncate(argument.order().longName(), 500);
        String referenceId = truncate(argument.order().id(), 40);

        // The buyer's email on the Square payment record — what staff see when they look a payment
        // up in the dashboard, and what a refund or dispute enquiry is traced by. It does NOT make
        // Square send a receipt: Square confirms automatic receipts are not sent for Payments API
        // payments, and its own receipt emails are card-linked (tied to an address the buyer gave
        // Square directly, which we neither see nor control). Emailing our own receipt — Square
        // returns a hosted receipt_url on every payment — is a separate job.
        String buyerEmail = emailOrNull(argument.customer() == null ? null : argument.customer().email());

        createSquarePayment(client,
            new SquarePaymentInputs(sourceId, locationId, verificationToken, amount, currencyCode, note, referenceId),
            idempotencyKey, buyerEmail, promise);
        return promise.future();
    }

    /**
     * Sends one CreatePayment attempt, and retries it once WITHOUT the buyer email if that is the
     * field Square rejected — this call IS the payment, so an address we hold must never be the
     * reason a member can't pay.
     */
    private static void createSquarePayment(AsyncSquareClient client, SquarePaymentInputs inputs, String idempotencyKey, String buyerEmail, Promise<GatewayCompletePaymentResult> promise) {
        // Use Square SDK async client pattern (like AsyncCustomersClient)
        client.payments()
            .create(inputs.toCreatePaymentRequest(idempotencyKey, buyerEmail))
            .thenAccept(response -> {
                // Extract payment from response (it's an Optional in v45)
                Payment payment = response.getPayment().orElse(null);
                if (payment != null) {
                    // We generate the final result from the payment information and also capture the response (stored in the database)
                    promise.complete(generateResultFromSquarePayment(payment, response));
                } else {
                    promise.fail("[Square] completePayment - Payment is null in response");
                }
            })
            .exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof SquareApiException ae) {
                    if (buyerEmail != null && isBuyerEmailRejection(ae)) {
                        // Square names this field only while validating the REQUEST, which happens
                        // before any card is touched — no payment was created, so paying again
                        // cannot double-charge. A different key is required: Square refuses a
                        // reused key whose request body changed (IDEMPOTENCY_KEY_REUSED).
                        Console.warn("[Square] completePayment - buyer_email_address refused by Square (" + squareErrorCodes(ae) + ") — retrying the payment without it");
                        try {
                            createSquarePayment(client, inputs, retryIdempotencyKey(idempotencyKey), null, promise);
                        } catch (Throwable t) {
                            // We are inside exceptionally(), and nothing consumes the stage this
                            // lambda returns — so a synchronous throw from the retry (request
                            // building, SDK client) would settle nothing at all, leaving the payer
                            // on a spinner until the bus times out instead of seeing an error.
                            // tryFail() rather than fail() as the promise may already be settled.
                            promise.tryFail(generateErrorMessage(t, "completePayment"));
                        }
                        return null;
                    }
                    // Card declines and validation errors arrive as SquareApiException (4xx HTTP response).
                    // Convert to a structured failure result so the UI can show a specific reason
                    // rather than a generic server error.
                    PaymentFailureReason failureReason = mapSquareErrorCodesToFailureReason(ae);
                    String gatewayResponse = generateErrorMessage(ex, "completePayment");
                    promise.complete(new GatewayCompletePaymentResult(gatewayResponse, null, "FAILED", PaymentStatus.FAILED, failureReason));
                } else {
                    // Non-API errors (network, timeout, etc.) are genuine server failures
                    promise.fail(generateErrorMessage(ex, "completePayment"));
                }
                return null;
            });
    }

    /**
     * The idempotency key for the retry above. DERIVED from the client's key, never random: a
     * replayed completePayment (lost bus reply, resubmitted payload) must land on the same key it
     * did the first time, so Square recognises it and returns the original payment instead of
     * taking a second one. A random key here would make every replay of a malformed-email payment
     * a fresh charge — the double-charge shape KBS has already been bitten by. Square caps the key
     * at 45 characters, so the base is trimmed to leave room for the suffix.
     */
    private static String retryIdempotencyKey(String idempotencyKey) {
        return idempotencyKey == null ? null : truncate(idempotencyKey, 42) + "-ne";
    }

    /**
     * True when Square refused the request specifically because of the buyer email we sent —
     * INVALID_REQUEST_ERROR only, which is what makes the retry safe. That category is Square
     * validating the request before it touches a card, so no payment exists to be duplicated; a
     * rejection in any other category (a decline, an API fault) must NOT be retried under a
     * different idempotency key.
     */
    private static boolean isBuyerEmailRejection(SquareApiException ae) {
        for (Error error : ae.errors()) {
            if (!ErrorCategory.INVALID_REQUEST_ERROR.equals(error.getCategory()))
                continue;
            if (error.getField().orElse("").toLowerCase().endsWith("buyer_email_address"))
                return true;
            // The buyer's is the only email address a CreatePayment request carries, so this code
            // can't be about anything else.
            if ("INVALID_EMAIL_ADDRESS".equals(error.getCode().toString()))
                return true;
        }
        return false;
    }

    /** Square error codes as a log-safe string — no address is ever logged, only what was wrong. */
    private static String squareErrorCodes(SquareApiException ae) {
        StringBuilder codes = new StringBuilder();
        for (Error error : ae.errors()) {
            if (!codes.isEmpty()) codes.append(", ");
            codes.append(error.getCode());
        }
        return codes.toString();
    }

    /**
     * The CreatePayment fields that stay identical across attempts — only the idempotency key and
     * the buyer email differ between the first try and the retry above.
     */
    private record SquarePaymentInputs(String sourceId, String locationId, String verificationToken, Long amount, String currencyCode, String note, String referenceId) {

        CreatePaymentRequest toCreatePaymentRequest(String idempotencyKey, String buyerEmail) {
            CreatePaymentRequest._FinalStage builder = CreatePaymentRequest.builder()
                .sourceId(sourceId)
                .idempotencyKey(idempotencyKey)
                .locationId(locationId)
                .verificationToken(verificationToken)
                .amountMoney(Money.builder()
                    .amount(amount)
                    .currency(Currency.valueOf(currencyCode))
                    .build())
                .note(note)
                .referenceId(referenceId);
            if (buyerEmail != null)
                builder = builder.buyerEmailAddress(buyerEmail);
            return builder.build();
        }
    }

    private static GatewayCompletePaymentResult generateResultFromSquarePayment(Payment payment, CreatePaymentResponse response) {
        String gatewayResponse = response.toString();
        String gatewayTransactionRef = payment.getId().orElse(null);
        String gatewayStatus = payment.getStatus().orElse("UNKNOWN");
        SquarePaymentStatus squarePaymentStatus = SquarePaymentStatus.valueOf(gatewayStatus.toUpperCase());
        PaymentStatus paymentStatus = squarePaymentStatus.getGenericPaymentStatus();
        PaymentFailureReason failureReason = paymentStatus == PaymentStatus.FAILED ? PaymentFailureReason.UNKNOWN_REASON : null;
        return new GatewayCompletePaymentResult(gatewayResponse, gatewayTransactionRef, gatewayStatus, paymentStatus, failureReason);
    }

    /**
     * Inspects all errors in a SquareApiException and returns the most specific
     * PaymentFailureReason, falling back to UNKNOWN_REASON.
     */
    private static PaymentFailureReason mapSquareErrorCodesToFailureReason(SquareApiException ae) {
        for (Error error : ae.errors()) {
            String code = error.getCode().toString();
            // INVALID_CARD_DATA can mean a misconfigured app_id (gateway config error) rather than
            // a bad card number — distinguish by checking the detail message.
            if ("INVALID_CARD_DATA".equals(code)) {
                String detail = error.getDetail().map(String::toLowerCase).orElse("");
                if (detail.contains("application environment"))
                    return PaymentFailureReason.GATEWAY_ERROR;
            }
            PaymentFailureReason mapped = mapSquareErrorCode(code);
            if (mapped != PaymentFailureReason.UNKNOWN_REASON)
                return mapped;
        }
        return PaymentFailureReason.UNKNOWN_REASON;
    }

    /** Maps a single Square error code string to a PaymentFailureReason. */
    private static PaymentFailureReason mapSquareErrorCode(String code) {
        return switch (code) {
            case "INVALID_CARD", "PAN_FAILURE"
                -> PaymentFailureReason.INVALID_CARD_NUMBER;
            case "CARD_EXPIRED", "EXPIRATION_FAILURE"
                -> PaymentFailureReason.EXPIRED_CARD;
            case "INVALID_EXPIRATION", "INVALID_EXPIRATION_DATE", "INVALID_EXPIRATION_YEAR", "BAD_EXPIRATION"
                -> PaymentFailureReason.INVALID_EXPIRY_DATE;
            case "CVV_FAILURE", "VERIFY_CVV_FAILURE"
                -> PaymentFailureReason.INVALID_CVV;
            case "ADDRESS_VERIFICATION_FAILURE", "VERIFY_AVS_FAILURE"
                -> PaymentFailureReason.BILLING_ADDRESS_REQUIRED;
            case "INSUFFICIENT_FUNDS", "CARDHOLDER_INSUFFICIENT_PERMISSIONS"
                -> PaymentFailureReason.INSUFFICIENT_FUNDS;
            case "CARD_NOT_SUPPORTED", "UNSUPPORTED_CARD_BRAND"
                -> PaymentFailureReason.CARD_TYPE_NOT_ACCEPTED;
            case "CARD_DECLINED", "CARD_DECLINED_CALL_ISSUER", "CARD_DECLINED_VERIFICATION_REQUIRED",
                 "GENERIC_DECLINE", "VOICE_FAILURE"
                -> PaymentFailureReason.DECLINED_BY_BANK;
            case "CARD_PROCESSING_NOT_ENABLED"
                -> PaymentFailureReason.GATEWAY_ERROR;
            default
                -> PaymentFailureReason.UNKNOWN_REASON;
        };
    }

    private static String generateErrorMessage(Throwable ex, String method) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        StringBuilder message = new StringBuilder("[Square] ").append(method).append(" - ").append(cause.getMessage());
        if (cause instanceof SquareApiException ae) {
            message.append(" | Errors: ");
            for (Error error : ae.errors()) {
                message.append(error.getCategory()).append(": ").append(error.getCode()).append(" - ").append(error.getDetail()).append("; ");
            }
        }
        if (DEBUG_LOG) {
            Console.error(method, ex);
        }
        return message.toString();
    }

    private static String accessTokenPrefix(String accessToken) {
        return accessToken != null && accessToken.length() > 8 ? accessToken.substring(0, 8) + "..." : accessToken;
    }

    /** Returns the input truncated to `maxLength` characters, or `null` if input is null. */
    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

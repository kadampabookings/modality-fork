package one.modality.ecommerce.payment.server.gateway.impl.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.checkout.SessionCreateParams;
import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
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

import java.util.List;

import static one.modality.ecommerce.payment.server.gateway.impl.stripe.StripeAsync.executeBlocking;
import static one.modality.ecommerce.payment.server.gateway.impl.stripe.StripeAsync.retryingRequestOptions;
import static one.modality.ecommerce.payment.server.gateway.impl.stripe.StripeRestApiJob.STRIPE_PAYMENT_FORM_ENDPOINT;

/**
 * Stripe payment gateway implementation using the Stripe Java SDK 32.x.
 *
 * <p>Supports two flows, selected via {@link GatewayInitiatePaymentArgument#preferredFormType()}:
 *
 * <p><b>Embedded flow</b> ({@link PaymentFormType#EMBEDDED}): {@link #initiatePayment} creates a
 * Stripe {@code PaymentIntent} and returns an HTML page (direct content or iframe URL) that loads
 * Stripe.js with the resulting {@code client_secret}. The page renders the Payment Element widget
 * (for CARD) or the Payment Request button (for GOOGLE_PAY / APPLE_PAY). On submit, Stripe.js
 * confirms the payment client-side and posts the {@code paymentIntent.id} back to the server,
 * which calls {@link #completePayment} to verify the final status.
 *
 * <p><b>Redirected flow</b> ({@link PaymentFormType#REDIRECTED}): {@link #initiatePayment} creates
 * a Stripe Checkout {@code Session} and returns its hosted URL. After the user pays on Stripe's
 * page, the {@link StripeRestApiJob} webhook handler receives {@code payment_intent.succeeded} /
 * {@code payment_intent.payment_failed} and updates the MoneyTransfer status asynchronously.
 *
 * <p>Required account parameters: {@code api_secret_key}, {@code api_publishable_key}.
 * Optional: {@code webhook_secret} (only required to verify webhook signatures).
 *
 * <p>Stripe's Java SDK uses synchronous I/O, so every API call is wrapped in
 * {@code Vertx.executeBlocking(...)} to keep the event loop unblocked.
 *
 * @author Bruno Salmon
 */
public final class StripePaymentGateway implements PaymentGateway {

    private static final boolean DEBUG_LOG = true;

    static final String GATEWAY_NAME = "Stripe";

    // Source: https://docs.stripe.com/testing — covers card decline triggers used in mapStripeDeclineCodeToFailureReason()
    private static final SandboxCard[] SANDBOX_CARDS = {
        new SandboxCard("Visa - Success",                     "4242 4242 4242 4242", null, "123", "12345"),
        new SandboxCard("Visa (debit) - Success",             "4000 0566 5566 5556", null, "123", "12345"),
        new SandboxCard("Mastercard - Success",               "5555 5555 5555 4444", null, "123", "12345"),
        new SandboxCard("Mastercard (debit) - Success",       "5200 8282 8282 8210", null, "123", "12345"),
        new SandboxCard("American Express - Success",         "3782 822463 10005",   null, "1234", "12345"),
        new SandboxCard("Discover - Success",                 "6011 1111 1111 1117", null, "123", "12345"),
        new SandboxCard("JCB - Success",                      "3566 0020 2036 0505", null, "123", "12345"),
        new SandboxCard("UnionPay - Success",                 "6200 0000 0000 0005", null, "123", "12345"),
        new SandboxCard("Visa - Requires 3DS authentication", "4000 0025 0000 3155", null, "123", "12345"),
        new SandboxCard("Visa - 3DS challenge required",      "4000 0000 0000 3220", null, "123", "12345"),
        new SandboxCard("Generic decline",                    "4000 0000 0000 0002", null, "123", "12345"),
        new SandboxCard("Insufficient funds",                 "4000 0000 0000 9995", null, "123", "12345"),
        new SandboxCard("Lost card",                          "4000 0000 0000 9987", null, "123", "12345"),
        new SandboxCard("Stolen card",                        "4000 0000 0000 9979", null, "123", "12345"),
        new SandboxCard("Expired card",                       "4000 0000 0000 0069", null, "123", "12345"),
        new SandboxCard("Incorrect CVC",                      "4000 0000 0000 0127", null, "123", "12345"),
        new SandboxCard("Processing error",                   "4000 0000 0000 0119", null, "123", "12345"),
        new SandboxCard("Card velocity exceeded",             "4000 0000 0000 6975", null, "123", "12345")
    };

    private static final List<GatewayPaymentMethodInfo> SUPPORTED_METHODS = List.of(
        new GatewayPaymentMethodInfo(PaymentMethod.CARD,       PaymentFormType.EMBEDDED),
        new GatewayPaymentMethodInfo(PaymentMethod.GOOGLE_PAY, PaymentFormType.EMBEDDED),
        new GatewayPaymentMethodInfo(PaymentMethod.APPLE_PAY,  PaymentFormType.EMBEDDED)
    );

    // Loaded once at class init — the iframe HTML wraps the form script and CSS into a single document
    private static final String CSS_TEMPLATE = Resource.getText(Resource.toUrl("stripe-payment-form.css", StripePaymentGateway.class));
    private static final String SCRIPT_TEMPLATE = Resource.getText(Resource.toUrl("stripe-payment-form.js", StripePaymentGateway.class));
    private static final String HTML_TEMPLATE = Resource.getText(Resource.toUrl("stripe-payment-form-iframe.html", StripePaymentGateway.class))
        .replace("${stripe_paymentFormScript}", SCRIPT_TEMPLATE)
        .replace("${stripe_paymentFormCSS}", CSS_TEMPLATE);

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
            String apiSecretKey = argument.getRequiredAccountParameter("api_secret_key");
            String apiPublishableKey = argument.getRequiredAccountParameter("api_publishable_key");
            boolean live = argument.isLive();

            if (DEBUG_LOG)
                Console.log("[Stripe][DEBUG] initiatePayment - live=" + live
                    + ", amount=" + argument.order().amount() + ", currencyCode=" + argument.currencyCode()
                    + ", formType=" + argument.preferredFormType() + ", paymentMethod=" + argument.paymentMethod()
                    + ", apiSecretKey prefix=" + keyPrefix(apiSecretKey));

            if (argument.preferredFormType() == PaymentFormType.EMBEDDED)
                return initiatePaymentEmbedded(argument, apiSecretKey, apiPublishableKey, live);
            return initiatePaymentRedirect(argument, apiSecretKey, live);
        } catch (Exception e) {
            return Future.failedFuture(GATEWAY_NAME + " initiatePayment() failed: " + e.getMessage());
        }
    }

    /**
     * Embedded flow: create a PaymentIntent and return an HTML page (direct content or iframe URL)
     * that renders the Stripe.js Payment Element or Payment Request button.
     */
    private Future<GatewayInitiatePaymentResult> initiatePaymentEmbedded(GatewayInitiatePaymentArgument argument, String apiSecretKey, String apiPublishableKey, boolean live) {
        StripeClient client = new StripeClient(apiSecretKey);
        GatewayOrder order = argument.order();
        String currencyCode = argument.currencyCode();
        String paymentMethodId = (argument.paymentMethod() != null ? argument.paymentMethod() : PaymentMethod.CARD).name();
        boolean seamless = argument.favorSeamless();

        return executeBlocking(() -> createPaymentIntent(client, order, currencyCode, argument.paymentId()))
            .map(paymentIntent -> {
                String paymentFormContent = (seamless ? SCRIPT_TEMPLATE : HTML_TEMPLATE)
                    .replace("${modality_amount}", String.valueOf(order.amount()))
                    .replace("${modality_currencyCode}", currencyCode)
                    .replace("${modality_seamless}", String.valueOf(seamless))
                    .replace("${modality_paymentMethodId}", paymentMethodId)
                    .replace("${stripe_publishableKey}", apiPublishableKey)
                    .replace("${stripe_clientSecret}", paymentIntent.getClientSecret())
                    .replace("${stripe_paymentIntentId}", paymentIntent.getId())
                    .replace("${stripe_returnUrl}", nullToEmpty(argument.returnUrl()))
                    .replace("${stripe_countryCode}", merchantCountryCodeOrDefault(argument));
                if (DEBUG_LOG)
                    Console.log("[Stripe][DEBUG] initiatePaymentEmbedded - paymentIntentId=" + paymentIntent.getId() + ", seamless=" + seamless);
                SandboxCard[] sandboxCards = live ? null : SANDBOX_CARDS;
                if (seamless) {
                    return GatewayInitiatePaymentResult.createEmbeddedContentInitiatePaymentResult(live, true, paymentFormContent, false, sandboxCards);
                }
                // Non-seamless: serve the form via the one-time HTML cache endpoint so the iframe
                // can load it through https (matches the Square plugin pattern).
                String htmlCacheKey = Uuid.randomUuid();
                RestApiOneTimeHtmlResponsesCache.registerOneTimeHtmlResponse(htmlCacheKey, paymentFormContent);
                String url = STRIPE_PAYMENT_FORM_ENDPOINT.replace(":htmlCacheKey", htmlCacheKey);
                return GatewayInitiatePaymentResult.createEmbeddedUrlInitiatePaymentResult(live, false, url, false, sandboxCards);
            });
    }

    /**
     * Redirected flow: create a Stripe Checkout Session and return its hosted URL.
     * The Session links the payment to our internal {@code paymentId} via {@code client_reference_id},
     * so the webhook can resolve back to our MoneyTransfer on {@code payment_intent.succeeded}.
     */
    private Future<GatewayInitiatePaymentResult> initiatePaymentRedirect(GatewayInitiatePaymentArgument argument, String apiSecretKey, boolean live) {
        StripeClient client = new StripeClient(apiSecretKey);
        GatewayOrder order = argument.order();
        String currencyCode = argument.currencyCode();

        return executeBlocking(() -> {
            SessionCreateParams.LineItem.PriceData.ProductData productData =
                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                    .setName(truncate(order.shortName(), 250))
                    .setDescription(truncate(order.longName(), 500))
                    .build();
            SessionCreateParams.LineItem.PriceData priceData =
                SessionCreateParams.LineItem.PriceData.builder()
                    .setCurrency(currencyCode.toLowerCase())
                    .setUnitAmount(order.amount())
                    .setProductData(productData)
                    .build();
            SessionCreateParams.LineItem lineItem =
                SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(priceData)
                    .build();
            SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setClientReferenceId(argument.paymentId()) // links the Stripe payment back to our MoneyTransfer
                .addLineItem(lineItem);
            if (argument.returnUrl() != null)
                paramsBuilder.setSuccessUrl(argument.returnUrl());
            if (argument.cancelUrl() != null)
                paramsBuilder.setCancelUrl(argument.cancelUrl());
            else if (argument.returnUrl() != null)
                paramsBuilder.setCancelUrl(argument.returnUrl()); // Stripe rejects sessions without cancel_url, so reuse the return URL
            // Idempotent + retries: a 429 here would otherwise leave the user with a broken
            // redirect button on the front-end during a booking-opening spike.
            RequestOptions requestOptions = retryingRequestOptions(argument.paymentId() + "-session");
            Session session = client.v1().checkout().sessions().create(paramsBuilder.build(), requestOptions);
            if (DEBUG_LOG)
                Console.log("[Stripe][DEBUG] initiatePaymentRedirect - sessionId=" + session.getId() + ", url=" + session.getUrl());
            return GatewayInitiatePaymentResult.createRedirectInitiatePaymentResult(live, session.getUrl());
        });
    }

    /** Creates a PaymentIntent with automatic payment methods enabled (Card, wallets, Link, etc.). */
    private static PaymentIntent createPaymentIntent(StripeClient client, GatewayOrder order, String currencyCode, String paymentId) throws StripeException {
        PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
            .setAmount(order.amount())
            .setCurrency(currencyCode.toLowerCase())
            .setAutomaticPaymentMethods(
                PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                    .setEnabled(true)
                    .build())
            .setDescription(truncate(order.longName(), 1000))
            // The reference is what links the Stripe-side payment back to our MoneyTransfer
            // when the webhook delivers payment_intent.succeeded.
            .putMetadata("modality_paymentId", paymentId);
        // Idempotency key (paymentId-derived) prevents the front-office retry / refresh from
        // creating duplicate PaymentIntents. setMaxNetworkRetries makes 429 + 5xx self-healing.
        RequestOptions requestOptions = retryingRequestOptions(paymentId + "-intent");
        return client.v1().paymentIntents().create(paramsBuilder.build(), requestOptions);
    }

    @Override
    public Future<GatewayCompletePaymentResult> completePayment(GatewayCompletePaymentArgument argument) {
        try {
            String apiSecretKey = argument.getAccountParameter("api_secret_key");
            ReadOnlyAstObject payload = AST.parseObject(argument.payload(), "json");
            String paymentIntentId = payload.getString("stripe_paymentIntentId");
            if (paymentIntentId == null)
                return Future.failedFuture("[Stripe] completePayment - stripe_paymentIntentId missing in payload");
            if (DEBUG_LOG)
                Console.log("[Stripe][DEBUG] completePayment - paymentIntentId=" + paymentIntentId + ", live=" + argument.isLive());

            StripeClient client = new StripeClient(apiSecretKey);
            return executeBlocking(() -> {
                // Read-only call — no idempotency key, but still benefit from network retries.
                PaymentIntent paymentIntent = client.v1().paymentIntents().retrieve(
                    paymentIntentId,
                    PaymentIntentRetrieveParams.builder().build(),
                    retryingRequestOptions(null));
                return buildResultFromPaymentIntent(paymentIntent);
            }).recover(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof CardException ce) {
                    // Card decline arrives as a CardException — convert to a structured failure
                    // result rather than failing the future so the UI can show the specific reason.
                    PaymentFailureReason failureReason = mapStripeDeclineCodeToFailureReason(ce.getDeclineCode(), ce.getCode());
                    String message = "[Stripe] completePayment - CardException - code=" + ce.getCode() + ", declineCode=" + ce.getDeclineCode() + ", message=" + ce.getUserMessage();
                    if (DEBUG_LOG)
                        Console.error(message, ce);
                    return Future.succeededFuture(new GatewayCompletePaymentResult(message, null, "FAILED", PaymentStatus.FAILED, failureReason));
                }
                return Future.failedFuture(generateErrorMessage(ex, "completePayment"));
            });
        } catch (Exception e) {
            return Future.failedFuture(GATEWAY_NAME + " completePayment() failed: " + e.getMessage());
        }
    }

    /** Builds the gateway result from a retrieved PaymentIntent. Visible to the webhook handler. */
    static GatewayCompletePaymentResult buildResultFromPaymentIntent(PaymentIntent paymentIntent) {
        String gatewayResponse = paymentIntent.toJson();
        String gatewayTransactionRef = paymentIntent.getId();
        String stripeStatus = paymentIntent.getStatus();
        StripePaymentStatus mapped = StripePaymentStatus.parse(stripeStatus);
        PaymentStatus paymentStatus = mapped != null ? mapped.getGenericPaymentStatus() : PaymentStatus.FAILED;
        PaymentFailureReason failureReason = null;
        if (paymentStatus == PaymentStatus.FAILED) {
            // last_payment_error.decline_code / code carries the specific Stripe failure category
            StripeError lpe = paymentIntent.getLastPaymentError();
            failureReason = mapStripeDeclineCodeToFailureReason(
                lpe != null ? lpe.getDeclineCode() : null,
                lpe != null ? lpe.getCode() : null);
        }
        return new GatewayCompletePaymentResult(gatewayResponse, gatewayTransactionRef, stripeStatus, paymentStatus, failureReason);
    }

    /**
     * Maps Stripe's {@code last_payment_error.decline_code} / {@code .code} to a {@link PaymentFailureReason}.
     * Decline codes are checked first since they're more specific (e.g. {@code insufficient_funds} is a
     * decline_code paired with the generic {@code card_declined} code).
     *
     * <p>Reference: <a href="https://stripe.com/docs/declines/codes">Stripe decline codes</a>.
     */
    static PaymentFailureReason mapStripeDeclineCodeToFailureReason(String declineCode, String errorCode) {
        if (declineCode != null) {
            PaymentFailureReason mapped = mapDeclineCode(declineCode);
            if (mapped != PaymentFailureReason.UNKNOWN_REASON)
                return mapped;
        }
        if (errorCode != null)
            return mapErrorCode(errorCode);
        return PaymentFailureReason.UNKNOWN_REASON;
    }

    private static PaymentFailureReason mapDeclineCode(String declineCode) {
        return switch (declineCode) {
            case "insufficient_funds", "withdrawal_count_limit_exceeded",
                 "card_velocity_exceeded"
                -> PaymentFailureReason.INSUFFICIENT_FUNDS;
            case "expired_card"
                -> PaymentFailureReason.EXPIRED_CARD;
            case "incorrect_cvc", "invalid_cvc"
                -> PaymentFailureReason.INVALID_CVV;
            case "incorrect_number", "invalid_number"
                -> PaymentFailureReason.INVALID_CARD_NUMBER;
            case "invalid_expiry_month", "invalid_expiry_year"
                -> PaymentFailureReason.INVALID_EXPIRY_DATE;
            case "incorrect_zip", "incorrect_address"
                -> PaymentFailureReason.BILLING_ADDRESS_REQUIRED;
            case "card_not_supported", "currency_not_supported"
                -> PaymentFailureReason.CARD_TYPE_NOT_ACCEPTED;
            case "generic_decline", "do_not_honor", "do_not_try_again",
                 "lost_card", "stolen_card", "pickup_card", "restricted_card",
                 "card_declined", "transaction_not_allowed", "fraudulent",
                 "call_issuer", "no_action_taken", "service_not_allowed",
                 "stop_payment_order", "revocation_of_authorization",
                 "revocation_of_all_authorizations"
                -> PaymentFailureReason.DECLINED_BY_BANK;
            case "processing_error", "issuer_not_available", "try_again_later"
                -> PaymentFailureReason.GATEWAY_ERROR;
            default
                -> PaymentFailureReason.UNKNOWN_REASON;
        };
    }

    private static PaymentFailureReason mapErrorCode(String errorCode) {
        return switch (errorCode) {
            case "incorrect_cvc", "invalid_cvc"            -> PaymentFailureReason.INVALID_CVV;
            case "incorrect_number", "invalid_number"      -> PaymentFailureReason.INVALID_CARD_NUMBER;
            case "expired_card"                            -> PaymentFailureReason.EXPIRED_CARD;
            case "invalid_expiry_month", "invalid_expiry_year"
                                                            -> PaymentFailureReason.INVALID_EXPIRY_DATE;
            case "card_declined"                           -> PaymentFailureReason.DECLINED_BY_BANK;
            case "processing_error"                        -> PaymentFailureReason.GATEWAY_ERROR;
            default                                        -> PaymentFailureReason.UNKNOWN_REASON;
        };
    }

    private static String generateErrorMessage(Throwable ex, String method) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        String base = "[Stripe] " + method + " - " + cause.getMessage();
        if (cause instanceof StripeException se) {
            base += " | code=" + se.getCode() + ", requestId=" + se.getRequestId() + ", statusCode=" + se.getStatusCode();
        }
        if (DEBUG_LOG)
            Console.error(method, ex);
        return base;
    }

    private static String keyPrefix(String apiKey) {
        return apiKey != null && apiKey.length() > 8 ? apiKey.substring(0, 8) + "..." : apiKey;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String merchantCountryCodeOrDefault(GatewayInitiatePaymentArgument argument) {
        String cc = argument.merchantCountryCode();
        return (cc != null && !cc.isEmpty()) ? cc.toUpperCase() : "US";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

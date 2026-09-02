package one.modality.ecommerce.payment.server.gateway.impl.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.ChargeUpdateParams;
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
import java.util.regex.Pattern;

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

    static final String GATEWAY_NAME = "Stripe";
    private static final boolean DEBUG_LOG = true;

    // Deliberately stricter than RFC 5322 (no quoted local parts, no IP-literal domains): this
    // only ever decides whether an address is safe to hand to Stripe as receipt_email, and the
    // penalty for a false accept — Stripe refusing the call — is paid on the payment path.
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+");

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

        // Billing details we already know from the booking — injected into the JS so the Payment
        // Element can hide the corresponding input fields ('fields.billingDetails = never') and
        // we still satisfy Stripe's requirement to supply them at confirmPayment() time.
        GatewayCustomer customer = argument.customer();
        return executeBlocking(() -> createPaymentIntent(client, order, currencyCode, argument.paymentId(), customer))
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
                    .replace("${stripe_countryCode}", merchantCountryCodeOrDefault(argument))
                    .replace("${modality_billingFirstName}",   jsStringEscape(customer == null ? null : customer.firstName()))
                    .replace("${modality_billingLastName}",    jsStringEscape(customer == null ? null : customer.lastName()))
                    .replace("${modality_billingEmail}",       jsStringEscape(customer == null ? null : customer.email()))
                    .replace("${modality_billingPhone}",       jsStringEscape(customer == null ? null : customer.phone()))
                    .replace("${modality_billingAddress}",     jsStringEscape(customer == null ? null : customer.address()))
                    .replace("${modality_billingCity}",        jsStringEscape(customer == null ? null : customer.city()))
                    .replace("${modality_billingState}",       jsStringEscape(customer == null ? null : customer.state()))
                    .replace("${modality_billingPostCode}",    jsStringEscape(customer == null ? null : customer.zipCode()))
                    .replace("${modality_billingCountryCode}", jsStringEscape(customer == null ? null : customer.countryCode()));
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
            // No customer_email pre-fill on purpose: Checkout collects the address itself and
            // sends its own receipt, and supplying one makes the field READ-ONLY on Stripe's page —
            // a stale booking address would then take the receipt with no way for the payer to
            // correct it. The embedded flow below (the only one this gateway exposes) is where the
            // receipt address has to be set explicitly.
            // Idempotent + retries: a 429 here would otherwise leave the user with a broken
            // redirect button on the front-end during a booking-opening spike.
            RequestOptions requestOptions = retryingRequestOptions(argument.paymentId() + "-session");
            Session session = client.v1().checkout().sessions().create(paramsBuilder.build(), requestOptions);
            if (DEBUG_LOG)
                Console.log("[Stripe][DEBUG] initiatePaymentRedirect - sessionId=" + session.getId() + ", url=" + session.getUrl());
            return GatewayInitiatePaymentResult.createRedirectInitiatePaymentResult(live, session.getUrl());
        });
    }

    /**
     * Creates a card-only PaymentIntent. We restrict payment_method_types to {@code ['card']}
     * (rather than using {@code automatic_payment_methods}) because our gateway already exposes
     * CARD / GOOGLE_PAY / APPLE_PAY as discrete user-selectable methods upstream — by the time
     * we reach here the user has explicitly picked one. Restricting types keeps the embedded
     * Payment Element clean: no extra method tabs (MB WAY, Satispay, Bancontact, etc.) and no
     * Link "save my info for faster checkout" CTA, which would otherwise re-introduce email /
     * phone / full-name fields we just told Stripe to hide via fields.billingDetails.
     *
     * <p>Wallet flows (GOOGLE_PAY / APPLE_PAY) still work fine with {@code ['card']} because the
     * Payment Request Button tokenizes a card under the hood.
     */
    private static PaymentIntent createPaymentIntent(StripeClient client, GatewayOrder order, String currencyCode, String paymentId, GatewayCustomer customer) throws StripeException {
        PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
            .setAmount(order.amount())
            .setCurrency(currencyCode.toLowerCase())
            .addPaymentMethodType("card")
            .setDescription(truncate(order.longName(), 1000))
            // The reference is what links the Stripe-side payment back to our MoneyTransfer
            // when the webhook delivers payment_intent.succeeded.
            .putMetadata("modality_paymentId", paymentId);
        // Stripe emails a receipt only when the charge carries a receipt_email (or an attached
        // Customer object with an email on file). The billing_details.email we send at
        // confirmPayment() time is recorded on the charge and shown in the dashboard — which
        // makes it look like Stripe has the address — but it never triggers a receipt. That is
        // why payers got none. receipt_email also forces delivery regardless of the account's
        // "Successful payments" email setting, so receipts no longer depend on a dashboard toggle.
        String receiptEmail = emailOrNull(customer == null ? null : customer.email());
        // Idempotency key (paymentId-derived) prevents the front-office retry / refresh from
        // creating duplicate PaymentIntents. setMaxNetworkRetries makes 429 + 5xx self-healing.
        if (receiptEmail != null) {
            try {
                return client.v1().paymentIntents().create(
                    paramsBuilder.setReceiptEmail(receiptEmail).build(),
                    retryingRequestOptions(paymentId + "-intent"));
            } catch (InvalidRequestException e) {
                // emailOrNull() is a shape check, not Stripe's validator: an address can pass here
                // and still be refused there. Retrying without it keeps a rejected receipt address
                // from turning "no receipt" into "this payer cannot pay at all". A fresh
                // idempotency key is required — Stripe refuses a reused key with changed params.
                if (!"receipt_email".equals(e.getParam()))
                    throw e;
                Console.warn("[Stripe] receipt_email refused by Stripe for payment " + paymentId
                    + " (code=" + e.getCode() + ") — creating the PaymentIntent without it, so this payer gets no Stripe receipt");
                return client.v1().paymentIntents().create(
                    paramsBuilder.setReceiptEmail(null).build(),
                    retryingRequestOptions(paymentId + "-intent-no-receipt"));
            }
        }
        return client.v1().paymentIntents().create(paramsBuilder.build(), retryingRequestOptions(paymentId + "-intent"));
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
            GatewayCustomer customer = argument.customer();
            return executeBlocking(() ->
                // Read-only call — no idempotency key, but still benefit from network retries.
                client.v1().paymentIntents().retrieve(
                    paymentIntentId,
                    PaymentIntentRetrieveParams.builder().build(),
                    retryingRequestOptions(null))
            ).map(paymentIntent -> {
                // Fire-and-forget: never delays or fails the payer's confirmation.
                sendMissingReceiptEmail(client, paymentIntent, customer == null ? null : customer.email());
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

    /**
     * Sets {@code receipt_email} on an already-succeeded charge when the PaymentIntent doesn't
     * carry one — Stripe sends the receipt at that moment. This covers the two cases
     * {@link #createPaymentIntent} can't:
     *
     * <ul>
     *   <li>a PaymentIntent created before this gateway started setting {@code receipt_email};</li>
     *   <li>a booking with no email on file, where the only address we ever see is the one the
     *       payer typed into the payment form (it reaches Stripe as the charge's
     *       {@code billing_details.email}, and us only by reading the charge back).</li>
     * </ul>
     *
     * <p>Deliberately fire-and-forget: a missing receipt is not worth failing — or even
     * delaying — a payment that has already succeeded, so every failure is logged and dropped.
     */
    static void sendMissingReceiptEmail(StripeClient client, PaymentIntent paymentIntent, String bookingEmail) {
        try {
            sendMissingReceiptEmailOrThrow(client, paymentIntent, bookingEmail);
        } catch (Throwable t) { // a receipt problem must never surface as a payment failure
            Console.error("[Stripe] Could not request a receipt for payment intent " + (paymentIntent == null ? null : paymentIntent.getId()), t);
        }
    }

    private static void sendMissingReceiptEmailOrThrow(StripeClient client, PaymentIntent paymentIntent, String bookingEmail) {
        if (paymentIntent == null || !"succeeded".equals(paymentIntent.getStatus()))
            return; // no captured charge yet — nothing to send a receipt for
        if (emailOrNull(paymentIntent.getReceiptEmail()) != null)
            return; // set at creation — Stripe has already sent the receipt
        String chargeId = paymentIntent.getLatestCharge();
        if (chargeId == null)
            return;
        String knownEmail = emailOrNull(bookingEmail);
        executeBlocking(() -> {
            // The charge is the authority here, not the intent: our write below lands on the
            // charge and never propagates back to the intent, so re-reading the intent could
            // never tell us we had already asked for this receipt. Both callers (completePayment
            // and the webhook, plus every webhook redelivery) reach this line for the same charge.
            Charge charge = client.v1().charges().retrieve(chargeId, retryingRequestOptions(null));
            if (emailOrNull(charge.getReceiptEmail()) != null)
                return Boolean.FALSE; // receipt already requested — don't send the payer a second one
            String email = knownEmail;
            if (email == null) { // last resort: whatever the payer entered in the payment form
                Charge.BillingDetails billingDetails = charge.getBillingDetails();
                email = emailOrNull(billingDetails == null ? null : billingDetails.getEmail());
                if (email == null)
                    return Boolean.FALSE;
            }
            // Updating receipt_email on a succeeded charge is what makes Stripe send the receipt.
            // The idempotency key closes the remaining window where the two callers race on the
            // same charge: within Stripe's 24h key retention the second update is a replay, not a
            // second email.
            client.v1().charges().update(chargeId,
                ChargeUpdateParams.builder().setReceiptEmail(email).build(),
                retryingRequestOptions(chargeId + "-receipt"));
            return Boolean.TRUE;
        })
            // Addresses are never logged — the charge id is enough to find the payment in Stripe.
            .onFailure(e -> Console.error("[Stripe] Could not set receipt_email on charge " + chargeId + " — no Stripe receipt for payment intent " + paymentIntent.getId(), e))
            .onSuccess(sent -> Console.log(sent
                ? "[Stripe] Receipt requested on charge " + chargeId
                : "[Stripe] Nothing to do for a receipt on charge " + chargeId + " (already requested, or no usable email address)"));
    }

    /**
     * Returns the value only if it plausibly is an email address, otherwise null. Stripe rejects
     * the whole API call on a malformed {@code receipt_email}, so junk in {@code person.email} (a
     * free-text field) would break the payment itself. A missing receipt is by far the lesser
     * failure, so anything that doesn't look like an address is dropped here rather than sent.
     */
    private static String emailOrNull(String email) {
        if (email == null)
            return null;
        String trimmed = email.trim();
        return trimmed.length() <= 254 && EMAIL_SHAPE.matcher(trimmed).matches() ? trimmed : null;
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

    /**
     * Escapes a value for safe embedding inside a single-quoted JS string literal in the
     * Stripe payment form script. Null becomes empty string so the JS variable evaluates as
     * falsy (which the JS uses to decide whether to keep the input field visible). The
     * {@code <} escape prevents a customer name containing {@code </script>} from breaking
     * out of the surrounding {@code <script>} block in the iframe HTML.
     */
    private static String jsStringEscape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<'  -> sb.append("\\u003c");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

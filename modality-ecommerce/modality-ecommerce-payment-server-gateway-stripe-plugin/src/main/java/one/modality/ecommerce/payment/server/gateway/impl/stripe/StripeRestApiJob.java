package one.modality.ecommerce.payment.server.gateway.impl.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.platform.util.http.HttpResponseStatus;
import dev.webfx.platform.util.vertx.VertxInstance;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import one.modality.base.shared.entities.GatewayParameter;
import one.modality.base.shared.entities.MoneyTransfer;
import one.modality.ecommerce.payment.PaymentFormType;
import one.modality.ecommerce.payment.PaymentService;
import one.modality.ecommerce.payment.PaymentStatus;
import one.modality.ecommerce.payment.UpdatePaymentStatusArgument;
import one.modality.ecommerce.payment.server.gateway.GatewayCompletePaymentResult;
import one.modality.ecommerce.payment.server.gateway.impl.util.RestApiOneTimeHtmlResponsesCache;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.SystemUserId;

import static one.modality.ecommerce.payment.server.gateway.impl.stripe.StripeAsync.executeBlocking;
import static one.modality.ecommerce.payment.server.gateway.impl.stripe.StripeAsync.retryingRequestOptions;

/**
 * Vert.x router bindings for the Stripe gateway:
 *
 * <ul>
 *   <li>{@code /payment/stripe/paymentForm/:htmlCacheKey} — serves the cached embedded form HTML
 *       (parallel to the Square plugin's one-time cache pattern).</li>
 *   <li>{@code /payment/stripe/live/webhook} and {@code /payment/stripe/sandbox/webhook} —
 *       receive Stripe webhook events ({@code payment_intent.succeeded},
 *       {@code payment_intent.payment_failed}, {@code payment_intent.canceled}, and
 *       {@code checkout.session.completed}) and update the MoneyTransfer status.</li>
 * </ul>
 *
 * <p>Webhook handling intentionally re-fetches the PaymentIntent from Stripe before updating the
 * DB, so the source of truth is Stripe's API rather than the (untrusted) webhook payload — matches
 * the pattern used in the Square plugin. Signature verification is best-effort: if the account has
 * a {@code webhook_secret} GatewayParameter, the signature is required; otherwise a warning is
 * logged and processing continues.
 *
 * @author Bruno Salmon
 */
public final class StripeRestApiJob implements ApplicationJob {

    static final String STRIPE_PAYMENT_FORM_ENDPOINT = "/payment/stripe/paymentForm/:htmlCacheKey";

    private static final String STRIPE_LIVE_WEBHOOK_ENDPOINT    = "/payment/stripe/live/webhook";
    private static final String STRIPE_SANDBOX_WEBHOOK_ENDPOINT = "/payment/stripe/sandbox/webhook";

    private static final String STRIPE_SIGNATURE_HEADER = "Stripe-Signature";

    private static final SystemUserId STRIPE_HISTORY_USER_ID = new SystemUserId(StripePaymentGateway.GATEWAY_NAME);

    @Override
    public void onInit() {
        Router router = VertxInstance.getHttpRouter();

        /*====================================== EMBED PAYMENT FORM REST API =========================================*/

        // Mirror of the Square iframe pattern: when the embedded form can't be loaded as direct
        // HTML content (e.g. when iframe.src must point to an https URL), it's stashed in the
        // one-time cache by StripePaymentGateway.initiatePaymentEmbedded() and served here.
        router.route(STRIPE_PAYMENT_FORM_ENDPOINT)
            .handler(ctx -> {
                String cacheKey = ctx.pathParam("htmlCacheKey");
                String html = RestApiOneTimeHtmlResponsesCache.getOneTimeHtmlResponse(cacheKey);
                ctx.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, HttpHeaders.TEXT_HTML)
                    .end(html);
            });

        /*=========================================== WEBHOOKS REST API ==============================================*/

        router.route(STRIPE_LIVE_WEBHOOK_ENDPOINT)
            .handler(BodyHandler.create())
            .handler(ctx -> handleWebhook(ctx, true));

        router.route(STRIPE_SANDBOX_WEBHOOK_ENDPOINT)
            .handler(BodyHandler.create())
            .handler(ctx -> handleWebhook(ctx, false));
    }

    private void handleWebhook(RoutingContext ctx, boolean live) {
        String textPayload = ctx.body().asString();
        String sigHeader = ctx.request().getHeader(STRIPE_SIGNATURE_HEADER);
        handleWebhook(textPayload, sigHeader, ctx, live);
    }

    /** Visible-for-testing entry that doesn't require a RoutingContext. */
    void handleWebhook(String textPayload, String sigHeader, boolean live) {
        handleWebhook(textPayload, sigHeader, null, live);
    }

    private void handleWebhook(String textPayload, String sigHeader, RoutingContext ctx, boolean live) {
        String logPrefix = "[Stripe] " + (live ? "Live" : "Sandbox") + " webhook - ";
        Console.log(logPrefix + "Called with payload = " + textPayload);

        // Parse the payload (untrusted) just enough to identify the linked payment.
        // The authoritative status will be re-fetched from Stripe's API below.
        ReadOnlyAstObject payload = AST.parseObjectSilently(textPayload, "json");
        if (payload == null) {
            Console.warn(logPrefix + "Could not parse webhook payload as JSON");
            respond(ctx, HttpResponseStatus.BAD_REQUEST_400);
            return;
        }
        String eventType = payload.getString("type");
        if (!isHandledEventType(eventType)) {
            Console.log(logPrefix + "Ignoring event type: " + eventType);
            respond(ctx, HttpResponseStatus.OK_200);
            return;
        }

        WebhookTarget target = extractTarget(payload);
        if (target == null) {
            Console.warn(logPrefix + "Could not extract paymentIntentId / paymentId from event " + eventType);
            respond(ctx, HttpResponseStatus.BAD_REQUEST_400);
            return;
        }

        processWebhook(target, textPayload, sigHeader, live, logPrefix)
            .onSuccess(v -> respond(ctx, HttpResponseStatus.OK_200))
            .onFailure(ex -> {
                if (ex.getMessage() != null && ex.getMessage().contains("Could not find MoneyTransfer")) {
                    Console.error(logPrefix + "Assuming this payment is from another system 🤷️", ex);
                    respond(ctx, HttpResponseStatus.OK_200);
                } else {
                    Console.error(logPrefix + "An error occurred while processing the webhook", ex);
                    respond(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR_500);
                }
            });
    }

    private static boolean isHandledEventType(String eventType) {
        return "payment_intent.succeeded".equals(eventType)
            || "payment_intent.payment_failed".equals(eventType)
            || "payment_intent.canceled".equals(eventType)
            || "checkout.session.completed".equals(eventType)
            || "checkout.session.async_payment_succeeded".equals(eventType)
            || "checkout.session.async_payment_failed".equals(eventType);
    }

    /**
     * Locates the linked MoneyTransfer (either by paymentIntentId stored as transactionRef, or by
     * the modality paymentId carried in metadata / client_reference_id), looks up the account
     * parameters needed for signature verification + Stripe API access, then re-fetches the
     * PaymentIntent from Stripe and updates the local payment status accordingly.
     */
    private static Future<Void> processWebhook(WebhookTarget target, String textPayload, String sigHeader, boolean live, String logPrefix) {
        // Step 1: locate the MoneyTransfer in our DB.
        return locateMoneyTransfer(target, logPrefix)
            // Step 2: load the account's api_secret_key (and webhook_secret if present).
            .compose(payment -> loadAccountParameters(payment, live, logPrefix)
                .compose(accountParams -> {
                    // Step 3: verify the webhook signature when a secret is configured.
                    if (accountParams.webhookSecret() != null) {
                        try {
                            Webhook.constructEvent(textPayload, sigHeader, accountParams.webhookSecret());
                        } catch (SignatureVerificationException ex) {
                            return Future.failedFuture(logPrefix + "Signature verification failed: " + ex.getMessage());
                        }
                    } else {
                        Console.warn(logPrefix + "No webhook_secret configured — skipping signature verification (best-effort mode)");
                    }
                    // Step 4: re-fetch the authoritative PaymentIntent from Stripe.
                    return executeBlocking(() -> retrievePaymentIntent(target, accountParams.apiSecretKey()))
                        // Step 5: write the new status into the DB.
                        .compose(paymentIntent -> updatePaymentStatus(payment, paymentIntent, textPayload, target.formType(), logPrefix));
                }));
    }

    private static Future<MoneyTransfer> locateMoneyTransfer(WebhookTarget target, String logPrefix) {
        EntityStore store = EntityStore.create();
        // Prefer the transactionRef lookup (set by completePayment on the embedded flow).
        if (target.paymentIntentId() != null) {
            return store.<MoneyTransfer>executeQuery("select pending,successful,status,gatewayResponse from MoneyTransfer where transactionRef = $1", target.paymentIntentId())
                .compose(matches -> {
                    MoneyTransfer payment = Collections.first(matches);
                    if (payment != null)
                        return Future.succeededFuture(payment);
                    // Fall back to the modality paymentId carried in metadata / client_reference_id
                    // — needed for the redirect flow where completePayment never runs.
                    if (target.modalityPaymentId() == null)
                        return Future.failedFuture(logPrefix + "Could not find MoneyTransfer for paymentIntentId=" + target.paymentIntentId());
                    return store.<MoneyTransfer>executeQuery("select pending,successful,status,gatewayResponse from MoneyTransfer where id = $1", Numbers.toShortestNumber(target.modalityPaymentId()))
                        .map(byId -> {
                            MoneyTransfer found = Collections.first(byId);
                            if (found == null)
                                throw new RuntimeException(logPrefix + "Could not find MoneyTransfer for modalityPaymentId=" + target.modalityPaymentId());
                            return found;
                        });
                });
        }
        // No paymentIntentId — only the modality paymentId is available (Checkout-session flow before payment_intent.* arrives).
        if (target.modalityPaymentId() != null) {
            return store.<MoneyTransfer>executeQuery("select pending,successful,status,gatewayResponse from MoneyTransfer where id = $1", Numbers.toShortestNumber(target.modalityPaymentId()))
                .map(matches -> {
                    MoneyTransfer payment = Collections.first(matches);
                    if (payment == null)
                        throw new RuntimeException(logPrefix + "Could not find MoneyTransfer for modalityPaymentId=" + target.modalityPaymentId());
                    return payment;
                });
        }
        return Future.failedFuture(logPrefix + "Webhook target has neither paymentIntentId nor modalityPaymentId");
    }

    private static Future<AccountParameters> loadAccountParameters(MoneyTransfer payment, boolean live, String logPrefix) {
        // The account is reached via Payment -> MoneyAccount -> GatewayAccount; we just need the
        // two specific GatewayParameter values for the Stripe account that owns this payment.
        return payment.getStore().<GatewayParameter>executeQuery(
            "select name,value from GatewayParameter p where account.gatewayCompany.name='Stripe' and name in ('api_secret_key','webhook_secret') and ($1 ? live : test) and account = (select moneyAccount.account from MoneyTransfer where id = $2) order by name",
            live, payment.getPrimaryKey())
            .map(params -> {
                String apiSecretKey = null;
                String webhookSecret = null;
                for (GatewayParameter param : params) {
                    if ("api_secret_key".equals(param.getName()))      apiSecretKey  = param.getValue();
                    else if ("webhook_secret".equals(param.getName())) webhookSecret = param.getValue();
                }
                if (apiSecretKey == null)
                    throw new RuntimeException(logPrefix + "No api_secret_key found for the payment's Stripe account (live=" + live + ")");
                return new AccountParameters(apiSecretKey, webhookSecret);
            });
    }

    private static PaymentIntent retrievePaymentIntent(WebhookTarget target, String apiSecretKey) throws Exception {
        StripeClient client = new StripeClient(apiSecretKey);
        // For a Checkout-session event with no payment_intent yet, target.paymentIntentId() may be
        // null — but callers only reach this method when we have a paymentIntentId to query.
        // Read-only retrieve — no idempotency key needed, but we want network retries on 429/5xx.
        return client.v1().paymentIntents().retrieve(target.paymentIntentId(), retryingRequestOptions(null));
    }

    private static Future<Void> updatePaymentStatus(MoneyTransfer payment, PaymentIntent paymentIntent, String textPayload, PaymentFormType formType, String logPrefix) {
        GatewayCompletePaymentResult result = StripePaymentGateway.buildResultFromPaymentIntent(paymentIntent);
        PaymentStatus paymentStatus = result.paymentStatus();
        String stripeStatus = paymentIntent.getStatus();
        boolean pending = paymentStatus.isPending();
        boolean successful = paymentStatus.isSuccessful();
        Object paymentPk = payment.getPrimaryKey();
        // Skip no-op updates: Stripe sends multiple events for the same transition and we don't
        // want to spam the payment history with identical entries.
        if (payment.isPending() == pending && payment.isSuccessful() == successful && stripeStatus != null && stripeStatus.equalsIgnoreCase(payment.getStatus())) {
            Console.log(logPrefix + "Skipping event — status already up-to-date on payment " + paymentPk);
            return Future.succeededFuture();
        }
        return STRIPE_HISTORY_USER_ID.callAndReturn(() ->
            PaymentService.updatePaymentStatus(UpdatePaymentStatusArgument.createCapturedStatusArgument(
                paymentPk, textPayload, paymentIntent.getId(), stripeStatus, pending, successful, formType, null))
                .onFailure(e -> Console.error(logPrefix + "⛔️️  Failed to update status " + stripeStatus + " for payment " + paymentPk, e))
                .onSuccess(v -> Console.log(logPrefix + "✅  Successfully updated status " + stripeStatus + " for payment " + paymentPk))
        );
    }

    /**
     * Extracts the payment-identifying fields (paymentIntentId, modality paymentId, form type)
     * from a Stripe webhook payload, accounting for both {@code payment_intent.*} and
     * {@code checkout.session.*} events.
     */
    static WebhookTarget extractTarget(ReadOnlyAstObject payload) {
        String eventType = payload.getString("type");
        ReadOnlyAstObject object = AST.lookupObject(payload, "data.object");
        if (object == null)
            return null;
        if (eventType != null && eventType.startsWith("payment_intent.")) {
            String paymentIntentId = object.getString("id");
            String modalityPaymentId = AST.lookupString(object, "metadata.modality_paymentId");
            return new WebhookTarget(paymentIntentId, modalityPaymentId, PaymentFormType.EMBEDDED);
        }
        if (eventType != null && eventType.startsWith("checkout.session.")) {
            String paymentIntentId = object.getString("payment_intent");
            String modalityPaymentId = object.getString("client_reference_id");
            return new WebhookTarget(paymentIntentId, modalityPaymentId, PaymentFormType.REDIRECTED);
        }
        return null;
    }

    private static void respond(RoutingContext ctx, int statusCode) {
        if (ctx == null) return; // direct unit-test entry
        ctx.response().setStatusCode(statusCode).end();
    }

    /** A webhook event resolved to enough info to find the matching MoneyTransfer in our DB. */
    record WebhookTarget(String paymentIntentId, String modalityPaymentId, PaymentFormType formType) {}

    private record AccountParameters(String apiSecretKey, String webhookSecret) {}
}

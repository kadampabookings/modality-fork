package one.modality.ecommerce.payment.server.gateway.impl.paypal;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.http.HttpResponseStatus;
import dev.webfx.platform.util.vertx.VertxInstance;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.session.state.SystemUserId;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import one.modality.base.shared.entities.MoneyTransfer;
import one.modality.ecommerce.payment.PaymentService;
import one.modality.ecommerce.payment.PaymentStatus;
import one.modality.ecommerce.payment.UpdatePaymentStatusArgument;
import one.modality.ecommerce.payment.server.gateway.impl.util.RestApiOneTimeHtmlResponsesCache;

/**
 * Registers Vert.x routes for PayPal webhook notifications and handles incoming events.
 *
 * <p>Handled event types:
 * <ul>
 *   <li>{@code PAYMENT.CAPTURE.COMPLETED} / {@code PAYMENT.CAPTURE.DENIED} — looks up the
 *       {@link MoneyTransfer} by {@code transactionRef} (capture ID) and updates its status.</li>
 *   <li>{@code CHECKOUT.ORDER.COMPLETED} — looks up the payment by our internal payment ID
 *       (stored as {@code reference_id} on the PayPal purchase unit) and updates its status,
 *       also recording the capture ID as {@code transactionRef} if not yet set.</li>
 * </ul>
 *
 * <p>Webhook URLs to register in the PayPal Developer Dashboard:
 * <ul>
 *   <li>Live:    {@code https://<host>/payment/paypal/live/webhook}</li>
 *   <li>Sandbox: {@code https://<host>/payment/paypal/sandbox/webhook}</li>
 * </ul>
 *
 * @author Bruno Salmon
 */
public final class PayPalRestApiJob implements ApplicationJob {

    static final String PAYPAL_LOAD_FORM_ENDPOINT        = "/payment/paypal/loadPaymentForm/:htmlCacheKey";
    static final String PAYPAL_LIVE_WEBHOOK_ENDPOINT    = "/payment/paypal/live/webhook";
    static final String PAYPAL_SANDBOX_WEBHOOK_ENDPOINT = "/payment/paypal/sandbox/webhook";

    private static final SystemUserId PAYPAL_HISTORY_USER_ID = new SystemUserId(PayPalPaymentGateway.GATEWAY_NAME);

    @Override
    public void onInit() {
        Router router = VertxInstance.getHttpRouter();

        /*====================================== EMBED PAYMENT FORM REST API =========================================*/

        router.route(PAYPAL_LOAD_FORM_ENDPOINT)
            .handler(ctx -> {
                String cacheKey = ctx.pathParam("htmlCacheKey");
                String html = RestApiOneTimeHtmlResponsesCache.getOneTimeHtmlResponse(cacheKey);
                if (html != null)
                    ctx.response()
                        .putHeader(HttpHeaders.CONTENT_TYPE, HttpHeaders.TEXT_HTML)
                        .end(html);
                else
                    ctx.response()
                        .setStatusCode(HttpResponseStatus.BAD_REQUEST_400)
                        .end("No value for cache key: " + cacheKey);
            });

        /*======================================== WEBHOOK REST API ================================================*/

        router.route(PAYPAL_LIVE_WEBHOOK_ENDPOINT)
            .handler(BodyHandler.create())
            .handler(ctx -> handleWebhook(ctx, true));

        router.route(PAYPAL_SANDBOX_WEBHOOK_ENDPOINT)
            .handler(BodyHandler.create())
            .handler(ctx -> handleWebhook(ctx, false));
    }

    private static void handleWebhook(RoutingContext ctx, boolean live) {
        JsonObject vertxPayload = ctx.body().asJsonObject();
        String textPayload = vertxPayload != null ? vertxPayload.encode() : ctx.body().asString();
        ReadOnlyAstObject payload = AST.createObject(vertxPayload);
        String logPrefix = "[PayPal] " + (live ? "Live" : "Sandbox") + " webhook - ";
        Console.log(logPrefix + "Called with payload = " + textPayload);

        String eventType = AST.lookupString(payload, "event_type");
        Console.log(logPrefix + "event_type = " + eventType);

        Future<Void> result;
        switch (eventType != null ? eventType : "") {
            case "PAYMENT.CAPTURE.COMPLETED":
            case "PAYMENT.CAPTURE.DENIED":
                result = handleCaptureEvent(payload, textPayload, logPrefix);
                break;
            case "CHECKOUT.ORDER.COMPLETED":
                result = handleOrderCompletedEvent(payload, textPayload, logPrefix);
                break;
            default:
                Console.log(logPrefix + "Ignoring unmanaged event type: " + eventType);
                ctx.response().setStatusCode(HttpResponseStatus.OK_200).end();
                return;
        }

        result
            .onSuccess(v -> ctx.response().setStatusCode(HttpResponseStatus.OK_200).end())
            .onFailure(ex -> {
                Console.error(logPrefix + "An error occurred while processing the PayPal webhook", ex);
                ctx.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR_500).end();
            });
    }

    /**
     * Handles {@code PAYMENT.CAPTURE.COMPLETED} and {@code PAYMENT.CAPTURE.DENIED}.
     *
     * <p>Looks up the payment by {@code transactionRef} (= capture ID stored by
     * {@link PayPalPaymentGateway#completePayment} when the front-office captured the order).
     * If the payment is not found by capture ID (e.g., the webhook arrived before the user
     * returned to the site), a warning is logged and the event is silently acknowledged — the
     * subsequent {@code CHECKOUT.ORDER.COMPLETED} event will handle it via reference_id.
     */
    private static Future<Void> handleCaptureEvent(ReadOnlyAstObject payload, String textPayload, String logPrefix) {
        String captureId     = AST.lookupString(payload, "resource.id");
        String captureStatus = AST.lookupString(payload, "resource.status");

        if (captureId == null) {
            Console.warn(logPrefix + "No resource.id found in capture event payload");
            return Future.succeededFuture();
        }

        return EntityStore.create()
            .<MoneyTransfer>executeQuery(
                "select pending,successful,status,gatewayResponse from MoneyTransfer where transactionRef = $1", captureId)
            .onFailure(e -> Console.error(logPrefix + "Failed to query MoneyTransfer by transactionRef=" + captureId, e))
            .compose(payments -> {
                if (payments.size() == 1)
                    return updatePaymentStatus(payments.get(0), captureId, captureStatus, textPayload, logPrefix);
                // Not found: completePayment may not have been called yet (race condition).
                // CHECKOUT.ORDER.COMPLETED will arrive shortly and handle it via reference_id.
                Console.warn(logPrefix + payments.size() + " payment(s) found for transactionRef=" + captureId);
                return Future.succeededFuture();
            });
    }

    /**
     * Handles {@code CHECKOUT.ORDER.COMPLETED}.
     *
     * <p>Extracts the {@code reference_id} set on the PayPal purchase unit during
     * {@link PayPalPaymentGateway#initiatePayment} (this equals our internal payment primary key)
     * and looks up the {@link MoneyTransfer} record directly by that ID.
     */
    private static Future<Void> handleOrderCompletedEvent(ReadOnlyAstObject payload, String textPayload, String logPrefix) {
        String orderStatus = AST.lookupString(payload, "resource.status");
        // Extract reference_id and capture ID from purchase_units[0] via string search,
        // because the AST API uses dot notation for objects only (not array indexing).
        String referenceId = extractReferenceIdFromOrderEvent(textPayload);
        String captureId   = extractCaptureIdFromOrderEvent(textPayload);

        if (referenceId == null) {
            Console.warn(logPrefix + "No purchase_units[0].reference_id found in CHECKOUT.ORDER.COMPLETED payload");
            return Future.succeededFuture();
        }

        Object paymentPk = Numbers.toShortestNumber(referenceId);
        String transactionRef = captureId != null ? captureId : AST.lookupString(payload, "resource.id");

        return EntityStore.create()
            .<MoneyTransfer>executeQuery(
                "select pending,successful,status,gatewayResponse from MoneyTransfer where id = $1", paymentPk)
            .onFailure(e -> Console.error(logPrefix + "Failed to query MoneyTransfer by id=" + paymentPk, e))
            .compose(payments -> {
                if (payments.size() == 1)
                    return updatePaymentStatus(payments.get(0), transactionRef, orderStatus, textPayload, logPrefix);
                Console.warn(logPrefix + payments.size() + " payment(s) found for id=" + paymentPk);
                return Future.succeededFuture();
            });
    }

    private static Future<Void> updatePaymentStatus(MoneyTransfer payment, String transactionRef,
                                                    String gatewayStatus, String textPayload,
                                                    String logPrefix) {
        PaymentStatus paymentStatus = PayPalPaymentGateway.mapPayPalCaptureStatusToPaymentStatus(gatewayStatus);
        boolean pending    = paymentStatus.isPending();
        boolean successful = paymentStatus.isSuccessful();
        Object paymentPk   = payment.getPrimaryKey();

        // Skip status update if nothing changed; only refresh the stored gateway response.
        if (payment.isPending() == pending && payment.isSuccessful() == successful
                && gatewayStatus != null && gatewayStatus.equals(payment.getStatus())) {
            UpdateStore updateStore = UpdateStore.createAbove(payment.getStore());
            MoneyTransfer updatable = updateStore.updateEntity(payment);
            updatable.setGatewayResponse(textPayload);
            return updateStore.submitChanges()
                .onSuccess(v -> Console.log(logPrefix + "✅  Updated gatewayResponse (no status change) for payment " + paymentPk))
                .onFailure(e -> Console.error(logPrefix + "⛔️  Failed to update gatewayResponse for payment " + paymentPk, e))
                .mapEmpty();
        }

        return PAYPAL_HISTORY_USER_ID.callAndReturn(() ->
            PaymentService.updatePaymentStatus(
                UpdatePaymentStatusArgument.createCapturedStatusArgument(
                    paymentPk, textPayload, transactionRef, gatewayStatus, pending, successful))
                .onSuccess(v -> Console.log(logPrefix + "✅  Updated status=" + gatewayStatus + " for payment " + paymentPk))
                .onFailure(e -> Console.error(logPrefix + "⛔️  Failed to update status=" + gatewayStatus + " for payment " + paymentPk, e))
        );
    }

    /**
     * Extracts {@code purchase_units[0].reference_id} from a raw PayPal order-event JSON body
     * using a simple string search (avoids requiring array-aware AST traversal).
     */
    private static String extractReferenceIdFromOrderEvent(String jsonBody) {
        int purchaseUnitsPos = jsonBody.indexOf("\"purchase_units\"");
        if (purchaseUnitsPos < 0)
            return null;
        int referenceIdPos = jsonBody.indexOf("\"reference_id\"", purchaseUnitsPos);
        if (referenceIdPos < 0)
            return null;
        int colonPos = jsonBody.indexOf(":", referenceIdPos + "\"reference_id\"".length());
        if (colonPos < 0)
            return null;
        int start = jsonBody.indexOf("\"", colonPos) + 1;
        int end   = jsonBody.indexOf("\"", start);
        if (start <= 0 || end <= start)
            return null;
        return jsonBody.substring(start, end);
    }

    /**
     * Extracts {@code purchase_units[0].payments.captures[0].id} from a raw PayPal order-event
     * JSON body by delegating to {@link PayPalPaymentGateway#extractFirstCaptureId}.
     */
    private static String extractCaptureIdFromOrderEvent(String jsonBody) {
        int purchaseUnitsPos = jsonBody.indexOf("\"purchase_units\"");
        if (purchaseUnitsPos < 0)
            return null;
        return PayPalPaymentGateway.extractFirstCaptureId(jsonBody.substring(purchaseUnitsPos));
    }
}

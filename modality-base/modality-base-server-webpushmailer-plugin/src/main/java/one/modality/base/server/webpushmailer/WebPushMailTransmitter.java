package one.modality.base.server.webpushmailer;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.orm.entity.EntityList;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.session.state.SystemUserId;
import dev.webfx.stack.webpush.WebPushPayload;
import dev.webfx.stack.webpush.WebPushResult;
import dev.webfx.stack.webpush.WebPushServerService;
import dev.webfx.stack.webpush.WebPushSubscription;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Letter;
import one.modality.base.shared.entities.Mail;
import one.modality.base.shared.entities.PushSubscription;
import one.modality.base.shared.entities.PushSubscriptionRecipient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-mail pipeline: resolve the booker's live subscribed devices, send the composed push
 * payload to each, and bookkeep. Bookkeeping ALWAYS marks the mail transmitted — on failure
 * with the error recorded — which is what stops a poison mail from blocking the queue.
 * Two push-specific outcomes on top of the SMTP transmitter's contract:
 * <ul>
 *   <li>an endpoint answering 410/404 is permanently dead — its push_subscription row is
 *       deleted in the same bookkeeping submit (recipient links cascade);</li>
 *   <li>zero successful deliveries re-fires {@code document.trigger_send_system_letter_id}
 *       with the mail's letter: the already-created push row makes the trigger skip the
 *       push branch (V0055 guard), so the second pass composes the EMAIL variant —
 *       automatic email fallback with all composition staying in the database.</li>
 * </ul>
 *
 * @author Bruno Salmon
 */
final class WebPushMailTransmitter {

    private static final String LOG_PREFIX = "[webpush-mailer] ";
    private static final SystemUserId WEBPUSH_MAILER_USER_ID = new SystemUserId("webpush-mailer");
    private static final int TTL_SECONDS = 24 * 3600; // push services hold the message this long for offline devices

    private boolean sandbox;

    void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Succeeds once the mail is processed AND bookkept — including the case where every
     * delivery failed (mail marked transmitted-with-error + email fallback re-fired).
     * Fails only when the bookkeeping submit itself failed, i.e. the mail is still marked
     * untransmitted.
     */
    Future<Void> transmitMail(Mail mail) {
        Letter letter = mail.getLetter();
        String pushContext = letter == null ? null : letter.getPushContext();
        if (pushContext == null) {
            // Hand-inserted or mis-migrated row — the system-letter trigger never creates this
            return completeMail(mail, 0, List.of(),
                    new IllegalStateException("Push mail without a letter push context"), false);
        }
        return loadLiveSubscriptions(mail, pushContext).compose(recipients -> {
            // Java-side twin of the vapid filter in SendPushNotificationExecutor: drop
            // subscriptions whose server identity doesn't match the current VAPID keypair
            // (env crossover after a prod→staging copy, or post-rotation leftovers). A null
            // stored key means "pre-column legacy row" and matches everything.
            String currentVapidKey = WebPushServerService.currentVapidPublicKey();
            Set<String> seenEndpoints = new HashSet<>();
            List<PushSubscription> devices = new ArrayList<>();
            for (PushSubscriptionRecipient recipient : recipients) {
                PushSubscription device = recipient.getSubscription();
                String deviceVapidKey = device.getVapidPublicKey();
                if ((deviceVapidKey == null || deviceVapidKey.equals(currentVapidKey))
                        && seenEndpoints.add(device.getEndpoint()))
                    devices.add(device);
            }
            if (devices.isEmpty()) {
                Console.log(LOG_PREFIX + "No live subscription for mail " + mail.getPrimaryKey() + " — falling back to email");
                return completeMail(mail, 0, List.of(),
                        new IllegalStateException("No live push subscription — fell back to email"), true);
            }
            WebPushPayload payload = buildPayload(mail);
            if (sandbox) {
                // Staging state: exercise the whole pipeline but never reach a real device
                // (a prod→staging DB refresh copies real subscriptions). Swallow + log, like
                // the sandbox mail transport.
                Console.log(LOG_PREFIX + "[sandbox] Would send '" + payload.title() + "' to "
                        + devices.size() + " device(s) for mail " + mail.getPrimaryKey());
                return completeMail(mail, devices.size(), List.of(), null, false);
            }
            Console.log(LOG_PREFIX + "Sending '" + payload.title() + "' to " + devices.size()
                    + " device(s) for mail " + mail.getPrimaryKey());
            return transmitToAllDevices(mail, payload, devices);
        });
    }

    private Future<EntityList<PushSubscriptionRecipient>> loadLiveSubscriptions(Mail mail, String pushContext) {
        Document document = mail.getDocument();
        // Same audience predicate as live_push_endpoints() in the database (V0055) — the
        // trigger's EXISTS at insert time and this list at send time must never disagree.
        // Match by the booking's denormalized email (case-insensitive) OR its person;
        // `distinct` collapses one device holding several recipient rows for the context.
        return mail.getStore().executeQuery(
                "select distinct subscription.endpoint, subscription.p256dhKey, subscription.authKey, subscription.vapidPublicKey"
                        + " from PushSubscriptionRecipient"
                        + " where context=$1 and unsubscribedAt=null and (lower(email)=lower($2) or person=$3)",
                pushContext,
                document == null ? null : document.getEmail(),
                // Plain primary key, not the EntityId wrapper — query parameters are passed verbatim
                document == null || document.getPersonId() == null ? null : document.getPersonId().getPrimaryKey());
    }

    private WebPushPayload buildPayload(Mail mail) {
        Letter letter = mail.getLetter();
        String url = letter == null ? null : letter.getPushUrl();
        return new WebPushPayload(
                mail.getSubject(),  // composed push title (trigger, V0055)
                mail.getContent(),  // composed push body
                url != null ? url : "/",
                // Stable per-mail tag: never collapses distinct notifications, and a re-delivery
                // of the same mail replaces its earlier copy on the device.
                "kbs-mail-" + mail.getPrimaryKey(),
                TTL_SECONDS,
                WebPushPayload.Urgency.NORMAL);
    }

    private Future<Void> transmitToAllDevices(Mail mail, WebPushPayload payload, List<PushSubscription> devices) {
        List<PushSubscription> expiredDevices = new ArrayList<>();
        List<String> failureMessages = new ArrayList<>();
        // Sequential fan-out (a booker has a handful of devices at most); failures are
        // collected, never propagated — the chain always reaches the bookkeeping compose.
        Future<Integer> chain = Future.succeededFuture(0);
        for (PushSubscription device : devices) {
            chain = chain.compose(successCount -> WebPushServerService.send(
                            new WebPushSubscription(device.getEndpoint(), device.getP256dhKey(),
                                    device.getAuthKey(), device.getVapidPublicKey()),
                            payload)
                    .map(result -> {
                        if (result instanceof WebPushResult.Success)
                            return successCount + 1;
                        if (result instanceof WebPushResult.SubscriptionExpired expired) {
                            expiredDevices.add(device);
                            failureMessages.add("endpoint expired (" + expired.statusCode() + ")");
                        } else if (result instanceof WebPushResult.Failed failed)
                            failureMessages.add(failed.message());
                        return successCount;
                    })
                    .otherwise(e -> {
                        failureMessages.add(e.getMessage() != null ? e.getMessage() : e.toString());
                        return successCount;
                    }));
        }
        return chain.compose(successCount -> {
            Throwable error = null;
            boolean fallBackToEmail = false;
            if (successCount == 0) {
                error = new IllegalStateException("All " + devices.size() + " device send(s) failed ("
                        + String.join("; ", failureMessages) + ") — fell back to email");
                fallBackToEmail = true;
            } else if (!failureMessages.isEmpty())
                Console.log(LOG_PREFIX + "Mail " + mail.getPrimaryKey() + " delivered to " + successCount
                        + "/" + devices.size() + " device(s); failures: " + String.join("; ", failureMessages));
            return completeMail(mail, successCount, expiredDevices, error, fallBackToEmail);
        });
    }

    private Future<Void> completeMail(Mail mail, int successCount, List<PushSubscription> expiredDevices,
                                      Throwable error, boolean fallBackToEmail) {
        UpdateStore updateStore = UpdateStore.createAbove(mail.getStore());
        Mail updatedMail = updateStore.updateEntity(mail);
        updatedMail.setTransmitted(true);
        updatedMail.setTransmissionDate(Instant.now());
        if (error != null) {
            String errorMessage = error.getMessage() != null ? error.getMessage() : error.toString();
            if (errorMessage.length() > 254)
                errorMessage = errorMessage.substring(0, 254);
            updatedMail.setError(errorMessage);
        }
        // 410/404 endpoints are permanently dead: delete the device row so future audience
        // resolutions (trigger EXISTS + send-time list) skip it. Recipient links cascade.
        for (PushSubscription expiredDevice : expiredDevices)
            updateStore.deleteEntity(expiredDevice);
        // Email fallback: re-fire the system-letter trigger with this mail's letter. The
        // push row we just bookkept makes the trigger's already-created guard skip the push
        // branch, so the second pass composes and inserts the email variant (V0055).
        Letter letter = mail.getLetter();
        Document document = mail.getDocument();
        if (fallBackToEmail && letter != null && document != null) {
            Document updatedDocument = updateStore.updateEntity(document);
            updatedDocument.setForeignField("triggerSendSystemLetter", letter.getId());
        }
        return WEBPUSH_MAILER_USER_ID.callAndReturn(updateStore::submitChanges)
                .onFailure(submitError -> {
                    // The mail is still untransmitted in the DB; the next poll retries it. No
                    // fast retry — if the DB is down we must not hammer it at send pace.
                    Console.log("⛔️ " + LOG_PREFIX + "Bookkeeping failed for mail " + mail.getPrimaryKey());
                    Console.log(submitError);
                })
                .map(ignored -> null);
    }
}

package one.modality.crm.server.webpush;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.webpush.WebPushSubscription;
import dev.webfx.stack.webpush.spi.WebPushSubscriptionStore;
import one.modality.base.shared.entities.PushSubscription;
import one.modality.base.shared.entities.PushSubscriptionRecipient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link WebPushSubscriptionStore} backed by Modality's {@link PushSubscription}
 * and {@link PushSubscriptionRecipient} entities. Provides:
 * <ul>
 *   <li>{@link #rotate} — used by the SW's rotate-subscription REST handler.</li>
 *   <li>{@link #findRecipients} — used by the
 *       {@code SendPushNotification} BO operation. Interprets the opaque
 *       {@code target} object (which the executor passes through verbatim) as
 *       a {@link ModalityWebPushTarget} and translates the populated FK field
 *       into a DSQL filter on {@code PushSubscriptionRecipient}.</li>
 *   <li>{@link #unsubscribeByEmail} — used by the
 *       {@code UnsubscribePushNotifications} FO operation. Soft-deletes by
 *       stamping {@code unsubscribed_at = now()} on every active row matching
 *       the email (no DELETE — keeps the row for opt-out-rate statistics).</li>
 * </ul>
 *
 * @author Bruno Salmon
 */
public final class ModalityWebPushSubscriptionStore implements WebPushSubscriptionStore {

    @Override
    public Future<Boolean> rotate(String oldEndpoint, String newEndpoint,
                                  String newP256dhKey, String newAuthKey,
                                  String userAgent, Instant lastSeenAt) {
        EntityStore loadStore = EntityStore.create();
        return loadStore.<PushSubscription>executeQuery(
                        "select id from PushSubscription where endpoint=$1", oldEndpoint)
                .compose(rows -> {
                    if (rows.isEmpty()) {
                        // No subscription with that endpoint — likely a stale
                        // rotation from a device whose subscription was
                        // already cleaned up server-side. Not an error.
                        return Future.succeededFuture(false);
                    }
                    PushSubscription sub = rows.get(0);

                    UpdateStore updateStore = UpdateStore.createAbove(loadStore);
                    PushSubscription mutable = updateStore.updateEntity(sub);
                    mutable.setEndpoint(newEndpoint);
                    mutable.setP256dhKey(newP256dhKey);
                    mutable.setAuthKey(newAuthKey);
                    mutable.setUserAgent(userAgent);
                    mutable.setLastSeenAt(lastSeenAt);

                    return updateStore.submitChanges()
                            .map(result -> {
                                Console.log("[webpush] rotated subscription " + sub.getPrimaryKey()
                                        + " from " + oldEndpoint + " to " + newEndpoint);
                                return true;
                            });
                });
    }

    @Override
    public Future<List<WebPushSubscription>> findRecipients(Object target, String emailFilter) {
        if (!(target instanceof ModalityWebPushTarget t)) {
            // Defensive — the codec layer should always hand us this type,
            // but a misconfigured client could send a different $codec.
            return Future.failedFuture("Unsupported target type: "
                    + (target == null ? "null" : target.getClass().getName()));
        }

        // V1: exactly one FK supported (event). Document/organization are
        // reserved for V2 — reject cleanly so it's obvious if a client gets
        // ahead of the server.
        if (t.document() != null || t.organization() != null) {
            return Future.failedFuture("ModalityWebPushTarget: document/organization not yet supported");
        }
        if (t.event() == null) {
            return Future.failedFuture("ModalityWebPushTarget: event must be set");
        }

        // `distinct` dedups across the rare case where one person ends up with
        // multiple recipient rows for the same context (e.g., two bookings on
        // the same device both linked to the same event) — without it, that
        // device would receive the push twice for one broadcast.
        // vapidPublicKey is loaded so the executor can drop subscriptions
        // whose server identity doesn't match the current VAPID keypair
        // (env crossover after a prod→staging copy, or post-rotation cleanup).
        // unsubscribedAt is null filters out rows the user soft-deleted via
        // the FO /unsubscribe page — they stay in the DB for stats but must
        // not receive further pushes.
        StringBuilder dql = new StringBuilder(
                "select distinct subscription.endpoint, subscription.p256dhKey, subscription.authKey,"
                + " subscription.vapidPublicKey"
                + " from PushSubscriptionRecipient where event=$1 and unsubscribedAt is null");
        Object[] params;
        if (emailFilter != null) {
            dql.append(" and email=$2");
            params = new Object[]{t.event(), emailFilter};
        } else {
            params = new Object[]{t.event()};
        }

        return EntityStore.create()
                .<PushSubscriptionRecipient>executeQuery(dql.toString(), params)
                .map(recipients -> {
                    List<WebPushSubscription> subs = new ArrayList<>(recipients.size());
                    for (PushSubscriptionRecipient r : recipients) {
                        PushSubscription s = r.getSubscription();
                        subs.add(new WebPushSubscription(
                                s.getEndpoint(), s.getP256dhKey(), s.getAuthKey(),
                                s.getVapidPublicKey()));
                    }
                    return subs;
                });
    }

    @Override
    public Future<Integer> unsubscribeByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Future.succeededFuture(0);
        }
        // Bulk soft-delete via DQL — much lighter than loading every row into
        // an EntityStore + UpdateStore. The `unsubscribedAt is null` guard
        // makes the operation idempotent (re-running on already-unsubscribed
        // rows is a no-op, and the row count reflects only newly-disabled
        // opt-ins, which is what surfaces to the user).
        String dql = "update PushSubscriptionRecipient"
                + " set unsubscribedAt = now()"
                + " where lower(email) = lower($1) and unsubscribedAt is null";
        return SubmitService.executeSubmit(SubmitArgument.builder()
                        .setLanguage("DQL")
                        .setStatement(dql)
                        .setParameters(email)
                        .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                        .build())
                .map(result -> {
                    int n = result.getRowCount();
                    Console.log("[webpush] unsubscribed " + n + " recipient row(s) for email=" + email);
                    return n;
                });
    }
}

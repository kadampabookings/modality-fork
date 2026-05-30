package one.modality.crm.server.webpush;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
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
                        "select id from PushSubscription where endpoint=?", oldEndpoint)
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
        StringBuilder dql = new StringBuilder(
                "select distinct subscription.endpoint, subscription.p256dhKey, subscription.authKey,"
                + " subscription.vapidPublicKey"
                + " from PushSubscriptionRecipient where event=?");
        Object[] params;
        if (emailFilter != null) {
            dql.append(" and email=?");
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
}

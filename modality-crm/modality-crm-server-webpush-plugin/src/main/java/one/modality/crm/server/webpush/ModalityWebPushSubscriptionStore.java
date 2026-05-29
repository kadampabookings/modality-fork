package one.modality.crm.server.webpush;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.webpush.spi.WebPushSubscriptionStore;
import one.modality.base.shared.entities.PushSubscription;

import java.time.Instant;

/**
 * {@link WebPushSubscriptionStore} backed by Modality's {@link PushSubscription}
 * entity. Invoked by the rotate-subscription REST endpoint registered by
 * {@code webfx-stack-webpush-server}.
 * <p>
 * Provides only the SPI implementation — the rest of the KBS Web Push
 * machinery (sending pushes, payload encryption, VAPID signing, the REST
 * route itself) lives in the {@code webfx-stack-webpush-server} module.
 *
 * @author Bruno Salmon
 */
public final class ModalityWebPushSubscriptionStore implements WebPushSubscriptionStore {

    @Override
    public Future<Boolean> rotate(String oldEndpoint, String newEndpoint,
                                  String newP256dhKey, String newAuthKey,
                                  String userAgent, Instant lastSeenAt) {
        // Load the row through an EntityStore — we need an entity instance
        // to mutate via UpdateStore.createAbove(), since UpdateStore expects
        // entities tracked by an underlying store.
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

                    // Mutate via an UpdateStore layered on top of the load
                    // store — this is the standard Modality "update one row"
                    // pattern (mirrors PodcastsImportJob.updateStore=createAbove).
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
}

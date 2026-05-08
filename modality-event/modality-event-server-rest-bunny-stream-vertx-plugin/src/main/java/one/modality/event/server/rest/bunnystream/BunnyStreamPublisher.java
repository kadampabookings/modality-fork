package one.modality.event.server.rest.bunnystream;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import one.modality.base.shared.entities.Media;
import one.modality.base.shared.entities.MediaType;
import one.modality.base.shared.entities.ScheduledItem;

/**
 * Idempotent post-transcoding bookkeeping for the publish-video flow.
 *
 * <p>When the status-poll endpoint observes that a Bunny Stream video has
 * reached the FINISHED state, this class:
 * <ol>
 *   <li>Inserts a new {@link Media} row pointing at
 *       {@code bunny:videoId=&lt;guid&gt;&zoneId=&lt;pullZoneId&gt;}.</li>
 *   <li>Flips {@code ScheduledItem.published = true} (and clears
 *       {@code vodDelayed}).</li>
 * </ol>
 *
 * <p>The whole sequence is wrapped in a "did we already do this?" guard keyed
 * on {@code (scheduledItemId, guid)}: a follow-up status poll that also sees
 * FINISHED is a no-op. This keeps polling cheap and safe to retry.
 *
 * <p><b>Front-office push notification:</b> the broadcast that announces the
 * {@code published} flip to connected viewers is fired by the React backoffice
 * client (see {@code PublishVideoTab.tsx}, mirroring the manual VideoTab save
 * path), <i>not</i> from this server-side method. The reason is that
 * server-internal {@code eventBus.publish(...)} calls are not forwarded
 * outbound by the SockJS bridge in this stack — only client-originated publishes
 * are. Restricting this class to DB writes keeps the architecture honest and
 * matches how the back-office's {@code use-video-tab} flow already works.
 */
final class BunnyStreamPublisher {

    /** URL prefix stored on the Media entity to identify a Bunny Stream video. */
    static final String BUNNY_URL_PREFIX = "bunny:videoId=";

    private BunnyStreamPublisher() {
    }

    /**
     * Run the idempotent FINISHED bookkeeping.
     *
     * @param scheduledItemId  the id of the ScheduledItem the video belongs to
     * @param guid             the Bunny Stream video guid that just finished transcoding
     * @param pullZoneId       the Event's Bunny pull zone (e.g. {@code vz-345e022a-72f})
     * @param durationMillis   video length reported by Bunny, or {@code null} if unknown
     * @return a Future that completes once the work is done (or skipped because already done)
     */
    static Future<Void> finalizeFinishedUpload(long scheduledItemId, String guid, String pullZoneId, Long durationMillis) {
        EntityStore store = EntityStore.create();
        // Idempotency guard: a previous poll for the same (session, guid) may have already
        // inserted the Media row. The url prefix uniquely identifies a Bunny video.
        return store.<Media>executeQuery(
                        "select id from Media where scheduledItem=$1 and url like $2",
                        scheduledItemId, BUNNY_URL_PREFIX + guid + "%")
                .compose(existing -> {
                    if (!existing.isEmpty()) {
                        Console.log("[BUNNY-STREAM] FINISHED already reconciled for session " + scheduledItemId + " / guid " + guid + " — skipping");
                        return Future.<Void>succeededFuture();
                    }
                    return store.<ScheduledItem>executeQuery(
                                    "select id, published from ScheduledItem where id=$1",
                                    scheduledItemId)
                            .compose(items -> {
                                if (items.isEmpty()) {
                                    return Future.failedFuture("ScheduledItem " + scheduledItemId + " not found");
                                }
                                return commit(store, items.get(0), guid, pullZoneId, durationMillis);
                            });
                });
    }

    private static Future<Void> commit(EntityStore store, ScheduledItem session, String guid, String pullZoneId, Long durationMillis) {
        UpdateStore updateStore = UpdateStore.createAbove(store);

        // Insert Media row with the Bunny Stream URL scheme — frontoffice playback knows
        // how to expand it into the HLS manifest URL (see kbs3-react/.../video-url.ts).
        Media media = updateStore.insertEntity(Media.class);
        media.setUrl(BUNNY_URL_PREFIX + guid + "&zoneId=" + pullZoneId);
        media.setScheduledItem(session);
        media.setType(MediaType.of("VOD"));
        media.setOrd(0);
        if (durationMillis != null && durationMillis > 0)
            media.setDurationMillis(durationMillis);

        // Flip publish flag on the session itself. Working through the UpdateStore so
        // the change rides along in the same transaction as the Media insert.
        // Also clear vodDelayed: the manual back-office save treats published and
        // vodDelayed as mutually exclusive (use-video-tab.ts:330-337), and the
        // front-office "Delayed" pill keys off vodDelayed independently of published —
        // without this the pill would stay even after the session is live.
        ScheduledItem editable = updateStore.updateEntity(session);
        editable.setPublished(Boolean.TRUE);
        editable.setVodDelayed(Boolean.FALSE);

        // PK comes back from JDBC as an Integer for this column — going via
        // Number is safe regardless of the runtime numeric type. The previous
        // `(Long) session.getPrimaryKey()` cast threw ClassCastException and
        // returned a 500 from the /rest/videos/status endpoint.
        long sessionId = ((Number) session.getPrimaryKey()).longValue();

        return updateStore.submitChanges()
                .map(result -> {
                    Console.log("[BUNNY-STREAM] Published session " + sessionId + " (guid " + guid + ")");
                    return (Void) null;
                });
    }

    /** Used by the status endpoint to surface "already published" to the client without
     *  re-running the bookkeeping. */
    static Future<Boolean> isAlreadyPublished(long scheduledItemId, String guid) {
        return EntityStore.create()
                .<Media>executeQuery("select id from Media where scheduledItem=$1 and url like $2",
                        scheduledItemId, BUNNY_URL_PREFIX + guid + "%")
                .map(rows -> !rows.isEmpty());
    }
}

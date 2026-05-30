package one.modality.crm.server.webpush;

/**
 * Modality's concrete {@code target} type for the {@code SendPushNotification}
 * BO operation. Carries the FK that scopes the recipient set; exactly one of
 * the fields should be non-null per send.
 * <p>
 * Wire-shape: serialised with {@code $codec = "ModalityWebPushTarget"} via
 * {@code ModalityWebPushTargetSerialCodec}, then handed back to
 * {@link ModalityWebPushSubscriptionStore#findRecipients} which translates
 * the populated field into a DSQL filter on {@code PushSubscriptionRecipient}.
 * <p>
 * V1 supports event-only — {@code document} / {@code organization} are
 * reserved for V2; the store impl rejects targets that set them today so any
 * client that tries surfaces a clear error rather than silently broadcasting.
 *
 * @param event        Event primary key to scope the recipient set to, or null.
 * @param document     Document primary key, or null. (Reserved — not yet supported.)
 * @param organization Organization primary key, or null. (Reserved — not yet supported.)
 *
 * @author Bruno Salmon
 */
public record ModalityWebPushTarget(
        Object event,
        Object document,
        Object organization
) { }

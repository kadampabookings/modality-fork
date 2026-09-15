package one.modality.ecommerce.document.service.spi;

import one.modality.ecommerce.document.service.*;

import dev.webfx.platform.async.Future;

/**
 * @author Bruno Salmon
 */
public interface DocumentServiceProvider {

    Future<DocumentAggregate> loadDocument(LoadDocumentArgument argument);

    Future<DocumentAggregate[]> loadDocuments(LoadDocumentArgument argument);

    Future<SubmitDocumentChangesResult> submitDocumentChanges(SubmitDocumentChangesArgument argument);

    Future<Boolean> leaveEventQueue(Object queueToken);

    Future<SubmitDocumentChangesResult> fetchEventQueueResult(Object queueToken);

    /** Mints a room-share invite token for the given booking's room (steps 4-5). */
    Future<String> mintMateInviteToken(Object documentId);

    /**
     * Reports whether a room-share invite link can still be followed, as one of USABLE, FULL,
     * EXPIRED or UNKNOWN — and nothing else. See {@code MateInviteStatus}.
     */
    Future<String> resolveMateInvite(String token, Object eventId);

    /**
     * For a room-share invite link that can still be followed, describes the room it joins — its
     * accommodation item and the room booking's first and last attendance day, as a small JSON object —
     * so the booking form can answer its first page for the invited mate (step 7). Empty for any other
     * link. Never names anyone.
     */
    Future<String> describeMateInviteRoom(String token, Object eventId);

}

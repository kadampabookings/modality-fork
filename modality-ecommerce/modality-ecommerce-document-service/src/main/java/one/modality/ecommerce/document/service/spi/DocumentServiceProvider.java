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

    Future<Void> sendMateInvitation(Object documentId, int mateSlot, String email, String lang);

    Future<String> listMateInvitations(Object documentId);

    Future<Integer> revokeMateInvitations(Object documentId, int mateSlot);

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

    /**
     * Ticks payments verified, or takes the tick back, as the back office's Finance pages ask — see
     * {@code DocumentService.verifyMoneyTransfers}. Answers a JSON object of counts; never names anyone.
     */
    Future<String> verifyMoneyTransfers(Object organizationId, Object[] transferIds, boolean verified);

    /** Records a payment, or with a negative amount a refund, on a booking — see {@code DocumentService.recordPayment}. */
    Future<Object> recordPayment(Object documentId, Object methodId, Object amount, Object wireless,
                                 Object transactionRef, Object comment, Object markArrived);

    /** Moves an amount paid on one booking to another — see {@code DocumentService.transferPayment}. */
    Future<Object> transferPayment(Object fromDocumentId, Object toDocumentId, Object amount, Object comment);

    /** Corrects a payment entered by hand — see {@code DocumentService.editPayment}. */
    Future<Object> editPayment(Object documentId, Object moneyTransferId, Object methodId, Object amount,
                               Object wireless, Object transactionRef, Object comment);

    /** Deletes a payment entered by hand, or a whole transfer — see {@code DocumentService.deletePayment}. */
    Future<Object> deletePayment(Object documentId, Object moneyTransferId, Object comment);

}

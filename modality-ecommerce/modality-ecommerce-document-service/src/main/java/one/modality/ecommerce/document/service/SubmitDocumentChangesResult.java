package one.modality.ecommerce.document.service;

/**
 * @author Bruno Salmon
 */
public record SubmitDocumentChangesResult(
    DocumentChangesStatus status,
    DocumentChangesRejectedReason rejectedReason, // only when status = REJECTED
    // When status = APPROVED, these fields contain some useful information related to the document
    Object documentPrimaryKey,
    Object documentRef,
    Object cartPrimaryKey,
    String cartUuid,
    // When status = REJECTED and reason = SOLD_OUT, these fields report the site and item primary keys that caused the failure
    Object soldOutSitePrimaryKey,
    Object soldOutItemPrimaryKey,
    // When status = ENQUEUED, or when it's the final result pushed after the original request has been enqueued
    Object queueToken,
    int queueTotal, // total number of bookings in the queue at enqueue time (only when status = ENQUEUED)
    boolean priority, // true when this booking doesn't require resource management (fast-tracked, no countdown wait)
    String errorMessage, // used when status = REJECTED and reason = TECHNICAL_ERROR
    // When status = APPROVED and the booking followed a room-share invite with a sharing place to link:
    // MATE_INVITE_LINKED or MATE_INVITE_NOT_LINKED. Null otherwise (no invite, or nothing to link).
    String mateInvite
) {

    /** The invited mate's booking was linked to the room the invite names. */
    public static final String MATE_INVITE_LINKED = "LINKED";
    /**
     * The invited mate's booking was saved with a sharing place but could NOT be linked — typically
     * someone else following the same link took the last bed first. Reported so the mate is told,
     * rather than leaving believing they share the room.
     */
    public static final String MATE_INVITE_NOT_LINKED = "NOT_LINKED";

    public static SubmitDocumentChangesResult createApprovedResult(Object documentPrimaryKey, Object documentRef, Object cartPrimaryKey, String cartUuid) {
        return new SubmitDocumentChangesResult(DocumentChangesStatus.APPROVED, null, documentPrimaryKey, documentRef, cartPrimaryKey, cartUuid, null, null, null, 0, false, null, null);
    }

    public static SubmitDocumentChangesResult createSoldOutResult(Object soldOutSitePrimaryKey, Object soldOutItemPrimaryKey) {
        return new SubmitDocumentChangesResult(DocumentChangesStatus.REJECTED, DocumentChangesRejectedReason.SOLD_OUT, null, null, null, null, soldOutSitePrimaryKey, soldOutItemPrimaryKey, null, 0, false, null, null);
    }

    public static SubmitDocumentChangesResult createAlreadyBookedResult() {
        return new SubmitDocumentChangesResult(DocumentChangesStatus.REJECTED, DocumentChangesRejectedReason.ALREADY_BOOKED, null, null, null, null, null, null, null, 0, false, null, null);
    }

    public static SubmitDocumentChangesResult createEventOnHoldResult() {
        return new SubmitDocumentChangesResult(DocumentChangesStatus.REJECTED, DocumentChangesRejectedReason.EVENT_ON_HOLD, null, null, null, null, null, null, null, 0, false, null, null);
    }

    public static SubmitDocumentChangesResult createEnqueuedResult(Object queueToken, int queueTotal, boolean priority) {
        return new SubmitDocumentChangesResult(DocumentChangesStatus.ENQUEUED, null, null, null, null, null, null, null, queueToken, queueTotal, priority, null, null);
    }

    public static SubmitDocumentChangesResult withQueueToken(SubmitDocumentChangesResult result, Object queueToken) {
        // Keeps mateInvite: a queued booking consumes its invite when the queue processes it, and this
        // is the result pushed back to the client afterwards.
        return new SubmitDocumentChangesResult(result.status, result.rejectedReason, result.documentPrimaryKey, result.documentRef, result.cartPrimaryKey, result.cartUuid, result.soldOutSitePrimaryKey, result.soldOutItemPrimaryKey, queueToken, 0, false, null, result.mateInvite);
    }

    public static SubmitDocumentChangesResult withMateInvite(SubmitDocumentChangesResult result, String mateInvite) {
        return new SubmitDocumentChangesResult(result.status, result.rejectedReason, result.documentPrimaryKey, result.documentRef, result.cartPrimaryKey, result.cartUuid, result.soldOutSitePrimaryKey, result.soldOutItemPrimaryKey, result.queueToken, result.queueTotal, result.priority, result.errorMessage, mateInvite);
    }

    public static SubmitDocumentChangesResult technicalErrorResult(String errorMessage, Object queueToken) {
        return new SubmitDocumentChangesResult(DocumentChangesStatus.REJECTED, DocumentChangesRejectedReason.TECHNICAL_ERROR, null, null, null, null, null, null, queueToken, 0, false, errorMessage, null);
    }

}

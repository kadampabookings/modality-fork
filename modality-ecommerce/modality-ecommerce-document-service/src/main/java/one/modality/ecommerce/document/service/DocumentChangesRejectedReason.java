package one.modality.ecommerce.document.service;

/**
 * @author Bruno Salmon
 */
public enum DocumentChangesRejectedReason {

    SOLD_OUT,

    ALREADY_BOOKED,

    EVENT_ON_HOLD,

    // The booking has gained a document line or an attendance since the client loaded the version these
    // changes were built on (see SubmitDocumentChangesArgument.baseDocument). Only front-office
    // modifications that send a base can get it; the client asks the booker to reload.
    BOOKING_CHANGED,

    TECHNICAL_ERROR // Used only when SubmitDocumentChangesArgument has been enqueued and the final result is pushed,
    // otherwise (when not enqueued and processed immediately) the application code should use Future.onFailure() to
    // handle technical errors.

}

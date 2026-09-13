package one.modality.ecommerce.document.service;

import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;

/**
 * @author Bruno Salmon
 */
public record SubmitDocumentChangesArgument(
    String historyComment,
    AbstractDocumentEvent[] documentEvents,
    boolean queueCapable,
    // The frontend origin used by the server to build/ booking-access magic links for guest confirmation emails.
    // Null for non-web clients and back-office submissions.
    String clientOrigin,
    // Room-share invite token (steps 4-5): when a mate books via an invite link, the client sends the
    // raw token here and the SERVER consumes it — validating it and linking the mate to the room
    // booker it names. Null on every other submission. The client never sends an owner line id; the
    // token is what names the owner, so the client cannot choose where the link lands.
    String inviteToken
) {

    // Alternative factory method for simple changes (1 change in most cases but possibly several) that avoids
    // array creation. In addition, queueCapable is false by default in this case because such simple changes are made
    // in contexts that don't support queueing (ex: client dialog or server call), as opposed to the context of a
    // booking form which can show the progress of the bookings queue.

    // Note: providing a second constructor instead of a factory method causes a GWT crash

    public static SubmitDocumentChangesArgument of(String historyComment, AbstractDocumentEvent... documentEvents) {
        return new SubmitDocumentChangesArgument(historyComment, documentEvents, false, null, null);
    }
}

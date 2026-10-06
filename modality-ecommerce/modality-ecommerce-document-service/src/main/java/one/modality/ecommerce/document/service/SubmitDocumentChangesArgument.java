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
    // booker it names. Null on every other submission. An INVITED mate cannot name a line: the token is
    // what names the owner, so a forwarded link cannot be pointed at a different room.
    String inviteToken,
    // Room-share by the BOOKER themselves (room-mate plan Part C): having booked a room, they book their
    // roommate second and name that room's line here, which submit 1 returned as the result's
    // ownerDocumentLine. The server links after the write, as it does for a token.
    //
    // Naming a line is safe HERE and not for an invited mate, because the two are authorised differently.
    // A token is an unguessable credential; a line id is sequential and shown in the UI, so what stands in
    // for the token is an ownership check — the line must belong to a booking of the submitting account
    // (MateLinkRules). That check is load-bearing, not defence in depth: Part C switches the bed count off
    // for this path, so without it a guessed id would be guaranteed to attach a mate to a stranger's room
    // rather than merely likely to.
    Object ownerDocumentLine,
    // The booking these changes were built on, for a front-office modification: the document, and the
    // highest document line and attendance ids it held when the client loaded it (counted as the server
    // loads them: lines with a site, present attendances). Changes are a diff against that booking and
    // the server applies them as sent, so a client still holding it after its own submit re-added the
    // same lines a second time (bookings 674 and 1164 paid their shuttles twice). When the booking has
    // gained a line or an attendance since, the submit is refused as BOOKING_CHANGED. Letters,
    // payments and flags add neither, so they never count. Null on every other submission — new
    // bookings and the back office are not checked.
    Object baseDocument,
    Object baseLastDocumentLine,
    Object baseLastAttendance
) {

    // Alternative factory method for simple changes (1 change in most cases but possibly several) that avoids
    // array creation. In addition, queueCapable is false by default in this case because such simple changes are made
    // in contexts that don't support queueing (ex: client dialog or server call), as opposed to the context of a
    // booking form which can show the progress of the bookings queue.

    // Note: providing a second constructor instead of a factory method causes a GWT crash

    public static SubmitDocumentChangesArgument of(String historyComment, AbstractDocumentEvent... documentEvents) {
        return new SubmitDocumentChangesArgument(historyComment, documentEvents, false, null, null, null, null, null, null);
    }
}

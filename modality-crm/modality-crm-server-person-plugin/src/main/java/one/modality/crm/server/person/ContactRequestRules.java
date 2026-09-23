package one.modality.crm.server.person;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.Document;

/**
 * "I have a question about my booking" — the centre's contact form, composed by the server.
 *
 * <p>The second slice of the mail arc, after {@link RefundRequestRules}, and the one the plan names
 * as the most urgent: it is reachable from the orders page and the media-access notice, in BOTH apps,
 * by members and guests alike.
 *
 * <h3>What the caller still chooses, and what they no longer do</h3>
 *
 * <p>Unlike the refund request, this one genuinely carries the caller's own words — a subject and a
 * message are the whole point of a contact form. Everything else was never theirs and is now read from
 * the booking: the {@code [event-ref]} prefix the centre threads on, the display name, the reply
 * address, and the document the mail hangs off. The browser used to send all of them, which meant a
 * caller could put any name and any reply address on a mail leaving a Kadampa-verified sender.
 *
 * <h3>The body is escaped, which it was not before</h3>
 *
 * <p>See {@link BookingMail#escapeHtml}. The dialog interpolated the member's text straight into an
 * HTML document. This is the change in this slice that is a fix rather than a move.
 *
 * <h3>Who may ask</h3>
 *
 * <p>Whoever can already see the booking on their orders page, by the same two tests the refund
 * request uses and for the same reasons — {@link BookingMail#MEMBER_TEST} spans two person rows, and
 * guests reach this form too. Which test applies is read from the PRINCIPAL, never from the argument.
 * Both ride in the WHERE of the read, so a booking the caller may not see is never loaded.
 *
 * <p>There is deliberately no overpayment clause here, unlike the refund read: any booking the caller
 * can see is one they may ask a question about.
 *
 * <h3>Where it goes</h3>
 *
 * <p>{@code out=false} with a document and no recipient row, so the {@code auto_recipient} trigger
 * addresses it to the centre — the existing behaviour. {@code account_id} is left out for the same
 * reason: {@code auto_account} fires only {@code WHEN (new.account_id IS NULL)} and derives the
 * account from the booking. A value supplied here would win over the trigger, and choosing which
 * verified address a mail leaves from is precisely what is being taken away from callers.
 *
 * @author Claude Code
 */
final class ContactRequestRules {

    private ContactRequestRules() {
    }

    /**
     * What the dialog's own inputs allow.
     *
     * <p>Mirrored rather than relaxed: the browser caps at these, so an honest caller cannot reach
     * them, and a caller who does is not one. Over-length is REFUSED rather than truncated — a member
     * who watched their words go into the box should not have them silently cut on the way.
     *
     * <p>Changing the dialog's {@code SUBJECT_MAX_LENGTH} or {@code MESSAGE_MAX_LENGTH} means changing
     * these; {@code mail.subject} is {@code varchar(255)} and holds the prefix as well, which is the
     * hard limit underneath.
     */
    static final int MAX_SUBJECT = 100;
    static final int MAX_MESSAGE = 1000;

    /** Not this caller's booking, or not a booking at all. One message for both. */
    static final String NOT_YOURS_KEY = "ContactNotYourBookingError";

    /** Empty, or longer than the form allows. */
    static final String BAD_MESSAGE_KEY = "ContactMessageInvalidError";

    /**
     * What the message is built from, read from the booking — one variant per kind of booker.
     *
     * <p>Built from one string so the select lists cannot drift apart, the way the refund pair is.
     */
    private static final String BOOKING_DQL =
        "select ref, person_firstName, person_lastName, person_email, event" +
        " from Document where id=$1 and ";

    static final String MEMBER_DQL = BOOKING_DQL + BookingMail.MEMBER_TEST;

    static final String GUEST_DQL = BOOKING_DQL + BookingMail.GUEST_TEST;

    /** The prefix the centre threads on, kept exactly as the dialog built it. */
    static String subject(Object eventId, String ref, String callerSubject) {
        return "[" + eventId + "-" + (ref == null ? "" : ref) + "] " + callerSubject;
    }

    /** The caller's words, escaped, with their line breaks kept. */
    static String content(String message) {
        return "<html>" + BookingMail.escapeHtml(message).replaceAll("\r?\n", "<br/>") + "</html>";
    }

    /**
     * Sends the caller's question to the centre as a mail against their booking.
     *
     * @param documentId   the booking, which must be one this caller can see
     * @param rawSubject   the caller's own subject line, without the prefix
     * @param rawMessage   the caller's own message
     * @param callerScope  the caller's account id for a member, or their proven address for a guest
     * @param guest        which of the two tests to apply — decided from the principal, never the argument
     * @param callerUserId the caller's principal, READ ON THE CALLER'S THREAD, so the rows carry an author
     */
    static Future<Object> send(Object documentId, String rawSubject, String rawMessage,
                               Object callerScope, boolean guest, Object callerUserId) {
        if (documentId == null || callerScope == null)
            return refused(NOT_YOURS_KEY);
        String subject = singleLine(rawSubject);
        String message = trimmed(rawMessage);
        if (!isSendable(subject, message))
            return refused(BAD_MESSAGE_KEY);
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return ServerWrite.asServer(() -> entityStore.<Document>executeQuery(
                guest ? GUEST_DQL : MEMBER_DQL, documentId, callerScope))
            .compose(rows -> rows == null || rows.isEmpty() ? refused(NOT_YOURS_KEY)
                : BookingMail.replyAddress(rows.get(0), callerScope, guest, entityStore)
                    .compose(replyTo -> write(rows.get(0), documentId, subject, message, replyTo, callerUserId)));
    }

    private static Future<Object> write(Document booking, Object documentId, String callerSubject,
                                        String message, String replyTo, Object callerUserId) {
        // getPrimaryKey, because a foreign field evaluates to an EntityId whose toString is
        // "ID[Event:1857]" - which would have gone into the subject line the centre threads on.
        Object event = booking.evaluate("event"); // via a variable: evaluate() is generic, and the
        Object eventId = Entities.getPrimaryKey(event); // Entity/EntityId overloads are ambiguous
        Integer ref = booking.getRef();
        String subject = subject(eventId, ref == null ? null : String.valueOf(ref), callerSubject);
        String fromName = BookingMail.name(booking.getStringFieldValue("person_firstName"),
            booking.getStringFieldValue("person_lastName"));

        SubmitArgument mail = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            // " returning id" is load-bearing: without it the batch carries no generated key and the
            // history row's reference below resolves to nothing, so every call fails.
            .setStatement("insert into mail (document_id, out, subject, content, from_name, from_email)" +
                          " values ($1, false, $2, $3, $4, $5) returning id")
            .setParameters(documentId, subject, content(message), fromName, replyTo)
            .setReturnGeneratedKeys(true)
            .build();
        // The history line the dialog used to write, kept so the centre sees the message in the
        // booking's own trail. It names the mail through the batch's generated key rather than a
        // second read - the same mechanism the change set used, here on the server's side of the guard.
        SubmitArgument history = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement("insert into history (document_id, mail_id, username, comment)" +
                          " values ($1, $2, 'online', $3)")
            .setParameters(documentId, new GeneratedKeyReference(0), "Sent " + subject)
            .build();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { mail, history })))
            .map(ignored -> (Object) Boolean.TRUE);
    }

    /** Null-safe trim, so the caller's leading newlines are not what makes a message non-empty. */
    static String trimmed(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * The caller's subject, on one line.
     *
     * <p>{@code mail.subject} becomes a mail HEADER — {@code MailTransmitter} passes it straight to
     * {@code .subject(...)} — and a header holding a newline is how a second header gets added. The
     * dialog's {@code <input type="text">} cannot produce one, which is exactly why this has to be
     * here: the browser is not what is being defended against once there is an endpoint.
     *
     * <p>Control characters are replaced rather than refused: they are invisible, so a caller who
     * pasted one would be told their subject was invalid with nothing on screen to explain it. A RUN
     * of them becomes a single space rather than nothing, so a subject pasted from two lines reads as
     * two words — dropping them outright would run those words together.
     */
    static String singleLine(String raw) {
        if (raw == null)
            return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= ' ' && c != '\u007F')
                out.append(c);
            else if (out.length() > 0 && out.charAt(out.length() - 1) != ' ')
                out.append(' ');
        }
        return out.toString().trim();
    }

    /**
     * Whether these are words the form could have produced.
     *
     * <p>Separate and pure so it can be checked without a database — the limits are the whole of the
     * server-side enforcement, and the browser's {@code maxLength} is UX rather than a control.
     */
    static boolean isSendable(String trimmedSubject, String trimmedMessage) {
        return !trimmedSubject.isEmpty() && !trimmedMessage.isEmpty()
               && trimmedSubject.length() <= MAX_SUBJECT && trimmedMessage.length() <= MAX_MESSAGE;
    }

    private static <T> Future<T> refused(String key) {
        return Future.failedFuture("[" + key + "] This operation is not available to you");
    }
}

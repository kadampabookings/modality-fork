package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.History;
import one.modality.base.shared.entities.Letter;
import one.modality.base.shared.entities.Mail;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.function.Function;

/**
 * Sending a booking's letter, as an authorised act rather than as a column write.
 *
 * <p><b>What this closes.</b> A letter is composed and addressed by the database: {@code send_letter}
 * fires on {@code UPDATE OF document.trigger_send_letter_id}, inserts a {@code mail} with no recipient,
 * and {@code trigger_mail_auto_recipient} then derives the address from the document. Every letter that
 * carries {@code [yourCart]} — Confirmation, Cart, Cancellation, No-deposit; measured 2026-10-16 — takes
 * the branch where {@code document.person_email} beats the account login, and that column is one a client
 * may write. So a back-office caller could set a booking's email to their own and fire the trigger, and
 * the database would post them that booking's letter, cart link included. The cart link is a BEARER
 * TOKEN: a guest session for the booking, payment included.
 *
 * <p><b>Why the obvious fixes were not taken.</b> Making the account login win would change the recipient
 * of 23,609 documents (8%) whose attendee address legitimately differs from the account's — a member
 * booking for a spouse or child. And the column's value cannot distinguish a staff typo-fix from a
 * redirect, so no rule over its content closes this. What is left is structural: the firing column stops
 * being client-writable, and the send becomes a named act, separately granted, scoped to the booking's
 * owner, and logged. A caller holding both grants can still edit and then send — but not silently, not on
 * another centre's bookings, and not without a line saying who did it.
 *
 * <p>See {@code docs/security/frontoffice-write-authorization-plan.md}, "Document — the letter-redirect
 * interception", for the measurements and the two rejected alternatives.
 *
 * <p><b>The letter is checked too</b> ({@link LetterApplicability}), not only the booking: it must be
 * one the back office's picker offers for this booking. And the send is <b>confirmed</b>: the trigger
 * silently composes nothing for a letter with no sending account or no text in the person's language,
 * so the answer is true only once a new mail row exists, and only then is the booking's history told
 * "Sent '<subject>'" — the line KBS2's "Send a letter" wrote, naming the mail and the sender.
 *
 * @author Claude Code
 */
final class DocumentLetterRules {

    /** Seeded with no {@code grant_route}: it gates a control, not a page (V0106's precedent). */
    static final String SEND_DOCUMENT_LETTER_OPERATION = "SendDocumentLetter";

    private static final String LOG_PREFIX = "[letter] ";
    private static final String TRIGGER_SEND_LETTER = "triggerSendLetter";

    /**
     * The failure when the trigger composed nothing. Bracketed like the other refusal keys, so the
     * client can tell it apart and say why, rather than "could not send".
     */
    static final String NOT_COMPOSED_KEY = "LetterNotComposed";

    /** The newest mail of a booking for a letter — compared before and after the send. */
    static final String LAST_MAIL_DQL = "select id,subject from Mail where document=$1 and letter=$2 order by id desc limit 1";

    private DocumentLetterRules() {
    }

    /**
     * Fires the booking's letter, once the caller is shown to hold {@code SendDocumentLetter} for the
     * organization and event that own it.
     *
     * <p>The owner is read from the DOCUMENT, never taken from the call: a caller naming an organization
     * they hold is exactly how a scoped grant gets used on somebody else's row.
     *
     * <p>A document with no event is refused. Every booking has one, and a row whose owner cannot be
     * resolved is not a row anybody holds a scoped grant over — guessing "then anywhere will do" is how
     * the fence gets dropped quietly.
     *
     * @param state        the caller's state, captured on the caller's thread by the endpoint
     * @param callerUserId likewise — it is what the audit triggers stamp
     */
    static Future<Boolean> sendLetter(Object state, Object documentId, Object letterId, Object callerUserId) {
        if (letterId == null)
            return RouteAccessGuard.refused();
        return forLetterSenderOf(state, documentId, "Send", (store, document, callerPersonId) ->
            checkLetterThenFire(store, document, letterId, callerUserId, callerPersonId));
    }

    /**
     * One of a booking's mails, content included, for the back office's mail viewer — to the same callers
     * who may send that booking letters, and to nobody else.
     *
     * <p><b>Why it is fenced like a send.</b> A composed mail is the letter as the booker received it: their
     * details, and the {@code [yourCart]} link, which is a bearer token for their booking. Reading one is
     * holding that token. So it is granted on {@code SendDocumentLetter} in the booking's own organization
     * and event, which a read-only role (the Data Protection Officer's, on staging) does not hold, and each
     * read is logged with who asked. The mail must belong to the booking named — a mail id alone is never
     * enough, so a caller with a grant over one booking cannot read another's mails by guessing ids.
     *
     * @return the mail's content (possibly empty), or a refusal — the same one whether the booking or the
     *         mail is missing or the caller is not allowed near it
     */
    static Future<String> readMailContent(Object state, Object documentId, Object mailId, Object callerUserId) {
        if (mailId == null)
            return RouteAccessGuard.refused();
        return forLetterSenderOf(state, documentId, "Mail read", (store, document, callerPersonId) ->
            ServerWrite.asServer(() -> store.<Mail>executeQuery(MAIL_CONTENT_DQL, mailId, Entities.getPrimaryKey(document)))
                .compose(mails -> {
                    // Refusals are logged too: a caller walking mail ids against a booking they hold reads
                    // nothing, but should not do it without a trace.
                    if (mails.isEmpty()) {
                        Console.log(LOG_PREFIX + "Mail read refused: mail " + mailId + " is not of document "
                                    + Entities.getPrimaryKey(document) + ", asked by person " + callerPersonId);
                        return RouteAccessGuard.<String>refused();
                    }
                    Console.log(LOG_PREFIX + "Mail " + mailId + " of document " + Entities.getPrimaryKey(document)
                                + " read by person " + callerPersonId);
                    String content = mails.get(0).getStringFieldValue("content");
                    return Future.succeededFuture(content == null ? "" : content);
                }));
    }

    /** One mail of one booking, its content only. */
    static final String MAIL_CONTENT_DQL = "select content from Mail where id=$1 and document=$2 limit 1";

    /** What runs once the caller is known to hold the grant over the booking. */
    @FunctionalInterface
    interface ForBooking<T> {
        Future<T> apply(EntityStore store, Document document, Object callerPersonId);
    }

    /**
     * The fence both the send and the read stand behind: a back-office caller holding
     * {@code SendDocumentLetter} for the organization and event that own the booking.
     */
    private static <T> Future<T> forLetterSenderOf(Object state, Object documentId, String act, ForBooking<T> action) {
        if (documentId == null)
            return RouteAccessGuard.refused();
        // Refuse an anonymous or front-office caller BEFORE reading anything. The grant itself cannot be
        // checked yet — it is scoped to the booking's owner, which is what the query below is for — but
        // the fences that do not depend on the target can be, and otherwise this address lets anyone at
        // all make the server look up document ids one by one. Nothing is disclosed either way: the same
        // refusal comes back whether the document is missing or the caller is not allowed near it.
        Object callerPersonId = RouteAccessGuard.backOfficeCallerPersonId(state);
        if (callerPersonId == null)
            return RouteAccessGuard.refused();
        EntityStore store = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return ServerWrite.asServer(() -> store.<Document>executeQuery(
                "select " + LetterApplicability.DOCUMENT_FIELDS + " from Document where id=$1 limit 1", documentId))
            .compose(documents -> {
                Document document = documents.isEmpty() ? null : documents.get(0);
                Event event = document == null ? null : document.getEvent();
                if (event == null) {
                    Console.log(LOG_PREFIX + act + " refused: document " + documentId + " has no resolvable event");
                    return RouteAccessGuard.<T>refused();
                }
                return RouteAccessGuard.whenCallerHoldsOperation(state, SEND_DOCUMENT_LETTER_OPERATION,
                    Entities.getPrimaryKey(event.getOrganizationId()), event.getPrimaryKey(),
                    () -> action.apply(store, document, callerPersonId));
            });
    }

    /** Fires a letter the booking is offered, refusing any other. */
    private static Future<Boolean> checkLetterThenFire(EntityStore store, Document document, Object letterId,
                                                       Object callerUserId, Object callerPersonId) {
        return withSendableLetter(store, document, letterId, "Send",
            letter -> fire(store, document, letterId, callerUserId, callerPersonId));
    }

    /**
     * Refuses a letter the booking is not offered ({@link LetterApplicability}) — read only once the
     * caller is known to hold the grant, so the refusal tells nobody else anything about letters — and
     * hands the one it is to {@code action}.
     */
    private static <T> Future<T> withSendableLetter(EntityStore store, Document document, Object letterId, String act,
                                                    Function<Letter, Future<T>> action) {
        return ServerWrite.asServer(() -> store.<Letter>executeQuery(
                "select " + LetterApplicability.LETTER_FIELDS + " from Letter where id=$1 limit 1", letterId))
            .compose(letters -> {
                Letter letter = letters.isEmpty() ? null : letters.get(0);
                if (!LetterApplicability.isSendable(letter, document)) {
                    Console.log(LOG_PREFIX + act + " refused: letter " + letterId + " is not offered for document "
                                + Entities.getPrimaryKey(document));
                    return RouteAccessGuard.<T>refused();
                }
                return action.apply(letter);
            });
    }

    /**
     * The letter as the booker would receive it — subject and body with the brackets filled in from this
     * booking — for the back office's preview before sending (KBS2 showed it filled in, and it is the
     * desk's check that the letter says the right things to the right person).
     *
     * <p>Composed by {@code letter_compose_email} in {@code letter_mail_lang}'s language: the very
     * functions {@code trigger_document_send_letter} sends with, so the preview is what goes out. It is a
     * READ, and enforced as one: {@code letter_preview()} (V0182) switches its transaction to read only
     * before composing, because bracket patterns are SQL the database executes, and the query path runs in
     * autocommit with no read-only transaction of its own. Nothing is queued, and no history is written.
     *
     * <p>Fenced and logged like a mail read, because it is one in advance: the filled-in letter carries the
     * booking's cart link, a bearer token. Only letters the booking is offered can be previewed.
     *
     * @return {@code [subject, content]}, both null when the letter has no text to send in that language
     */
    static Future<Object[]> previewLetter(Object state, Object documentId, Object letterId) {
        if (letterId == null)
            return RouteAccessGuard.refused();
        return forLetterSenderOf(state, documentId, "Preview", (store, document, callerPersonId) ->
            withSendableLetter(store, document, letterId, "Preview", letter ->
                ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                        .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                        .setStatement(PREVIEW_SQL)
                        // The CHECKED letter's key, not the caller's value: what was checked is what is composed.
                        .setParameters(Entities.getPrimaryKey(letter), Entities.getPrimaryKey(document))
                        .build()))
                    .map(result -> {
                        Console.log(LOG_PREFIX + "Letter " + letterId + " previewed for document "
                                    + Entities.getPrimaryKey(document) + " by person " + callerPersonId);
                        boolean found = result != null && result.getRowCount() > 0;
                        return new Object[] { found ? result.getValue(0, 0) : null, found ? result.getValue(0, 1) : null };
                    })));
    }

    /**
     * The letter composed for the booking exactly as the send trigger composes it, in a read-only transaction:
     * {@code letter_preview()} (V0182) wraps {@code letter_mail_lang} + {@code letter_compose_email}.
     */
    static final String PREVIEW_SQL = "select subject, content from letter_preview($1, $2)";

    /**
     * The write itself, as the server but in the caller's name — {@link ServerWrite#asServerActingFor}:
     * the client-origin stamp has to go (the write path refuses a raw statement that carries it) while
     * the principal has to stay (it is what {@code document}'s audit triggers record).
     *
     * <p>Then the confirmation: the newest mail of this booking for this letter, before and after. A new
     * one means the trigger composed it; none means it skipped the letter (no sending account, no text
     * in the person's language — {@code trigger_document_send_letter}, V0160) or that
     * {@code auto_recipient} dropped a mail it could not address (V0059), and the call fails with
     * {@link #NOT_COMPOSED_KEY} instead of reporting a send that did not happen.
     *
     * <p><b>Known limit:</b> the two reads are not atomic with the send. Two people sending the same
     * letter to the same booking in the same second can both see the second mail, so one history line
     * names the other's mail; another writer of a mail for this letter between the reads could turn a
     * skipped send into a reported one. Both need concurrent sends of one letter to one booking, which
     * the desk does not do; closing them for good means the trigger returning its mail id.
     *
     * <p>The log line is the point as much as the write is. A redirect leaves a {@code mail} row addressed
     * to wherever it went; this says who asked for it, which that row does not.
     */
    private static Future<Boolean> fire(EntityStore store, Document document, Object letterId,
                                        Object callerUserId, Object callerPersonId) {
        Object documentId = Entities.getPrimaryKey(document);
        return lastMail(store, documentId, letterId)
            .compose(before -> ServerWrite.asServerActingFor(callerUserId, () -> {
                    UpdateStore updateStore = UpdateStore.createAbove(store);
                    Document update = updateStore.updateEntity(document);
                    update.setForeignField(TRIGGER_SEND_LETTER, letterId);
                    return updateStore.submitChanges();
                })
                .compose(ignored -> lastMail(store, documentId, letterId))
                .compose(after -> {
                    if (after == null || (before != null && Entities.samePrimaryKey(before, after))) {
                        Console.log(LOG_PREFIX + "Letter " + letterId + " composed no mail for document " + documentId
                                    + " (no sending account, no text in the booker's language, or no address)");
                        return Future.<Boolean>failedFuture("[" + NOT_COMPOSED_KEY + "] The letter was not sent: no mail"
                                                            + " could be composed or addressed for this booking");
                    }
                    Console.log(LOG_PREFIX + "Letter " + letterId + " sent for document " + documentId
                                + " by person " + callerPersonId);
                    return recordHistory(store, document, after, callerUserId, callerPersonId);
                }));
    }

    /** The newest mail of the booking for the letter, or null when there is none. */
    private static Future<Mail> lastMail(EntityStore store, Object documentId, Object letterId) {
        return ServerWrite.asServer(() -> store.<Mail>executeQuery(LAST_MAIL_DQL, documentId, letterId))
            .map(mails -> mails.isEmpty() ? null : mails.get(0));
    }

    /**
     * Tells the booking's history the letter went: "Sent '<subject>'" (the subject as the booker got it),
     * naming the mail and the sender, as KBS2's "Send a letter" did.
     *
     * <p>Written AFTER the send, in its own transaction, and a failure here does not fail the call: the
     * mail is already queued, and answering "not sent" would invite a second, duplicate send.
     */
    private static Future<Boolean> recordHistory(EntityStore store, Document document, Mail mail,
                                                 Object callerUserId, Object callerPersonId) {
        return ServerWrite.asServerActingFor(callerUserId, () -> {
                UpdateStore updateStore = UpdateStore.createAbove(store);
                History history = updateStore.insertEntity(History.class);
                history.setDocument(document);
                history.setMail(mail);
                history.setUserPerson(callerPersonId);
                history.setComment("Sent '" + mail.getSubject() + "'");
                return updateStore.submitChanges();
            })
            .compose(ignored -> Future.succeededFuture(true),
                error -> {
                    Console.log(LOG_PREFIX + "History not recorded for mail " + Entities.getPrimaryKey(mail)
                                + " of document " + Entities.getPrimaryKey(document) + ": " + error.getMessage());
                    return Future.succeededFuture(true);
                });
    }
}

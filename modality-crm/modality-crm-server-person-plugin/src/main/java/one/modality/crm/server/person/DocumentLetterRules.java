package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.UpdateStore;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Event;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

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
 * @author Claude Code
 */
final class DocumentLetterRules {

    /** Seeded with no {@code grant_route}: it gates a control, not a page (V0106's precedent). */
    static final String SEND_DOCUMENT_LETTER_OPERATION = "SendDocumentLetter";

    private static final String LOG_PREFIX = "[letter] ";
    private static final String TRIGGER_SEND_LETTER = "triggerSendLetter";

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
        if (documentId == null || letterId == null)
            return RouteAccessGuard.refused();
        // Refuse an anonymous or front-office caller BEFORE reading anything. The grant itself cannot be
        // checked yet — it is scoped to the booking's owner, which is what the query below is for — but
        // the fences that do not depend on the target can be, and otherwise this address lets anyone at
        // all make the server look up document ids one by one. Nothing is disclosed either way: the same
        // refusal comes back whether the document is missing or the caller is not allowed near it.
        if (RouteAccessGuard.backOfficeCallerPersonId(state) == null)
            return RouteAccessGuard.refused();
        EntityStore store = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return ServerWrite.asServer(() -> store.<Document>executeQuery(
                "select event.(id,organization) from Document where id=$1 limit 1", documentId))
            .compose(documents -> {
                Document document = documents.isEmpty() ? null : documents.get(0);
                Event event = document == null ? null : document.getEvent();
                if (event == null) {
                    Console.log(LOG_PREFIX + "Send refused: document " + documentId + " has no resolvable event");
                    return RouteAccessGuard.<Boolean>refused();
                }
                return RouteAccessGuard.whenCallerHoldsOperation(state, SEND_DOCUMENT_LETTER_OPERATION,
                    Entities.getPrimaryKey(event.getOrganizationId()), event.getPrimaryKey(),
                    () -> fire(store, document, letterId, callerUserId));
            });
    }

    /**
     * The write itself, as the server but in the caller's name — {@link ServerWrite#asServerActingFor}:
     * the client-origin stamp has to go (the write path refuses a raw statement that carries it) while
     * the principal has to stay (it is what {@code document}'s audit triggers record).
     *
     * <p>The log line is the point as much as the write is. A redirect leaves a {@code mail} row addressed
     * to wherever it went; this says who asked for it, which that row does not.
     */
    private static Future<Boolean> fire(EntityStore store, Document document, Object letterId, Object callerUserId) {
        return ServerWrite.asServerActingFor(callerUserId, () -> {
            UpdateStore updateStore = UpdateStore.createAbove(store);
            Document update = updateStore.updateEntity(document);
            update.setForeignField(TRIGGER_SEND_LETTER, letterId);
            return updateStore.submitChanges()
                .map(ignored -> {
                    Console.log(LOG_PREFIX + "Letter " + letterId + " sent for document "
                                + Entities.getPrimaryKey(document) + " by person " + callerUserId);
                    return true;
                });
        });
    }
}

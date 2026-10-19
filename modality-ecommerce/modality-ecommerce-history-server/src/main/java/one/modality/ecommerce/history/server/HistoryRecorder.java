package one.modality.ecommerce.history.server;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.authn.AuthenticationService;
import dev.webfx.stack.com.serial.SerialCodecManager;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.session.state.SystemUserId;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.domainmodel.formatters.PriceFormatter;
import one.modality.base.shared.entities.*;
import one.modality.base.shared.entities.db.DatabasePayment;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;
import one.modality.ecommerce.document.service.events.AbstractAttendancesEvent;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;
import one.modality.ecommerce.document.service.events.AbstractDocumentLineEvent;
import one.modality.ecommerce.document.service.events.AbstractMoneyTransferEvent;
import one.modality.ecommerce.document.service.events.book.AddRequestEvent;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author Bruno Salmon
 */
public final class HistoryRecorder {

    public static Future<History[]> prepareDocumentHistoriesBeforeSubmit(String comment, Document document, DocumentLine documentLine) {
        return prepareDocumentHistoriesBeforeSubmit(comment, document, documentLine, ThreadLocalStateHolder.getUserId());
    }

    public static Future<History[]> prepareDocumentHistoriesBeforeSubmit(String comment, Document document, DocumentLine documentLine, Object userId) {
        // We must know the document for the history
        if (document == null) { // may happen, but in that case documentLine is not null, and we can read the document from the database
            return documentLine.onExpressionLoaded("document")
                    .compose(v -> {
                        if (documentLine.getDocument() == null)
                            return Future.failedFuture("Document not found in database");
                        return prepareDocumentHistoriesBeforeSubmit(comment, documentLine.getDocument(), documentLine, userId);
                    });
        }

        // Now we are set to insert the History entity
        UpdateStore updateStore = (UpdateStore) document.getStore();
        History history = updateStore.insertEntity(History.class);
        history.setDocument(document);
        history.setComment(comment);

        // The final step in the history creation is to set the user
        return setUserOnHistories(new History[] { history }, userId);
    }

    private static Future<History[]> setUserOnHistories(History[] histories, Object userId) {
        // To record who made the changes, we can 1) set userPerson if available, or 2) set username otherwise
        if (userId instanceof ModalityUserPrincipal modalityUserPrincipal) { // Case 1) Should be most cases
            for (History history : histories)
                history.setUserPerson(modalityUserPrincipal.getUserPersonId());
            return Future.succeededFuture(histories);
        }
        if (userId instanceof SystemUserId) {
            for (History history : histories)
                history.setUsername(((SystemUserId) userId).getName());
            return Future.succeededFuture(histories);
        }
        // A guest signed in by a cart or magic link (2026-10-19). Its principal carries ONLY an email, and fell to the
        // claims arm below, whose username is null for a guest — so both actor columns stayed null and the row named
        // nobody. The email is the guest's whole identity (it is what their grants are looked up by), so it is what is
        // recorded. NOT the constant "Online guest": a constant cannot tell two guests apart, and a later check keyed on
        // it would let every guest read every other guest's payments.
        if (userId instanceof ModalityGuestPrincipal guest) {
            String username = guestUsername(guest.getEmail());
            for (History history : histories)
                history.setUsername(username);
            return Future.succeededFuture(histories);
        }
        // Case 2) User not logged in or just through SSO
        return AuthenticationService.getUserClaims()
                .compose(userClaims -> {
                    for (History history : histories)
                        history.setUsername(userClaims.username());
                    return Future.succeededFuture(histories);
                }, ex -> {
                    for (History history : histories)
                        history.setUsername("Online guest");
                    return Future.succeededFuture(histories);
                });
    }

    /**
     * The prefix that tells a guest's email in history.username from a KBS2 login or the constant "Online guest".
     *
     * <p><b>Anything reading history.username must know it holds several kinds of value</b>: a KBS2 login, a
     * SystemUserId name, "guest:" + an email (a guest signed in by a cart or magic link, since 2026-10-19), and still
     * the CONSTANT "Online guest" — a caller with no session at all, which is every such caller. A predicate keyed
     * on that constant would treat every anonymous payer as the same person. History says who acted, not who owns.
     */
    static final String GUEST_USERNAME_PREFIX = "guest:";

    /** history.username is varchar(64); a longer value would fail the insert and lose the history row altogether. */
    static final int USERNAME_MAX_LENGTH = 64;

    /** What a guest's history rows record: their email, prefixed, cut to the column's length. Pure. */
    static String guestUsername(String email) {
        String username = GUEST_USERNAME_PREFIX + (email == null ? "" : email);
        return username.length() <= USERNAME_MAX_LENGTH ? username : username.substring(0, USERNAME_MAX_LENGTH);
    }

    public static Future<Void> completeDocumentHistoriesAfterSubmit(History[] histories, AbstractDocumentEvent... documentEvents) {
        // Completing the history recording by saving the changes (new primary keys can now be resolved)
        UpdateStore updateStore = (UpdateStore) histories[0].getStore();
        resolveDocumentEventsPrimaryKeys(documentEvents, updateStore);
        String changes = AST.formatArray(SerialCodecManager.encodeJavaArrayToAstArray(documentEvents), "json");
        // Also, if the user wrote a request in these changes, we copy that request in the history
        AddRequestEvent are = (AddRequestEvent) Arrays.findFirst(documentEvents, e -> e instanceof AddRequestEvent);
        for (History history : histories) {
            history = updateStore.updateEntity(history); // Ensuring it's an updated entity
            history.setChanges(changes);
            if (are != null)
                history.setRequest(are.getRequest());
        }
        return updateStore.submitChanges().mapEmpty();
    }

    private static void resolveDocumentEventsPrimaryKeys(AbstractDocumentEvent[] documentEvents, UpdateStore updateStore) {
        for (AbstractDocumentEvent e : documentEvents) {
            resolvePrimaryKeyField(Document.class, e::getDocumentPrimaryKey, e::setDocumentPrimaryKey, updateStore);
            if (e instanceof AbstractDocumentLineEvent documentLineEvent) {
                resolvePrimaryKeyField(DocumentLine.class, documentLineEvent::getDocumentLinePrimaryKey, documentLineEvent::setDocumentLinePrimaryKey, updateStore);
                if (e instanceof AbstractAttendancesEvent aae) {
                    Object[] attendancesPrimaryKeys = aae.getAttendancesPrimaryKeys();
                    for (int i = 0; i < attendancesPrimaryKeys.length; i++) {
                        final int fi = i;
                        resolvePrimaryKeyField(Attendance.class, () -> attendancesPrimaryKeys[fi], pk -> attendancesPrimaryKeys[fi] = pk, updateStore);
                    }
                }
            } else if (e instanceof AbstractMoneyTransferEvent moneyTransferEvent) {
                resolvePrimaryKeyField(MoneyTransfer.class, moneyTransferEvent::getMoneyTransferPrimaryKey, moneyTransferEvent::setMoneyTransferPrimaryKey, updateStore);
            }
        }
    }

    private static void resolvePrimaryKeyField(Class<? extends Entity> entityClass, Supplier<Object> getter, Consumer<Object> setter, UpdateStore updateStore) {
        Object primaryKey = getter.get();
        Entity entity = updateStore.getEntity(entityClass, primaryKey, true);
        if (entity != null) {
            setter.accept(entity.getPrimaryKey());
        }
    }

    public static Future<History[]> preparePaymentHistoriesBeforeSubmit(String comment, DatabasePayment databasePayment) {
        return preparePaymentHistoriesBeforeSubmit(comment, databasePayment, ThreadLocalStateHolder.getUserId());
    }

    public static Future<History[]> preparePaymentHistoriesBeforeSubmit(String comment, DatabasePayment databasePayment, Object userId) {
        MoneyTransfer totalTransfer = databasePayment.totalTransfer();
        MoneyTransfer[] allocatedTransfers = databasePayment.allocatedTransfers();
        UpdateStore updateStore = (UpdateStore) totalTransfer.getStore();
        String interpretedComment = comment.replace("[amount]", PriceFormatter.formatWithoutCurrency(totalTransfer.getAmount()));
        if (Arrays.isEmpty(allocatedTransfers)) {
            // We need to know the document associated with the payment
            Document document = totalTransfer.getDocument();
            // If not found, we may still know its id (this happens when ServerPaymentServiceProvider adds a payment)
            if (document == null) {
                EntityId documentId = totalTransfer.getDocumentId();
                if (documentId != null) { // If we know its id, that's enough, and we just create the document in the store
                    document = updateStore.createEntity(documentId);
                }
            }
            String fieldsToLoad = "amount"; // we also read the amount (necessary for UpdateMoneyTransferEvent serialization)
            // If still not found, we need to load it from the database (this happens when ServerPaymentServiceProvider updates a payment)
            if (document == null) {
                fieldsToLoad += ",document";
            }

            return totalTransfer.onExpressionLoaded(fieldsToLoad)
                .compose(v -> {
                    if (totalTransfer.getDocument() == null)
                        return Future.failedFuture("Payment document not found in database");
                    // Now we are set to insert the History entity
                    History history = updateStore.insertEntity(History.class);
                    history.setMoneyTransfer(totalTransfer);
                    history.setDocument(totalTransfer.getDocument());
                    history.setComment(interpretedComment);

                    // The final step in the history creation is to set the user
                    return setUserOnHistories(new History[] { history }, userId);
                });
        }

        History[] histories = Arrays.map(allocatedTransfers, allocatedTransfer -> {
            History history = updateStore.insertEntity(History.class);
            history.setMoneyTransfer(allocatedTransfer);
            history.setDocument(allocatedTransfer.getDocumentId());
            history.setComment(interpretedComment + " (" + PriceFormatter.formatWithoutCurrency(allocatedTransfer.getAmount()) + " for this booking)");
            return history;
        }, History[]::new);
        // Who paid, on every booking the payment is spread over — exactly as the single-payment branch above records it.
        // This used to return the rows as they were: a split payment's history named nobody, whoever paid (1,405 of the
        // 1,420 unattributed payment rows in production over 90 days, measured 2026-10-19). The read side's "did you make
        // this payment?" check reads these very rows, so it refused the payer of every split payment.
        return setUserOnHistories(histories, userId);
    }

}
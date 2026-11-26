package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.platform.async.Batch;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.History;
import one.modality.base.shared.entities.MoneyTransfer;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Payments entered by the back office on a booking — the server half of {@code DocumentService.recordPayment},
 * {@code transferPayment}, {@code editPayment} and {@code deletePayment}, ported from KBS2's Bookings screen
 * ("Add a payment", "Add a transfer", "Edit…", "Delete…"). Design: docs/design/registration-bookings-payments.md
 * (aggregate repo).
 *
 * <p><b>Why endpoints.</b> No client may write {@code money_transfer} (ClientWriteSecrets.denyTable, 2026-10-19):
 * a client choosing a payment row's columns could mark its own booking paid. So each act is named here, granted by
 * its own operation, and scoped to the booking it touches — the same shape as {@link MoneyTransferVerification}.
 *
 * <p><b>Fences, in order, for every act.</b>
 * <ol>
 *   <li>a verified back-office session of a person of their own ({@link RouteAccessGuard#backOfficeCallerPersonId}),
 *       checked before anything is read, so an anonymous caller cannot make the server look ids up;</li>
 *   <li>the shape and the values — a known method, an amount in range, no card number in the free text — refused,
 *       never defaulted;</li>
 *   <li>the booking is read, and the operation asked for in the organisation and event that OWN it — never one
 *       the call names. A transfer asks for it on both bookings;</li>
 *   <li>the booking's event is still open: a payment on a RECONCILED, FINALISED or ARCHIVED event would move
 *       accounts somebody has already closed;</li>
 *   <li>for an edit or a delete, the payment must belong to the booking named and be one staff entered by hand:
 *       {@link #notChangeableReason}.</li>
 * </ol>
 * A missing booking, a payment of another booking and a caller without the grant all get the same
 * {@link RouteAccessGuard#refused()}, so the endpoints are no oracle for other centres' ids.
 *
 * <p><b>The server chooses what the browser cannot.</b> The date is the database's {@code now()}; the money
 * accounts are left to {@code autoset_accounts}, which picks them from the method and the event, as for KBS2's
 * entries; {@code read} is true (staff entered it, so it is not news for the booking — 2,972 of 2,980 manual
 * payments on staging, 2026-11-26). Pending and successful keep their defaults (settled): the dialog does not
 * offer them, and staff set them on 133 of 9,000 manual payments in two years.
 *
 * <p><b>One transaction, written as the caller.</b> The payment, the booking's arrival when asked, and one
 * {@code history} row per booking go in one {@code submitChanges}, run as the server acting for the caller —
 * the client origin dropped, the principal kept, so the audit triggers record who did it.
 *
 * <p><b>A refund is a negative payment</b>, as staff record them today (1,883 negative Online payments and 101
 * negative manual ones on staging in two years; the {@code refund} flag is never set). Nothing here moves money:
 * it records money that moved elsewhere.
 *
 * @author Claude Code
 */
final class BookingPayments {

    /** Recording a payment or a refund, transferring between bookings, correcting a hand-entered payment (V0184). */
    static final String RECORD_PAYMENTS = "RecordPayments";

    /** Changing a booking (V0138) — asked as well when a payment also marks the booking arrived. */
    static final String SUBMIT_BOOKINGS = "SubmitBookings";

    /** Deleting a hand-entered payment or a transfer (V0184) — granted to nobody by default. */
    static final String DELETE_PAYMENTS = "DeletePayments";

    static final String MALFORMED = "[PaymentError] Malformed request";
    static final String NOT_SAVED = "[PaymentError] The payment could not be saved";
    static final String EVENT_CLOSED = "[PaymentEventClosed] The event's accounts are closed";
    static final String NOT_CHANGEABLE = "[PaymentNotChangeable] This payment cannot be changed here";
    static final String CARD_NUMBER = "[PaymentCardNumber] A card number must never be typed in a payment";
    static final String ACCOUNT_CLOSED = "[PaymentAccountClosed] The money account for this payment is closed";
    static final String NO_ACCOUNT = "[PaymentNoAccount] The organisation has no open money account for this method";

    /** Cash, Cheque, Credit card, Bank transfer, Online (for a refund made at the gateway). Not Contra, not Gift card. */
    static final Set<Integer> RECORDABLE_METHODS = Set.of(1, 2, 3, 4, 5);

    static final int ONLINE_METHOD = 5;
    static final int CONTRA_METHOD = 6;

    /** 100,000.00 in minor units — far above any booking, low enough to catch a slip of the keyboard. */
    static final int MAX_AMOUNT = 10_000_000;

    /** The column widths: {@code transaction_ref varchar(255)}, {@code comment varchar(1024)}. */
    static final int MAX_REF = 255, MAX_COMMENT = 1024;

    /** The event states whose accounts are closed (V0148). */
    static final Set<String> CLOSED_EVENT_STATES = Set.of("RECONCILED", "FINALISED", "ARCHIVED");

    /** What the closed-account trigger raises (P0001): "'<name>' money account is closed". */
    static final String CLOSED_ACCOUNT_MESSAGE = "money account is closed";

    /**
     * What {@code autoset_money_transfer_from_account} / {@code _to_account} raise (P0001) when the organisation has
     * no open account of the right type supporting the method — found on the first live run, 2026-11-26. A centre whose
     * accounts are not set up for, say, cheques gets this, and the desk should be told so rather than "not saved".
     */
    static final String NO_ACCOUNT_MESSAGE = "No open money account found";

    private static final String LOG_PREFIX = "[BookingPayments] ";

    private BookingPayments() {
    }

    // ── The statements ─────────────────────────────────────────────────────────────────────────────────────────
    //
    // Constants only; every value is a bound parameter.

    /** A booking and who owns it: id, ref, event, organisation, event state. */
    static final String BOOKING_SQL = "select d.id, d.ref, e.id, e.organization_id, e.state"
        + " from document d join event e on e.id = d.event_id where d.id = $1";

    /**
     * A payment and what decides whether staff may change it: id, booking, parent, spread, method, amount,
     * verified, receipts batch, in a batch or on a statement, touched by a gateway, method name, settled
     * (not pending and successful), exported to accounting.
     */
    static final String PAYMENT_SQL = "select mt.id, mt.document_id, mt.parent_id, mt.spread, mt.method_id, mt.amount,"
        + " mt.verified, mt.receipts_transfer, (mt.statement_id is not null or mt.transfer_id is not null),"
        + " (mt.gateway_response is not null or mt.sessionid is not null or mt.gateway_accesscode is not null"
        + " or mt.gateway_company_id is not null or mt.gateway_generate_payment_url_response is not null),"
        + " m.name, (not mt.pending and mt.successful), mt.accounting_export_date is not null"
        + " from money_transfer mt join method m on m.id = mt.method_id where mt.id = $1";

    /** A transfer's two rows: id, booking, amount, verified, in a batch or on a statement. */
    static final String TRANSFER_CHILDREN_SQL = "select ch.id, ch.document_id, ch.amount, ch.verified,"
        + " (ch.statement_id is not null or ch.transfer_id is not null)"
        + " from money_transfer ch where ch.parent_id = $1 order by ch.id";

    /** A method's name, for the history line. */
    static final String METHOD_NAME_SQL = "select name from method where id = $1";

    // ── The guarded writes (edit and delete) ───────────────────────────────────────────────────────────────────
    //
    // The eligibility read above decides what to tell the caller; these decide what is WRITTEN. Each repeats every
    // rule of notChangeableReason, the booking's ownership and the open event, plus the method and amount as read,
    // so a payment ticked verified, batched, exported, moved or changed by somebody else between the read and the
    // write is left alone (security review 2026-11-26, finding 1). The write and its history row are one statement;
    // when no row qualifies, the guard divides by zero (SQLSTATE 22012), the transaction aborts, nothing is written,
    // and the caller is told NOT_CHANGEABLE. The guard sits in a one-row derived table LEFT JOINed to the change, so
    // it is evaluated even when the change returned nothing (an inner join could be planned to skip it), and the
    // WHERE reads it: a guard column nothing reads is pruned by the planner and never divides — which is exactly what
    // the first rehearsal found (the write was skipped, a history row went in anyway, and no error was raised).

    /** Every rule a hand-entered payment {@code mt} must still meet when it is written; {@code $1} id, {@code $2} booking. */
    static final String STILL_CHANGEABLE = "mt.id = $1 and mt.document_id = $2 and mt.parent_id is null and not mt.spread"
        + " and not mt.verified and not mt.receipts_transfer and mt.statement_id is null and mt.transfer_id is null"
        + " and mt.accounting_export_date is null and not mt.pending and mt.successful"
        + " and mt.gateway_response is null and mt.sessionid is null and mt.gateway_accesscode is null"
        + " and mt.gateway_company_id is null and mt.gateway_generate_payment_url_response is null"
        + " and mt.method_id in (1, 2, 3, 4, 5) and (mt.method_id <> 5 or mt.amount < 0)"
        + " and not exists(select 1 from document d join event e on e.id = d.event_id where d.id = mt.document_id"
        + " and e.state in ('RECONCILED', 'FINALISED', 'ARCHIVED'))";

    /** Fails with SQLSTATE 22012 unless the CTE {@code changed} returned exactly what it should. */
    static final String GUARD = "(select 1 / (select count(*)::int from changed) as guard) g left join changed c on true"
        + " where g.guard = 1";

    /**
     * {@code $1} id, {@code $2} booking, {@code $3} method, {@code $4} amount, {@code $5} contactless, {@code $6} ref,
     * {@code $7} comment, {@code $8} method as read, {@code $9} amount as read, {@code $10} who, {@code $11} history text.
     */
    static final String EDIT_SQL = "with changed as (update money_transfer mt"
        + " set method_id = $3, amount = $4, wireless = $5, transaction_ref = $6, comment = $7"
        + " where " + STILL_CHANGEABLE + " and mt.method_id = $8 and mt.amount = $9 returning mt.id)"
        + " insert into history (document_id, money_transfer_id, user_person_id, comment)"
        + " select $2, c.id, $10, $11 from " + GUARD;

    /** {@code $1} id, {@code $2} booking, {@code $3} method as read, {@code $4} amount as read, {@code $5} who, {@code $6} text. */
    static final String DELETE_SQL = "with changed as (delete from money_transfer mt"
        + " where " + STILL_CHANGEABLE + " and mt.method_id = $3 and mt.amount = $4 returning mt.id)"
        // No money_transfer link: the row is gone by the end of the statement, and the foreign key is checked then.
        + " insert into history (document_id, money_transfer_id, user_person_id, comment)"
        + " select $2, null, $5, $6 from " + GUARD;

    /**
     * Deletes a transfer by its parent ({@code $1}) — the cascade takes both rows — only while it still has exactly
     * its two rows, on bookings {@code $2} and {@code $3}, with the amount as read ({@code $4}), neither verified,
     * batched, on a statement or exported, and both events open. {@code $5} who, {@code $6}/{@code $7} the history
     * text for {@code $2}/{@code $3}.
     */
    // The driver sends parameters untyped: an operator on a bare parameter ("-$4") cannot be resolved, so it is cast.
    static final String DELETE_TRANSFER_SQL = "with changed as (delete from money_transfer p"
        + " where p.id = $1 and p.method_id = 6 and p.spread and p.document_id is null"
        + " and (select count(*) from money_transfer ch where ch.parent_id = p.id) = 2"
        + " and exists(select 1 from money_transfer ch where ch.parent_id = p.id and ch.document_id = $2 and ch.amount = -($4::int))"
        + " and exists(select 1 from money_transfer ch where ch.parent_id = p.id and ch.document_id = $3 and ch.amount = $4::int)"
        + " and not exists(select 1 from money_transfer ch where ch.parent_id = p.id and (ch.verified"
        + " or ch.statement_id is not null or ch.transfer_id is not null or ch.accounting_export_date is not null))"
        + " and not exists(select 1 from money_transfer ch join document d on d.id = ch.document_id"
        + " join event e on e.id = d.event_id where ch.parent_id = p.id and e.state in ('RECONCILED', 'FINALISED', 'ARCHIVED'))"
        + " returning p.id)"
        + " insert into history (document_id, money_transfer_id, user_person_id, comment)"
        + " select v.document_id, null, $5, v.comment"
        + " from (select 1 / (select count(*)::int from changed) as guard) g"
        + " cross join (values ($2::int, $6::varchar), ($3::int, $7::varchar)) v(document_id, comment)"
        + " where g.guard = 1";

    // ── The rows read ──────────────────────────────────────────────────────────────────────────────────────────

    /** A booking as {@link #BOOKING_SQL} reads it. */
    record Booking(Object id, Object ref, Object eventId, Object organizationId, String eventState) {
        boolean eventClosed() {
            return eventState != null && CLOSED_EVENT_STATES.contains(eventState);
        }

        /** "#ref", as KBS2's history named a booking. */
        String label() {
            return "#" + ref;
        }
    }

    /** A payment as {@link #PAYMENT_SQL} reads it. */
    record Payment(int id, Integer documentId, Integer parentId, boolean spread, int methodId, int amount,
                   boolean verified, boolean receiptsTransfer, boolean batchedOrOnStatement, boolean fromGateway,
                   String methodName, boolean settled, boolean exported) {
        boolean isTransferRow() {
            return methodId == CONTRA_METHOD;
        }
    }

    // ── Record ─────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Records a payment — or, with a negative amount, a refund — on a booking.
     *
     * @return the new payment's id
     */
    static Future<Object> record(Object callerState, Object documentId, Object methodId, Object amount, Object wireless,
                                 Object transactionRef, Object comment, Object markArrived) {
        Object callerPersonId = RouteAccessGuard.backOfficeCallerPersonId(callerState);
        if (!VerifiedBackoffice.of(callerState) || callerPersonId == null)
            return RouteAccessGuard.refused();
        Integer document = positiveInt(documentId);
        Integer method = positiveInt(methodId);
        Integer value = signedAmount(amount);
        if (document == null || method == null || !RECORDABLE_METHODS.contains(method) || value == null
            || !onlineOnlyAsRefund(method, value)
            || !(wireless instanceof Boolean contactless) || !(markArrived instanceof Boolean arrived)
            || !isOptionalText(transactionRef, MAX_REF) || !isOptionalText(comment, MAX_COMMENT))
            return Future.failedFuture(MALFORMED);
        String ref = cleanText(transactionRef), note = cleanText(comment);
        if (containsCardNumber(ref) || containsCardNumber(note))
            return Future.failedFuture(CARD_NUMBER);
        Object callerUserId = StateAccessor.getUserId(callerState);
        return readBooking(document).compose(booking -> booking == null ? RouteAccessGuard.refused()
            : RouteAccessGuard.whenCallerHoldsOperation(callerState, RECORD_PAYMENTS, booking.organizationId(), booking.eventId(),
                // Marking arrived changes the booking, not the money: it asks the booking right too, so RecordPayments
                // granted on its own (V0184 grants it only with SubmitBookings, an administrator might not) cannot.
                () -> !arrived ? Future.succeededFuture(true)
                    : RouteAccessGuard.whenCallerHoldsOperation(callerState, SUBMIT_BOOKINGS, booking.organizationId(), booking.eventId(),
                        () -> Future.succeededFuture(true))).compose(allowed -> booking.eventClosed() ? Future.failedFuture(EVENT_CLOSED)
                    : readMethodName(method).compose(methodName -> {
                        UpdateStore store = newUpdateStore();
                        MoneyTransfer payment = store.insertEntity(MoneyTransfer.class);
                        payment.setDocument(booking.id());
                        payment.setMethod(method);
                        payment.setAmount(value);
                        payment.setTransactionRef(ref);
                        payment.setComment(note);
                        payment.setFieldValue("wireless", contactless && method == 3);
                        payment.setFieldValue("read", true);
                        if (arrived) {
                            Document arrival = store.updateEntity(Document.class, booking.id());
                            arrival.setArrived(true);
                        }
                        addHistory(store, booking.id(), payment, callerPersonId,
                            "Added " + methodName + " payment amount = " + formatAmount(value)
                            + (arrived ? ", marked as arrived" : ""));
                        return submit(store, callerUserId, "Record", callerPersonId)
                            .map(ignored -> {
                                Console.log(LOG_PREFIX + "Payment " + payment.getPrimaryKey() + " recorded on document "
                                            + booking.id() + " by person " + callerPersonId);
                                return payment.getPrimaryKey();
                            });
                    })));
    }

    // ── Transfer ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Moves {@code amount} paid on one booking to another, as KBS2's "Add a transfer": a Contra parent (no booking,
     * {@code spread}) with two children, +amount on the credited booking and −amount on the debited one. The
     * database copies the parent's method and flags onto the children ({@code autoset_children}) and keeps the
     * parent's amount at their sum, zero.
     *
     * <p>Both bookings must be of the same organisation (KBS2's rule) and the caller must hold the right on both;
     * they may be of different events (301 of 749 transfers on staging in two years were).
     *
     * @return the transfer's (parent's) id
     */
    static Future<Object> transfer(Object callerState, Object fromDocumentId, Object toDocumentId, Object amount, Object comment) {
        Object callerPersonId = RouteAccessGuard.backOfficeCallerPersonId(callerState);
        if (!VerifiedBackoffice.of(callerState) || callerPersonId == null)
            return RouteAccessGuard.refused();
        Integer from = positiveInt(fromDocumentId), to = positiveInt(toDocumentId);
        Integer value = signedAmount(amount);
        if (from == null || to == null || from.equals(to) || value == null || value < 0 || !isOptionalText(comment, MAX_COMMENT))
            return Future.failedFuture(MALFORMED);
        String note = cleanText(comment);
        if (containsCardNumber(note))
            return Future.failedFuture(CARD_NUMBER);
        Object callerUserId = StateAccessor.getUserId(callerState);
        return readBooking(from).compose(debited -> readBooking(to).compose(credited -> {
            if (debited == null || credited == null || !sameKey(debited.organizationId(), credited.organizationId()))
                return RouteAccessGuard.refused();
            return RouteAccessGuard.whenCallerHoldsOperation(callerState, RECORD_PAYMENTS, debited.organizationId(), debited.eventId(),
                () -> RouteAccessGuard.whenCallerHoldsOperation(callerState, RECORD_PAYMENTS, credited.organizationId(), credited.eventId(),
                    () -> {
                        if (debited.eventClosed() || credited.eventClosed())
                            return Future.failedFuture(EVENT_CLOSED);
                        UpdateStore store = newUpdateStore();
                        MoneyTransfer parent = store.insertEntity(MoneyTransfer.class);
                        parent.setMethod(CONTRA_METHOD);
                        parent.setSpread(true);
                        parent.setAmount(0);
                        parent.setComment(note);
                        parent.setFieldValue("read", true);
                        MoneyTransfer credit = transferChild(store, parent, credited.id(), value, note);
                        MoneyTransfer debit = transferChild(store, parent, debited.id(), -value, note);
                        String amountText = "Transferred amount = " + formatAmount(value);
                        addHistory(store, credited.id(), credit, callerPersonId,
                            amountText + " from " + labelFrom(debited, credited));
                        addHistory(store, debited.id(), debit, callerPersonId,
                            amountText + " to " + labelFrom(credited, debited));
                        return submit(store, callerUserId, "Transfer", callerPersonId)
                            .map(ignored -> {
                                Console.log(LOG_PREFIX + "Transfer " + parent.getPrimaryKey() + " from document " + debited.id()
                                            + " to document " + credited.id() + " by person " + callerPersonId);
                                return parent.getPrimaryKey();
                            });
                    }));
        }));
    }

    private static MoneyTransfer transferChild(UpdateStore store, MoneyTransfer parent, Object documentId, int amount, String note) {
        MoneyTransfer child = store.insertEntity(MoneyTransfer.class);
        child.setParent(parent);
        child.setDocument(documentId);
        child.setAmount(amount);
        // method_id is NOT NULL, so it is set here as well; autoset_children (BEFORE INSERT) copies the parent's anyway.
        child.setMethod(CONTRA_METHOD);
        child.setComment(note);
        child.setFieldValue("read", true);
        return child;
    }

    /** The other booking, with its event when it is not this one's: the two may be of different events. */
    static String labelFrom(Booking other, Booking self) {
        return other.label() + (sameKey(other.eventId(), self.eventId()) ? "" : " (event " + other.eventId() + ")");
    }

    // ── Edit ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Corrects a payment staff entered by hand: its method, amount, contactless flag, reference and comment. The
     * date, the accounts and the settled flags are not changeable here — the date moves account balances and
     * statements, and the accounts follow the method ({@code autoset_accounts} re-picks them when it changes).
     *
     * @return true
     */
    static Future<Object> edit(Object callerState, Object documentId, Object moneyTransferId, Object methodId, Object amount,
                               Object wireless, Object transactionRef, Object comment) {
        Object callerPersonId = RouteAccessGuard.backOfficeCallerPersonId(callerState);
        if (!VerifiedBackoffice.of(callerState) || callerPersonId == null)
            return RouteAccessGuard.refused();
        Integer document = positiveInt(documentId), paymentId = positiveInt(moneyTransferId), method = positiveInt(methodId);
        Integer value = signedAmount(amount);
        if (document == null || paymentId == null || method == null || !RECORDABLE_METHODS.contains(method) || value == null
            || !onlineOnlyAsRefund(method, value)
            || !(wireless instanceof Boolean contactless)
            || !isOptionalText(transactionRef, MAX_REF) || !isOptionalText(comment, MAX_COMMENT))
            return Future.failedFuture(MALFORMED);
        String ref = cleanText(transactionRef), note = cleanText(comment);
        if (containsCardNumber(ref) || containsCardNumber(note))
            return Future.failedFuture(CARD_NUMBER);
        Object callerUserId = StateAccessor.getUserId(callerState);
        return forPaymentOf(callerState, RECORD_PAYMENTS, document, paymentId, (booking, payment) -> {
            if (payment.isTransferRow() || notChangeableReason(payment) != null)
                return Future.failedFuture(NOT_CHANGEABLE);
            return readMethodName(method).compose(methodName -> {
                String text = "Edited " + methodName + " payment amount = " + formatAmount(value)
                              + " (was " + payment.methodName() + " " + formatAmount(payment.amount()) + ")";
                return guardedSubmit(callerUserId, "Edit", callerPersonId, EDIT_SQL, payment.id(), booking.id(), method, value,
                    contactless && method == 3, ref, note, payment.methodId(), payment.amount(), callerPersonId, text).map(ignored -> {
                    Console.log(LOG_PREFIX + "Payment " + payment.id() + " of document " + booking.id() + " edited by person " + callerPersonId);
                    return (Object) true;
                });
            });
        });
    }

    // ── Delete ─────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Deletes a payment staff entered by hand, or a whole transfer when the payment named is one of its two rows:
     * the parent is deleted and {@code money_transfer_parent_id_fkey} (ON DELETE CASCADE) takes both rows with it,
     * so a transfer is never left one-sided. A transfer needs the right on both bookings, and both events open.
     *
     * @param comment why, for the booking's history (optional)
     * @return true
     */
    static Future<Object> delete(Object callerState, Object documentId, Object moneyTransferId, Object comment) {
        Object callerPersonId = RouteAccessGuard.backOfficeCallerPersonId(callerState);
        if (!VerifiedBackoffice.of(callerState) || callerPersonId == null)
            return RouteAccessGuard.refused();
        Integer document = positiveInt(documentId), paymentId = positiveInt(moneyTransferId);
        if (document == null || paymentId == null || !isOptionalText(comment, MAX_COMMENT))
            return Future.failedFuture(MALFORMED);
        String note = cleanText(comment);
        if (containsCardNumber(note))
            return Future.failedFuture(CARD_NUMBER);
        String reason = note == null ? "" : " — " + note;
        Object callerUserId = StateAccessor.getUserId(callerState);
        return forPaymentOf(callerState, DELETE_PAYMENTS, document, paymentId, (booking, payment) -> {
            if (payment.isTransferRow())
                return deleteTransfer(callerState, callerUserId, callerPersonId, booking, payment, reason);
            if (notChangeableReason(payment) != null)
                return Future.failedFuture(NOT_CHANGEABLE);
            String text = "Deleted " + payment.methodName() + " payment amount = " + formatAmount(payment.amount()) + reason;
            return guardedSubmit(callerUserId, "Delete", callerPersonId, DELETE_SQL, payment.id(), booking.id(),
                payment.methodId(), payment.amount(), callerPersonId, text).map(ignored -> {
                Console.log(LOG_PREFIX + "Payment " + payment.id() + " of document " + booking.id() + " deleted by person " + callerPersonId);
                return (Object) true;
            });
        });
    }

    /** Deletes the transfer {@code row} is one side of — both sides, once the caller is shown to hold the right on both. */
    private static Future<Object> deleteTransfer(Object callerState, Object callerUserId, Object callerPersonId,
                                                 Booking booking, Payment row, String reason) {
        if (row.parentId() == null)
            return Future.failedFuture(NOT_CHANGEABLE); // a transfer row with no parent is not one this code made
        int parentId = row.parentId();
        return asServer(() -> query(TRANSFER_CHILDREN_SQL, parentId)).compose(children -> {
            if (children.getRowCount() != 2)
                return Future.failedFuture(NOT_CHANGEABLE); // the 7 odd transfers on staging stay as they are
            Object otherDocumentId = null;
            int amount = 0;
            for (int i = 0; i < 2; i++) {
                Object verified = children.getValue(i, 3), batched = children.getValue(i, 4);
                if (Boolean.TRUE.equals(verified) || Boolean.TRUE.equals(batched))
                    return Future.failedFuture(NOT_CHANGEABLE);
                Object childDocument = children.getValue(i, 1);
                if (!sameKey(childDocument, booking.id()))
                    otherDocumentId = childDocument;
                Object childAmount = children.getValue(i, 2);
                if (childAmount instanceof Number n && n.intValue() > 0)
                    amount = n.intValue();
            }
            if (otherDocumentId == null)
                return Future.failedFuture(NOT_CHANGEABLE);
            int transferred = amount;
            return readBooking(otherDocumentId).compose(other -> other == null ? RouteAccessGuard.refused()
                : RouteAccessGuard.whenCallerHoldsOperation(callerState, DELETE_PAYMENTS, other.organizationId(), other.eventId(), () -> {
                    if (other.eventClosed())
                        return Future.failedFuture(EVENT_CLOSED);
                    String text = "Deleted transfer amount = " + formatAmount(transferred);
                    // The booking named is the debited or the credited side: the statement wants them in that order.
                    boolean namedIsDebited = sameKey(booking.id(), debitedId(children));
                    Booking debited = namedIsDebited ? booking : other, credited = namedIsDebited ? other : booking;
                    return guardedSubmit(callerUserId, "Delete transfer", callerPersonId, DELETE_TRANSFER_SQL, parentId,
                        debited.id(), credited.id(), transferred, callerPersonId,
                        text + " with " + labelFrom(credited, debited) + reason,
                        text + " with " + labelFrom(debited, credited) + reason).map(ignored -> {
                        Console.log(LOG_PREFIX + "Transfer " + parentId + " between documents " + booking.id() + " and "
                                    + other.id() + " deleted by person " + callerPersonId);
                        return (Object) true;
                    });
                }));
        });
    }

    // ── The fence for an existing payment ──────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface ForPayment {
        Future<Object> apply(Booking booking, Payment payment);
    }

    /**
     * Reads the booking and the payment, refuses unless the payment is the booking's own — a payment id alone is
     * never enough — and the caller holds {@code operation} where the booking is owned, then refuses a closed
     * event, and only then hands both to {@code action}.
     */
    private static Future<Object> forPaymentOf(Object callerState, String operation, Integer documentId, Integer paymentId,
                                               ForPayment action) {
        return readBooking(documentId).compose(booking -> booking == null ? RouteAccessGuard.refused()
            : readPayment(paymentId).compose(payment -> {
                if (payment == null || payment.documentId() == null || !sameKey(payment.documentId(), booking.id())) {
                    Console.log(LOG_PREFIX + "Refused: payment " + paymentId + " is not of document " + documentId);
                    return RouteAccessGuard.refused();
                }
                return RouteAccessGuard.whenCallerHoldsOperation(callerState, operation, booking.organizationId(), booking.eventId(),
                    () -> booking.eventClosed() ? Future.failedFuture(EVENT_CLOSED) : action.apply(booking, payment));
            }));
    }

    /**
     * Why staff may not change this payment here, or null when they may. Pure.
     *
     * <p>Only payments staff entered by hand: not one a gateway touched (any of its columns set — changing its
     * amount would contradict the provider's record), not a positive Online payment (staff record Online only as a
     * refund, so a positive one is the gateway's even when its payload has been cleared — as staging's anonymiser
     * does), not one still pending or failed (an online attempt, 15,791 of them on staging in two years), not a
     * split payment or its child, not a receipts batch, not one already in a batch or on a statement, not one
     * exported to accounting, and not one verified against a statement (Finance's tick, which the accounts rely
     * on). A transfer row is judged by {@link #deleteTransfer} instead.
     */
    static String notChangeableReason(Payment payment) {
        if (payment.fromGateway() || (payment.methodId() == ONLINE_METHOD && payment.amount() >= 0))
            return "gateway";
        if (!payment.settled())
            return "unsettled";
        if (payment.exported())
            return "exported";
        if (payment.spread() || (payment.parentId() != null && !payment.isTransferRow()))
            return "split";
        if (payment.receiptsTransfer() || payment.batchedOrOnStatement())
            return "batched";
        if (payment.verified())
            return "verified";
        if (!payment.isTransferRow() && !RECORDABLE_METHODS.contains(payment.methodId()))
            return "method";
        return null;
    }

    // ── Reads and writes ───────────────────────────────────────────────────────────────────────────────────────

    private static Future<Booking> readBooking(Object documentId) {
        return asServer(() -> query(BOOKING_SQL, documentId)).map(rows -> {
            if (rows.getRowCount() == 0)
                return null;
            Object id = rows.getValue(0, 0), ref = rows.getValue(0, 1), event = rows.getValue(0, 2),
                organization = rows.getValue(0, 3), state = rows.getValue(0, 4);
            return new Booking(id, ref, event, organization, state == null ? null : state.toString());
        });
    }

    private static Future<Payment> readPayment(Object paymentId) {
        return asServer(() -> query(PAYMENT_SQL, paymentId)).map(rows -> {
            if (rows.getRowCount() == 0)
                return null;
            // Through Object locals: getValue is generic (see MoneyTransferVerification.classify).
            Object id = rows.getValue(0, 0), document = rows.getValue(0, 1), parent = rows.getValue(0, 2),
                spread = rows.getValue(0, 3), method = rows.getValue(0, 4), amount = rows.getValue(0, 5),
                verified = rows.getValue(0, 6), receipts = rows.getValue(0, 7), batched = rows.getValue(0, 8),
                gateway = rows.getValue(0, 9), methodName = rows.getValue(0, 10), settled = rows.getValue(0, 11),
                exported = rows.getValue(0, 12);
            return new Payment(((Number) id).intValue(), document == null ? null : ((Number) document).intValue(),
                parent == null ? null : ((Number) parent).intValue(), Boolean.TRUE.equals(spread),
                ((Number) method).intValue(), amount == null ? 0 : ((Number) amount).intValue(),
                Boolean.TRUE.equals(verified), Boolean.TRUE.equals(receipts), Boolean.TRUE.equals(batched),
                Boolean.TRUE.equals(gateway), methodName == null ? "" : methodName.toString(),
                Boolean.TRUE.equals(settled), Boolean.TRUE.equals(exported));
        });
    }

    private static Future<String> readMethodName(int methodId) {
        return asServer(() -> query(METHOD_NAME_SQL, methodId)).map(rows -> {
            Object name = rows.getRowCount() == 0 ? null : rows.getValue(0, 0);
            return name == null ? "" : name.toString();
        });
    }

    private static Future<QueryResult> query(String sql, Object parameter) {
        return QueryService.executeQuery(new QueryArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameter)
            .build());
    }

    private static UpdateStore newUpdateStore() {
        return UpdateStore.create(DataSourceModelService.getDefaultDataSourceModel());
    }

    /** The booking of a transfer's negative row, as {@link #TRANSFER_CHILDREN_SQL} read them. */
    private static Object debitedId(QueryResult children) {
        for (int i = 0; i < children.getRowCount(); i++) {
            Object amount = children.getValue(i, 2);
            if (amount instanceof Number n && n.intValue() < 0)
                return children.getValue(i, 1);
        }
        return null;
    }

    /**
     * Runs one guarded statement as the server acting for the caller. A batch of one, not executeSubmit: only the batch
     * path stamps {@code kbs.audit_person_id} (see MoneyTransferVerification). A guard that found nothing to write
     * answers {@link #NOT_CHANGEABLE}.
     */
    private static Future<Object> guardedSubmit(Object callerUserId, String act, Object callerPersonId, String sql, Object... parameters) {
        SubmitArgument statement = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters)
            .build();
        return asServerActingFor(callerUserId, () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { statement })))
            .map(ignored -> (Object) true)
            .recover(failure -> failed(failure, act, callerPersonId));
    }

    /** One history row: the booking, the payment when it stays, who, and KBS2's wording. */
    private static void addHistory(UpdateStore store, Object documentId, MoneyTransfer payment, Object callerPersonId, String comment) {
        History history = store.insertEntity(History.class);
        history.setDocument(documentId);
        if (payment != null)
            history.setMoneyTransfer(payment);
        history.setUserPerson(callerPersonId);
        history.setComment(comment);
    }

    /** Submits as the server acting for the caller, and turns a database refusal into a key the client can translate. */
    private static Future<Object> submit(UpdateStore store, Object callerUserId, String act, Object callerPersonId) {
        return asServerActingFor(callerUserId, store::submitChanges)
            .map(ignored -> (Object) true)
            .recover(failure -> failed(failure, act, callerPersonId));
    }

    private static Future<Object> failed(Throwable failure, String act, Object callerPersonId) {
        String message = failure == null ? null : failure.getMessage();
        String answer = failureMessage(message);
        // Never the database's own text: a constraint's "Failing row contains (...)" would copy the comment and the
        // reference — free text — into the logs. The SQLSTATE and what the caller was told are enough.
        Console.log(LOG_PREFIX + act + " failed for person " + callerPersonId + ": SQLSTATE " + sqlState(message)
                    + ", answered " + answer);
        return Future.failedFuture(answer);
    }

    /** The five-character SQLSTATE the Postgres client appends, e.g. "(P0001)", or "?" when there is none. Pure. */
    static String sqlState(String databaseMessage) {
        if (databaseMessage == null)
            return "?";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([0-9A-Z]{5})\\)").matcher(databaseMessage);
        String state = "?";
        while (m.find())
            state = m.group(1);
        return state;
    }

    /** What the caller is told about a failed write. Pure. */
    static String failureMessage(String databaseMessage) {
        if (databaseMessage == null)
            return NOT_SAVED;
        if ("22012".equals(sqlState(databaseMessage)) || databaseMessage.contains("division by zero"))
            return NOT_CHANGEABLE; // a guarded write found its payment no longer as read (see STILL_CHANGEABLE)
        if (databaseMessage.contains(CLOSED_ACCOUNT_MESSAGE))
            return ACCOUNT_CLOSED;
        return databaseMessage.contains(NO_ACCOUNT_MESSAGE) ? NO_ACCOUNT : NOT_SAVED;
    }

    // ── The values ─────────────────────────────────────────────────────────────────────────────────────────────

    /** A non-zero whole amount of minor units within ±{@link #MAX_AMOUNT}, or null. Pure. */
    static Integer signedAmount(Object value) {
        if (!(value instanceof Number number))
            return null;
        double d = number.doubleValue();
        if (d == 0 || Math.abs(d) > MAX_AMOUNT || d != Math.floor(d))
            return null; // NaN fails the last test, the infinities the range
        return number.intValue();
    }

    /**
     * Online is recorded by hand only as a refund made at the gateway (negative): a positive "Online" payment typed
     * by staff would mark a booking paid and be attributed to the gateway's account, which never received it. Pure.
     */
    static boolean onlineOnlyAsRefund(int methodId, int amount) {
        return methodId != ONLINE_METHOD || amount < 0;
    }

    /** A positive {@code int} from any integral number; null for anything else. Pure. */
    static Integer positiveInt(Object value) {
        return MoneyTransferVerification.positiveInt(value);
    }

    /** Null, or a string of at most {@code max} characters. Pure. */
    static boolean isOptionalText(Object value, int max) {
        return value == null || value instanceof String s && s.length() <= max;
    }

    /** Trimmed, and null when empty. Pure. */
    static String cleanText(Object value) {
        if (!(value instanceof String s))
            return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Whether {@code text} holds what looks like a payment card number: 13 to 19 digits — written together or in
     * groups split by a single separator ({@link #CARD_SEPARATORS}) — that pass the Luhn check. A guard against a
     * slip at the desk, not against somebody determined to store one: "4111 x 1111…" still gets through. Desk staff have a card terminal in front
     * of them; a number typed here would be stored in a column the anonymiser and the exports carry. Pure.
     *
     * <p>A long numeric bank reference passes the Luhn check one time in ten and is refused too; the message says
     * why, and the reference can be typed with a letter or a slash.
     */
    static boolean containsCardNumber(String text) {
        if (text == null)
            return false;
        int length = text.length();
        int i = 0;
        while (i < length) {
            if (!Character.isDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int groupEnd = digitsEnd(text, i);
            StringBuilder digits = new StringBuilder(text.substring(i, groupEnd));
            // Join further groups only as cards are written: one separator, the same one each time, and groups of 3 to 6
            // digits (4-4-4-4, 4-6-5). So "26/11/2026 120.00" stays a date and an amount.
            char separator = 0;
            int j = groupEnd;
            while (isCardGroup(groupEnd - i) && j + 1 < length && CARD_SEPARATORS.indexOf(text.charAt(j)) >= 0
                   && (separator == 0 || text.charAt(j) == separator) && Character.isDigit(text.charAt(j + 1))) {
                int nextEnd = digitsEnd(text, j + 1);
                if (!isCardGroup(nextEnd - j - 1))
                    break;
                separator = text.charAt(j);
                digits.append(text, j + 1, nextEnd);
                i = j + 1;
                groupEnd = nextEnd;
                j = nextEnd;
            }
            if (hasCardNumber(digits))
                return true;
            i = groupEnd;
        }
        return false;
    }

    private static int digitsEnd(String text, int from) {
        int k = from;
        while (k < text.length() && Character.isDigit(text.charAt(k)))
            k++;
        return k;
    }

    private static boolean isCardGroup(int size) {
        return size >= 3 && size <= 6;
    }

    /** What may stand between two digit groups of a card number as people type one. */
    static final String CARD_SEPARATORS = " -./_";

    /** Whether any 13-to-19-digit window of {@code digits} passes the Luhn check. */
    private static boolean hasCardNumber(CharSequence digits) {
        int n = digits.length();
        for (int size = 13; size <= Math.min(19, n); size++)
            for (int start = 0; start + size <= n; start++)
                if (luhn(digits, start, size))
                    return true;
        return false;
    }

    static boolean luhn(CharSequence digits, int start, int size) {
        int sum = 0;
        for (int k = 0; k < size; k++) {
            int d = digits.charAt(start + size - 1 - k) - '0';
            if (k % 2 == 1) {
                d *= 2;
                if (d > 9)
                    d -= 9;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    /** Minor units as KBS2's history wrote them: amount / 100, e.g. 12.5 → "12.50", -700 → "-7.00". Pure. */
    static String formatAmount(int minorUnits) {
        return BigDecimal.valueOf(minorUnits, 2).toPlainString();
    }

    static boolean sameKey(Object a, Object b) {
        return a != null && b != null && (a.equals(b)
            || a instanceof Number x && b instanceof Number y && x.longValue() == y.longValue());
    }

    // ── As whom ────────────────────────────────────────────────────────────────────────────────────────────────

    private static <T> T asServer(Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
    }

    private static <T> T asServerActingFor(Object callerUserId, Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.setUserId(StateAccessor.createEmptyState(), callerUserId), call);
    }

    /** For tests: the statements' parameters are bound, never concatenated. */
    static List<String> statements() {
        return List.of(BOOKING_SQL, PAYMENT_SQL, TRANSFER_CHILDREN_SQL, METHOD_NAME_SQL, EDIT_SQL, DELETE_SQL, DELETE_TRANSFER_SQL);
    }
}

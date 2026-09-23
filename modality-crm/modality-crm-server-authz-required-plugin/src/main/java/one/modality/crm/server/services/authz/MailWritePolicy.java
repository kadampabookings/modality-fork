package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;

/**
 * A client may compose mail. It may not touch mail somebody else composed, and it may not choose where
 * an already-composed mail goes.
 *
 * <h3>What the mailer will send</h3>
 *
 * <p>{@code MailerJob} drains every {@code mail} row with {@code channel='email'} and {@code transmitted}
 * false whose date falls inside the drain window, and sends it from an address Kadampa has verified.
 * Clients INSERT rows into that table directly — eighteen places in the two React apps and one in the
 * legacy back office do it as their normal way of sending a notification.
 *
 * <h3>Be exact about what a client can already do, because it is easy to overstate</h3>
 *
 * <p>A client that inserts a {@code Mail} and nothing else does NOT choose the recipient: the
 * {@code auto_recipient} trigger derives one — the mail account's own address for an incoming
 * ({@code out=false}) message, the booking's person or their account owner for an outgoing one — and
 * discards the mail when it can resolve no address. The contact form is the first shape, and it reaches
 * only the centre's own mailbox. <b>The second shape is not harmless:</b> {@code out=true} with any
 * document id addresses that booking's person, so a client with no recipient row at all still sends
 * whatever HTML it likes to a member of the caller's choosing. Document ids are sequential. Nothing here
 * closes that, and it is not what this rule is about.
 *
 * <p>What IS free is the {@code recipient} row a client inserts alongside: its address is whatever the
 * client says. Fourteen client sites do exactly that legitimately, to reach a volunteer or a coordinator.
 * <b>So a client can still send arbitrary HTML to an arbitrary address from a verified Kadampa address,
 * and this rule does not stop it</b> — see what this does not close, below.
 *
 * <h3>What it does close: reaching mail that is not the caller's</h3>
 *
 * <p><b>Interception.</b> A mail sits in the table between being composed and being drained. Until this
 * rule, an ordinary client write — not a raw statement, the shape a change set produces — could add a
 * {@code recipient} row naming the caller's own address to somebody else's pending mail. The mailer then
 * delivered that mail, unaltered, to the caller as well. For a magic-link or password-recovery mail the
 * content is a live sign-in capability, so this is account takeover; for a booking confirmation it is
 * somebody's personal data. Mail ids are sequential, so finding one takes no read access.
 *
 * <p><b>Rewriting and deletion.</b> The same reach the other way: editing a pending mail's subject and
 * content turns a confirmation the member is expecting into whatever the caller likes, still sent from
 * the verified address to the real member — a far more credible phish than one sent cold. Deleting it
 * suppresses the mail instead.
 *
 * <h3>Why "the same batch" is the test, and why it is exact rather than a proxy</h3>
 *
 * <p>A recipient for a mail the caller is composing references that mail by a
 * {@link GeneratedKeyReference}, because the id does not exist yet; one aimed at a mail that already
 * exists must name it by a literal id. The two are different at the wire, so the rule reads the
 * distinction rather than guessing at it: no address is inspected, no allowlist is kept, and no
 * legitimate write has to be recognised.
 *
 * <p><b>The reference is resolved, not merely recognised.</b> Being a reference says only that the caller
 * points at some statement of its own batch, and the batch is the caller's to compose — it may point at
 * an insert into any table it can write, whose generated id lands in {@code recipient.mail_id} and will be
 * accepted if it matches an existing mail, which a sequence can be walked towards with throwaway inserts.
 * So the statement pointed at is resolved through {@code batchInsertAt} and must itself insert a Mail.
 * That is why the guard reads a whole batch before judging any statement in it.
 *
 * <p>This is deliberately not the rule that suggests itself first — "a recipient's address must be one
 * the database already holds". That one sounds strong and is not: every member's address is in the
 * database, and a mail to a member from a verified Kadampa address is the phish worth sending. It would
 * have refused nothing that mattered while reading as though it had.
 *
 * <p>UPDATE and DELETE are refused outright on both tables for the same reason, there being no
 * "same batch" for a row that already exists. Checked against both apps before being listed, as the
 * account columns were: every client write of {@code Recipient} in either stack is an INSERT passing the
 * handle of the {@code Mail} inserted beside it, and no client updates or deletes a {@code Mail} or a
 * {@code Recipient} at all. The mailer's own writes are server-side and never reach this guard.
 *
 * <h3>What this does NOT close</h3>
 *
 * <p><b>And the same interception by a different road, which this rule does not reach at all.</b> A client
 * never has to touch {@code mail} or {@code recipient} to have somebody else's letter sent where it likes:
 * {@code document.person_email} and {@code document.trigger_send_letter_id} are both ordinary client-writable
 * columns, and setting the second makes the DATABASE compose that booking's letter and address it to the
 * first. Those writes are the trigger's, server-side, so this guard never sees them. Document ids are
 * sequential. Neither column can simply be denied — the React back office writes the address on its booking
 * details tab, and the legacy back office sets the trigger to send a confirmation — so closing it needs an
 * ownership rule on Document (the plan's item C) or those two paths moved to server endpoints. Stated here
 * because a reader of this section would otherwise conclude that intercepting somebody's booking mail is
 * closed, and it is not.
 *
 * <p>V0109 adds a third column to that road: {@code document.payer_id}. A letter whose type carries
 * {@code letter_type.payer} (the "Payment request") is addressed to the payer, and setting the payer also
 * moves the booking into that payer's cart, whose /pay-cart/ link the letter carries. So a client that
 * writes {@code payer_id} and {@code trigger_send_letter_id} on a booking it does not own can aim that
 * booking's payment request, with a working payment link, at any existing person. The Education
 * programmes page is its only legitimate writer; the same item C ownership rule, making {@code payer}
 * staff-only, is what closes it.
 *
 * <p>The relay itself. A client composing its own mail still picks the subject, the body and the
 * address, which is what the eighteen sites are for. Only {@code denyTable("mail")} and
 * {@code denyTable("recipient")} close that, and they cannot be taken while those sites write the tables
 * — nor while the legacy back office's {@code CommunicationsTab} inserts a mail and a recipient, which is
 * the same blocker that holds up {@code denyTable("person")}. This narrows the reach to the caller's own
 * mail; it does not end the relay, and must not be described as though it had.
 *
 * @author Claude Code
 */
final class MailWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** Told to the caller, and deliberately about the statement rather than about them. */
    static final String EXISTING_MAIL_REFUSED =
        "A recipient can only be added to a mail created in the same batch";

    static final String CHANGE_REFUSED = "A client may create mail, but not change or delete it";

    /** The whole rule, as a pure function of the write, so the check can exercise it directly. */
    static String refusalFor(ProtectedEntityWriteRegistry.WriteRequest write) {
        if (write == null)
            return ClientSubmitGuard.UNCHECKABLE_REFUSED;
        boolean mailTable = "Mail".equals(write.entityName());
        if (!mailTable && !"Recipient".equals(write.entityName()))
            return null;
        if (write.verb() != ProtectedEntityWriteRegistry.WriteVerb.INSERT)
            return CHANGE_REFUSED;
        if (mailTable) // composing a new mail is the thing clients are allowed to do
            return null;
        // A mail THIS BATCH IS CREATING, and nothing else.
        //
        // Both halves are needed. "Is it a reference?" alone says only that the caller is pointing at some
        // statement of its own batch - and the batch is the caller's to compose, so that statement can be an
        // insert into anything. The generated key it yields is an id from that other table's sequence, and
        // recipient.mail_id would take it if it happened to match a mail: a sequence can be advanced with
        // throwaway inserts until it does. So the statement pointed at is resolved and must itself insert a Mail.
        //
        // An unreadable or absent value fails here too: a recipient that names no mail this can read is not one
        // this can clear.
        Object mail = write.writtenValues() == null ? null : write.writtenValues().get("mail");
        if (!(mail instanceof GeneratedKeyReference reference))
            return EXISTING_MAIL_REFUSED;
        return "Mail".equals(write.batchInsertAt(reference.getStatementBatchIndex()))
            ? null : EXISTING_MAIL_REFUSED;
    }

    @Override
    public Future<String> refusalReason(ProtectedEntityWriteRegistry.WriteRequest write) {
        return Future.succeededFuture(refusalFor(write));
    }
}

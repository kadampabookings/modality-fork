package one.modality.base.server.mailer;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.async.Promise;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Booleans;
import dev.webfx.stack.mail.transport.MailAddress;
import dev.webfx.stack.mail.transport.MailTransport;
import dev.webfx.stack.mail.transport.TransportMessage;
import dev.webfx.stack.mail.transport.TransportTags;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityList;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.session.state.SystemUserId;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Letter;
import one.modality.base.shared.entities.LetterType;
import one.modality.base.shared.entities.Mail;
import one.modality.base.shared.entities.MailAccount;
import one.modality.base.shared.entities.Recipient;
import one.modality.base.shared.entities.SmtpAccount;

import java.time.Instant;
import java.util.List;

/**
 * Per-mail pipeline, a behaviour-preserving port of KBS2's JavaMailSender: load the mail
 * graph through its recipients, resolve the sender, build the transport message, transmit,
 * and bookkeep. Bookkeeping ALWAYS marks the mail transmitted — on failure with the error
 * recorded — which is what stops a poison mail from blocking the queue in a fast loop.
 *
 * @author Bruno Salmon
 */
final class MailTransmitter {

    private static final String LOG_PREFIX = "[mailer] ";
    // The legacy fallback display name for cart mails enqueued without a fromName
    private static final String LEGACY_CART_FROM_NAME = "Booking cart";
    private static final SystemUserId MAILER_USER_ID = new SystemUserId("mailer");

    private String redirectTo; // non-empty: replaces every recipient address (QA rehearsals)

    void setRedirectTo(String redirectTo) {
        this.redirectTo = redirectTo == null || redirectTo.isEmpty() ? null : redirectTo;
    }

    /**
     * Succeeds once the mail is processed AND bookkept — including the case where the
     * transport failed (mail marked transmitted-with-error). Fails only when the
     * bookkeeping submit itself failed, i.e. the mail is still marked untransmitted.
     */
    Future<Void> transmitMail(Mail mail) {
        return loadRecipients(mail).compose(recipients -> {
            TransportMessage message;
            try {
                message = buildMessage(mail, recipients);
            } catch (Exception buildError) {
                Console.log("⛔️ " + LOG_PREFIX + "Could not build mail " + mail.getPrimaryKey() + ": " + buildError);
                return completeMail(mail, recipients, buildError);
            }
            Console.log(LOG_PREFIX + "Sending '" + message.getSubject() + "' to " + message.getTo());
            Promise<Void> promise = Promise.promise();
            MailTransport.transmit(message)
                    .onSuccess(result -> {
                        Console.log(LOG_PREFIX + "Sent " + result);
                        completeMail(mail, recipients, null).onComplete(promise);
                    })
                    .onFailure(transportError -> {
                        Console.log("⛔️ " + LOG_PREFIX + "Transport failed for mail " + mail.getPrimaryKey());
                        Console.log(transportError);
                        completeMail(mail, recipients, transportError).onComplete(promise);
                    });
            return promise.future();
        });
    }

    private Future<EntityList<Recipient>> loadRecipients(Mail mail) {
        // One query loads the recipients AND (through the mail FK, into the same store) every
        // mail/document/letter/account/smtp field the pipeline needs — mirror of the KBS2 load.
        return mail.getStore().<Recipient>executeQuery(
                "select name,email,to,cc,bcc,mail.(subject,content,fromName,fromEmail,"
                        + "document.(ref,event,passReady),"
                        + "letter.type.(confirmation,cancellation),"
                        + "account.(name,email,awsSesVerified,smtpAccount.(host,port,username,password,ssl,email)))"
                        + " from Recipient where mail=$1",
                mail.getPrimaryKey());
    }

    private TransportMessage buildMessage(Mail mail, List<Recipient> recipients) {
        MailAccount account = mail.getAccount();
        if (account == null)
            throw new IllegalStateException("Mail " + mail.getPrimaryKey() + " has no mail account");
        SmtpAccount smtpAccount = account.getSmtpAccount();

        TransportMessage.Builder builder = TransportMessage.builder()
                .subject(mail.getSubject())
                .htmlBody(mail.getContent())
                .header("Message-ID", buildMessageId(mail));

        // Sender resolution (KBS2 JavaMailSender parity). SES only sends from verified
        // domains: an awsSesVerified account sends as itself, others send from the central
        // verified address with Reply-To routing replies back to the centre. A mail-level
        // fromEmail (cart / chat flows) always becomes the Reply-To.
        boolean sesVerified = Booleans.isTrue(account.isAwsSesVerified());
        String accountEmail = account.getEmail();
        String accountName = account.getName();
        String senderEmail = sesVerified ? accountEmail : (smtpAccount == null ? null : smtpAccount.getEmail());
        if (senderEmail == null)
            throw new IllegalStateException("No sender address for mail " + mail.getPrimaryKey() + " (account not SES-verified and no central smtp address)");
        String fromEmail = mail.getFromEmail();
        String fromName = mail.getFromName();
        if (fromEmail != null) {
            builder.from(MailAddress.of(senderEmail, fromName != null ? fromName : LEGACY_CART_FROM_NAME))
                    .addReplyTo(MailAddress.of(fromEmail, fromName));
        } else {
            builder.from(MailAddress.of(senderEmail, accountName));
            if (!sesVerified)
                builder.addReplyTo(MailAddress.of(accountEmail, accountName));
        }

        for (Recipient recipient : recipients) {
            MailAddress address = MailAddress.of(
                    redirectTo != null ? redirectTo : recipient.getEmail(),
                    recipient.getName());
            if (Booleans.isTrue(recipient.isTo()))
                builder.addTo(address);
            if (Booleans.isTrue(recipient.isCc()))
                builder.addCc(address);
            if (Booleans.isTrue(recipient.isBcc()))
                builder.addBcc(address);
        }

        if (smtpAccount != null) {
            builder.tag(TransportTags.SMTP_HOST, smtpAccount.getHost())
                    .tag(TransportTags.SMTP_PORT, String.valueOf(smtpAccount.getPort()))
                    .tag(TransportTags.SMTP_SSL, String.valueOf(Booleans.isTrue(smtpAccount.isSsl())))
                    .tag(TransportTags.SMTP_USERNAME, smtpAccount.getUsername())
                    .tag(TransportTags.SMTP_PASSWORD, smtpAccount.getPassword());
        }

        TransportMessage message = builder.build();
        if (!message.hasRecipients())
            throw new IllegalStateException("Mail " + mail.getPrimaryKey() + " has no recipients");
        return message;
    }

    // Byte-for-byte the KBS2 Message-ID format — bounce handling correlates on it
    private String buildMessageId(Mail mail) {
        Document document = mail.getDocument();
        return "<" + System.currentTimeMillis()
                + ".mail." + mail.getPrimaryKey()
                + ".document." + (document == null ? null : document.getPrimaryKey())
                + ".ref." + (document == null ? null : document.getRef())
                + ".event." + (document == null || document.getEventId() == null ? null : document.getEventId().getPrimaryKey())
                + "@ds." + DataSourceModelService.getDefaultDataSourceModel().getDataSourceId() + '>';
    }

    private Future<Void> completeMail(Mail mail, List<Recipient> recipients, Throwable error) {
        UpdateStore updateStore = UpdateStore.createAbove(mail.getStore());
        Mail updatedMail = updateStore.updateEntity(mail);
        updatedMail.setTransmitted(true);
        updatedMail.setTransmissionDate(Instant.now());
        if (error != null) {
            String errorMessage = error.getMessage() != null ? error.getMessage() : error.toString();
            if (errorMessage.length() > 254)
                errorMessage = errorMessage.substring(0, 254);
            updatedMail.setError(errorMessage);
        } else {
            // Sending a confirmation / cancellation letter flips the matching booking flags
            // (KBS2 parity, including the passReady interplay with the read flag)
            Letter letter = mail.getLetter();
            Document document = mail.getDocument();
            LetterType letterType = letter == null ? null : letter.getType();
            if (letterType != null && document != null) {
                boolean passReady = Booleans.isTrue(document.isPassReady());
                if (Booleans.isTrue(letterType.isConfirmation())) {
                    Document updatedDocument = updateStore.updateEntity(document);
                    updatedDocument.setConfirmed(true);
                    if (!passReady)
                        updatedDocument.setRead(true);
                } else if (Booleans.isTrue(letterType.isCancellation())) {
                    Document updatedDocument = updateStore.updateEntity(document);
                    updatedDocument.setCancelled(true);
                    updatedDocument.setRead(!passReady);
                }
            }
        }
        for (Recipient recipient : recipients) {
            Recipient updatedRecipient = updateStore.updateEntity(recipient);
            if (error == null)
                updatedRecipient.setOk(true);
            else
                updatedRecipient.setPermanentError(true);
        }
        return MAILER_USER_ID.callAndReturn(updateStore::submitChanges)
                .onFailure(submitError -> {
                    // The mail is still untransmitted in the DB; the next poll retries it. No
                    // fast retry — if the DB is down we must not hammer it at send pace.
                    Console.log("⛔️ " + LOG_PREFIX + "Bookkeeping failed for mail " + mail.getPrimaryKey());
                    Console.log(submitError);
                })
                .map(ignored -> null);
    }
}

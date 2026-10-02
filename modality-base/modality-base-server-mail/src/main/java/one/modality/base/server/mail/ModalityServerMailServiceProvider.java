package one.modality.base.server.mail;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.mail.MailMessage;
import dev.webfx.stack.mail.spi.MailServiceProvider;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.UpdateStore;
import one.modality.base.shared.context.ModalityContext;
import one.modality.base.shared.entities.Mail;
import one.modality.base.shared.entities.Recipient;

/**
 * @author Bruno Salmon
 */
public class ModalityServerMailServiceProvider implements MailServiceProvider {

    private final DataSourceModel dataSourceModel;

    public ModalityServerMailServiceProvider() {
        this(DataSourceModelService.getDefaultDataSourceModel());
    }

    public ModalityServerMailServiceProvider(DataSourceModel dataSourceModel) {
        this.dataSourceModel = dataSourceModel;
    }

    @Override
    public Future<Void> sendMail(MailMessage mailMessage) {
        UpdateStore updateStore = UpdateStore.create(dataSourceModel);
        Mail mail = updateStore.insertEntity(Mail.class);
        mail.setFromEmail(mailMessage.getFrom());
        mail.setSubject(mailMessage.getSubject());
        mail.setContent(mailMessage.getBody());
        mail.setOut(true);
        Object documentId = null, magicLinkId = null;
        if (mailMessage instanceof ModalityMailMessage) {
            ModalityMailMessage modalityMessage = (ModalityMailMessage) mailMessage;
            // Sender display name is flow-specific (branded for magic-link emails,
            // unset for other flows), so the caller decides via ModalityMailMessage.
            if (modalityMessage.getFromName() != null) {
                mail.setFromName(modalityMessage.getFromName());
            }
            ModalityContext modalityContext = modalityMessage.getModalityContext();
            if (modalityContext != null) {
                documentId = modalityContext.getDocumentId();
                magicLinkId = modalityContext.getMagicLinkId();
                mail.setDocument(documentId);
                mail.setOrganization(modalityContext.getOrganizationId());
                mail.setMagicLink(magicLinkId);
            }
        }
        // Temporarily hardcoding the account to be used for sending mails, because otherwise, if the organization is
        // set to a centre where no mail account is configured, the database will raise this constraint error:
        // null value in column "account_id" violates not-null constraint (23502)
        mail.setAccount(27); // Hardcoded value for kbs@kadampa.net account, because so far only LoginLinkService is sending mails in KBS3

        // The address the caller gave us, written down rather than dropped.
        //
        // `mail` has no `to` column: addressing lives in `recipient`, and the auto_recipient trigger
        // fills it from the mail's magic link or its document. A mail carrying NEITHER was addressed by
        // nobody - the trigger reaches neither branch and inserts nothing, and its own "discard a mail
        // we cannot address" DELETE sits inside the document branch, so the row survived to be drained,
        // fail and be marked transmitted. That is three real senders - the email-change notice, the
        // password-closed notice and the guest booking-recovery mail - none of which has ever arrived.
        // See docs/security/mail-addressing-gap.md.
        //
        // Only when the trigger cannot do it. It runs AFTER INSERT on mail, which is before this row
        // reaches the database, so writing one here as well would leave a magic-link or document mail
        // with two recipients and send it twice.
        if (documentId == null && magicLinkId == null) {
            String to = mailMessage.getTo();
            if (to == null || to.trim().isEmpty())
                // Refused rather than inserted: a mail with no document, no magic link and no address
                // cannot be sent by anybody, and the row left behind looks queued for as long as anyone
                // cares to look at it. Callers treat a failure as "not sent", which is the truth.
                return Future.failedFuture(new IllegalArgumentException(
                        "Cannot send a mail with no recipient: no 'to' address, and no document or magic link to derive one from"));
            Recipient recipient = updateStore.insertEntity(Recipient.class);
            recipient.setMail(mail);
            recipient.setEmail(to.trim());
            recipient.setTo(true);
            recipient.setCc(false);
            recipient.setBcc(false);
            recipient.setOk(false);
        } else if (documentId != null && magicLinkId == null && mailMessage.getTo() != null) {
            // A `to` that would be silently ignored, in the one shape where that is a trap rather than a
            // harmless duplicate. The trigger addresses a document mail to the BOOKING's person or their
            // account owner, so a sender passing both - the natural thing to do, since the context is
            // what ties a mail to its booking in the UI - would have their message delivered to the
            // member instead of the address they named, carrying whatever access that letter type
            // grants. Refused here rather than discovered there.
            //
            // Narrowed to the document-without-magic-link case on purpose: the magic-link flow
            // legitimately passes both, and the address it passes is the one the trigger derives anyway,
            // so refusing that combination would take out every sign-in and password-reset mail. No
            // caller is in the shape refused here, which is why it can be refused at all.
            return Future.failedFuture(new IllegalArgumentException(
                    "A mail carrying a document is addressed from that booking, so its 'to' would be ignored."
                    + " Pass a 'to' or a document, not both."));
        }

        return updateStore.submitChanges().map(ignored -> null);
    }
}

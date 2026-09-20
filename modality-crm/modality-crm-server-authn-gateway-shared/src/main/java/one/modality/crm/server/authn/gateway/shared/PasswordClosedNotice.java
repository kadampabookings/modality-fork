package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Strings;
import dev.webfx.stack.mail.MailMessage;
import dev.webfx.stack.mail.MailService;
import one.modality.base.server.mail.ModalityMailMessage;
import one.modality.base.shared.context.ModalityContext;

/**
 * What an account whose password is closed ("Stop my password working", V0096) receives instead of a sign-in link, a
 * code or a password reset: a note that nothing was sent, and that its passkey is the way in.
 *
 * <p>Sending nothing at all was the alternative, and it leaves an owner who has forgotten they closed it waiting for a
 * mail that never comes. This one carries no link and no code, so there is nothing in it for whoever else reads the
 * mailbox, and it is only sent to the account's own address, which already knows the account exists.
 *
 * <p>No sign-in token is created for it either, unlike the "unknown account" mail, which records one for history: a
 * token that is never sent is still one more live way in, refused only because the redeem path checks again.
 *
 * @author Claude Code
 */
public final class PasswordClosedNotice {

    private static final String MAIL_FROM = "kbs@kadampa.net";
    private static final String MAIL_FROM_NAME = "Kadampa Booking System";

    private static final LocalizedMailTemplate MAIL = LocalizedMailTemplate.load(
        "PasswordSignInClosedMailBody", "PasswordSignInClosedMailMessages", PasswordClosedNotice.class);

    private PasswordClosedNotice() {}

    /**
     * Sends the notice to {@code email}, in {@code lang}.
     *
     * @return a future that always succeeds — like the request it answers, whose caller is never told whether the
     *         address has an account, a failure to send is logged here rather than reported
     */
    public static Future<Void> send(String email, String lang) {
        if (Strings.isEmpty(email))
            return Future.succeededFuture();
        MailMessage message = MailMessage.create(MAIL_FROM, email, MAIL.renderSubject(lang), MAIL.renderBody(lang));
        ModalityContext context = new ModalityContext(1 /* default organizationId, as for the magic-link mails */, null, null, null);
        return MailService.sendMail(new ModalityMailMessage(message, context, MAIL_FROM_NAME))
            .<Void>map(ignored -> null)
            .recover(e -> {
                // The exception's type only: a provider's message usually quotes the recipient
                Console.log("⚠️ Password-closed notice could not be sent (" + e.getClass().getSimpleName() + ")");
                return Future.succeededFuture();
            });
    }
}

package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Strings;
import dev.webfx.stack.mail.MailMessage;
import dev.webfx.stack.mail.MailService;
import one.modality.base.server.mail.ModalityMailMessage;
import one.modality.base.shared.context.ModalityContext;

/**
 * Tells an account's PREVIOUS address that its sign-in email has just been changed.
 *
 * <p>Changing the sign-in email changes who can recover the account, which makes it the change a thief most
 * wants. The server now requires the current password or a fresh recovery before it will start one, but
 * nothing stops the person who has both; what is left is making sure the owner hears about it, at the one
 * address they still read. The confirmation link went to the NEW address, so without this the old one is told
 * nothing at all.
 *
 * <p>The new address is shown masked — its first character and its domain — which is enough for the owner to
 * recognise their own change, or to see that it is not theirs, without handing the full address to whoever
 * reads the old mailbox now (an old work address, a shared family one).
 *
 * <p>Sent after the change has committed, and never allowed to undo it: a notice that failed to send is
 * logged, not turned into a failed email change the person would then retry.
 *
 * @author Claude Code
 */
public final class EmailChangeNotice {

    private static final String MAIL_FROM = "kbs@kadampa.net";
    private static final String MAIL_FROM_NAME = "Kadampa Booking System";

    private static final LocalizedMailTemplate MAIL = LocalizedMailTemplate.load(
        "AccountEmailChangedNoticeMailBody", "AccountEmailChangedNoticeMailMessages", EmailChangeNotice.class);

    private EmailChangeNotice() {}

    /**
     * Sends the notice to {@code previousEmail}, in {@code lang}. Does nothing when there is no previous address
     * or it is the same address (a change of case only).
     *
     * @return a future that always succeeds — a failure to send is logged here
     */
    public static Future<Void> send(String previousEmail, String newEmail, String lang) {
        if (Strings.isEmpty(previousEmail) || Strings.isEmpty(newEmail) || previousEmail.equalsIgnoreCase(newEmail))
            return Future.succeededFuture();
        String body = MAIL.renderBody(lang).replace("[newEmail]", escapeHtml(mask(newEmail)));
        MailMessage message = MailMessage.create(MAIL_FROM, previousEmail, MAIL.renderSubject(lang), body);
        ModalityContext context = new ModalityContext(1 /* default organizationId, as for the magic-link mails */, null, null, null);
        return MailService.sendMail(new ModalityMailMessage(message, context, MAIL_FROM_NAME))
            .<Void>map(ignored -> null)
            .recover(e -> {
                // The exception's type only: a provider's message usually quotes the recipient, which is
                // somebody's former address, and does not belong in the server log
                Console.log("⚠️ Email-change notice to the previous address could not be sent (" + e.getClass().getSimpleName() + ")");
                return Future.succeededFuture();
            });
    }

    /** {@code jane.doe@example.org} → {@code j***@example.org}. Anything without an {@code @} is masked whole. */
    static String mask(String email) {
        int at = email.lastIndexOf('@');
        if (at <= 0)
            return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * The address is chosen by whoever made the change, and goes into an HTML body: escaped, because an email
     * address's local part may legally hold characters HTML gives meaning to.
     */
    static String escapeHtml(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}

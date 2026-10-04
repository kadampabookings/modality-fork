package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

/**
 * What the two room-share mails have in common: how a message and its one recipient are written.
 *
 * <p>Shared rather than copied because the pair of statements is exactly the sort that drifts — a column
 * list, the quoting of {@code "to"}, the generated-key reference that joins them — and the two senders
 * would then differ in ways nothing would catch. {@link MateInvitationMail} appends a third statement of
 * its own to the same batch; {@link MateJoinedMail} needs only these two.
 *
 * <p><b>Written here rather than through MailService</b>, for the reason the invitation gives: the mail
 * has to land in the same transaction as what it reports, and the ordinary path hands back no id.
 */
final class MateMails {

    /** mail.subject is varchar(255); recipient.name is varchar(91); recipient.email is varchar(127). */
    static final int MAX_SUBJECT = 255, MAX_NAME = 91, MAX_EMAIL = 127;
    /** Longer than any name a greeting needs, and short enough not to be a paragraph. */
    static final int MAX_PRINTED_NAME = 40;

    private static final String MAIL_FROM = "kbs@kadampa.net";
    private static final String MAIL_FROM_NAME = "Kadampa Booking System";
    /** The account the other KBS3 senders use until mail accounts are resolved per organisation. */
    private static final int MAIL_ACCOUNT_ID = 27;

    private static final String MAIL_SQL =
        "insert into mail (account_id, out, subject, content, from_name, from_email)" +
        " values ($1, true, $2, $3, $4, $5) returning id";

    private static final String RECIPIENT_SQL =
        "insert into recipient (mail_id, person_id, name, email, \"to\", cc, bcc, ok)" +
        " values ($1, $2, $3, $4, true, false, false, false)";

    private MateMails() { }

    /**
     * The two statements that enqueue one message to one person, in that order: the second resolves its
     * mail id from the first's generated key, so they belong to one batch and neither works alone.
     *
     * <p>{@code personId} is the recipient's person row where we know it, and null where we do not —
     * an invitee is a stranger to this system and may never become one. It is not decoration: without
     * it the row is invisible to any person-keyed lookup, which is how a stored address escapes a
     * subject access request or an erasure sweep. Every other sender here records it, the database's own
     * `auto_recipient` trigger goes to the trouble of computing it, and leaving it null for somebody we
     * can name would be the one place that quietly did not.
     */
    static SubmitArgument[] mailAndRecipient(
        String subject, String body, String name, String email, Object personId) {
        Object dataSourceId = DataSourceModelService.getDefaultDataSourceId();
        SubmitArgument mail = new SubmitArgumentBuilder()
            .setDataSourceId(dataSourceId)
            // " returning id" with setReturnGeneratedKeys is what the reference below resolves against;
            // without the pair, nothing is addressed.
            .setStatement(MAIL_SQL)
            .setParameters(MAIL_ACCOUNT_ID, cut(subject, MAX_SUBJECT), body, MAIL_FROM_NAME, MAIL_FROM)
            .setReturnGeneratedKeys(true)
            .build();
        SubmitArgument recipient = new SubmitArgumentBuilder()
            .setDataSourceId(dataSourceId)
            .setStatement(RECIPIENT_SQL)
            .setParameters(new GeneratedKeyReference(0), personId, cut(name, MAX_NAME), cut(email, MAX_EMAIL))
            .build();
        return new SubmitArgument[] { mail, recipient };
    }

    /**
     * A name as a message may print it, or a neutral word when what the booking holds is not one.
     *
     * Escaping stops markup; it does not stop PROSE. These names are free text on somebody's booking,
     * and these messages go out under our own name — so forty characters of it are an invitation to
     * write a URL and have a mail client render it as a link beside our branding. Anything carrying a
     * scheme, an at-sign or something domain-shaped is therefore not printed at all, and a long one is
     * cut: a person genuinely called that is better greeted impersonally than used as a billboard.
     */
    static String nameOrNeutral(String value, String neutral) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > MAX_PRINTED_NAME)
            return neutral;
        // Square brackets out, so a name cannot name a placeholder the next `replace` will fill. Harmless
        // in these two templates — every substituted value is our own escaped data, and the notification
        // carries no link to hijack — but the discipline is what keeps that true of the next one.
        if (name.indexOf('[') >= 0 || name.indexOf(']') >= 0)
            return neutral;
        String lower = name.toLowerCase();
        if (lower.contains("://") || lower.contains("www.") || lower.indexOf('@') >= 0
            || lower.matches(".*\\.[a-z]{2,}.*"))
            return neutral;
        return escapeHtml(name);
    }

    /**
     * Names reach these messages from a booking form, so they are somebody's free text. Escaped rather
     * than trusted: these mails go out under our own name.
     */
    static String escapeHtml(String value) {
        if (value == null)
            return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

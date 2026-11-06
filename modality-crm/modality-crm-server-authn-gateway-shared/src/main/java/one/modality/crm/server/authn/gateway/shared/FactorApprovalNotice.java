package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.mail.MailMessage;
import dev.webfx.stack.mail.MailService;
import one.modality.base.server.mail.ModalityMailMessage;
import one.modality.base.shared.context.ModalityContext;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The emails a new second factor sends — to the super administrators when it waits for them, and always to its owner.
 *
 * <h3>To the super administrators: enough to judge, nothing to click</h3>
 *
 * <p>A PENDING device is a request somebody must decide, and the decision is only as good as what the decider sees:
 * who it is, their phone on record (to call them back on a number already known), what device, the address and
 * offline location it came from, how the session that added it signed in, and the warnings the approval page shows.
 * It deliberately carries <b>no approve link</b>: an approval that a click in a mailbox could give would make the
 * mailbox the approval factor — exactly what this gate exists not to trust. It says where to decide instead.
 *
 * <h3>To the owner: a tripwire</h3>
 *
 * <p>Every new device, approved at once or not, tells its owner. If an attacker with the password added it, the owner
 * hears about it from the system rather than from the consequences. It names the device and the time only.
 *
 * <h3>Personal data</h3>
 *
 * <p>The super-administrator mail carries a name, a phone number and an IP address — to the two or three people whose
 * job is this decision, and only for a PENDING request. Every interpolated value is HTML-escaped: names, device labels
 * and the browser-reported user agent are typed by the person being checked, and an email body is rendered HTML.
 * Nothing here is logged beyond ids. Mail lands in the {@code mail} table and follows its retention.
 *
 * <p>English only for now: the recipients are the super administrators and back-office staff, and the
 * LocalizedMailTemplate pattern has no interpolation. Never fails the enrolment: a mail that cannot be sent is logged.
 *
 * @author Claude Code
 */
public final class FactorApprovalNotice {

    public static final String KIND_PASSKEY = "passkey";
    public static final String KIND_TOTP = "totp";

    /**
     * The start of every approver email's subject — what the 90-day clearing of the {@code mail} table matches on
     * (SignInApprovals.clearOldContext), since that mail carries the same IP, place and phone as the request.
     */
    public static final String APPROVER_SUBJECT_PREFIX = "Sign-in device waiting for approval";

    private static final String MAIL_FROM = "kbs@kadampa.net";
    private static final String MAIL_FROM_NAME = "Kadampa Booking System";
    private static final String LOG_PREFIX = "[2fa] ";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH);

    // The account's owner person: what the approver will phone, and where the owner's tripwire goes. Live persons
    // first, the account owner first, then the lowest id — the magic-link resolution's order.
    private static final String OWNER_SQL =
        "SELECT p.first_name, p.last_name, p.phone, a.username, o.name" +
        "  FROM frontend_account a JOIN person p ON p.frontend_account_id = a.id" +
        "  LEFT JOIN organization o ON o.id = p.organization_id" +
        " WHERE a.id = $1 ORDER BY p.removed, p.owner DESC, p.id LIMIT 1";

    // Every super administrator except the requester's own account, with the address to write to: the login username,
    // which IS an address the person controls, rather than the contact email anybody may have typed.
    private static final String SUPER_ADMINS_SQL =
        "SELECT DISTINCT a.username FROM authorization_super_admin s" +
        "  JOIN person p ON p.id = s.super_admin_id JOIN frontend_account a ON a.id = p.frontend_account_id" +
        " WHERE p.removed IS NOT TRUE AND a.disabled IS NOT TRUE AND a.id <> $1";

    private FactorApprovalNotice() {}

    /**
     * Sends the notices for a factor just stored. Fire-and-forget: returns at once, never fails the caller.
     *
     * @param kind {@link #KIND_PASSKEY} or {@link #KIND_TOTP}
     * @param authenticator the passkey's AAGUID (the approval page names the provider from it), or null
     */
    public static void factorAdded(Object accountId, String kind, String label, String authenticator, FactorRequest.Resolved request) {
        Long account = Numbers.toLong(accountId);
        if (account == null)
            return;
        RawSql.query(OWNER_SQL, account).compose(owner -> {
            if (owner.getRowCount() != 1)
                return Future.succeededFuture();
            String first = string(owner, 0), last = string(owner, 1), phone = string(owner, 2), username = string(owner, 3),
                organization = string(owner, 4);
            String name = (nonNull(first) + " " + nonNull(last)).trim();
            String device = deviceText(kind, label);
            String when = ZonedDateTime.now(ZoneOffset.UTC).format(TIME);
            List<Future<?>> sends = new ArrayList<>();
            sends.add(send(username, "A new sign-in device was added to your account",
                ownerBody(device, when, request.isPending())));
            if (request.isPending())
                sends.add(RawSql.query(SUPER_ADMINS_SQL, account).compose(admins -> {
                    List<Future<?>> adminSends = new ArrayList<>();
                    String body = approverBody(name.isEmpty() ? username : name, username, phone, organization, device,
                        authenticator, when, request);
                    for (int row = 0; row < admins.getRowCount(); row++)
                        adminSends.add(send(string(admins, row, 0), APPROVER_SUBJECT_PREFIX + ": "
                            + (name.isEmpty() ? "a staff member" : name.replaceAll("[\\r\\n\\t]", " ")), body));
                    return Future.all(adminSends).mapEmpty();
                }));
            return Future.all(sends).mapEmpty();
        }).onFailure(e -> Console.log(LOG_PREFIX + "⚠️ Second-factor notices for account " + account
                                      + " could not be prepared (" + e.getClass().getSimpleName() + ")"));
    }

    private static String ownerBody(String device, String when, boolean pending) {
        return page("A new sign-in device",
            "<p>" + esc(device) + " was added to your Kadampa Booking System back-office account on " + esc(when) + ".</p>"
            + (pending ? "<p>It will work once a super administrator has approved it. They may phone you to check it was you.</p>"
                       : "<p>It can be used to sign in straight away.</p>")
            + "<p><b>If this wasn't you</b>, contact a super administrator at once: somebody may know your password.</p>");
    }

    private static String approverBody(String name, String username, String phone, String organization, String device,
                                       String authenticator, String when, FactorRequest.Resolved request) {
        StringBuilder rows = new StringBuilder();
        row(rows, "Person", name);
        row(rows, "Sign-in email", username);
        row(rows, "Phone on record", phone);
        row(rows, "Organisation", organization);
        row(rows, "Device", device);
        row(rows, "Authenticator id", authenticator);
        row(rows, "Reported by the browser", request.userAgent());
        row(rows, "IP address", request.ip());
        row(rows, "Location (approximate)", request.location());
        row(rows, "Signed in with", signInText(request.signInMethod()));
        row(rows, "Requested", when);
        String warning = request.signInMethod() != null && (request.signInMethod().contains("ml") || request.signInMethod().contains("code"))
            ? "<p style=\"color:#b42318\"><b>Warning:</b> this device was added from a session opened with an emailed link or code.</p>" : "";
        return page("A sign-in device is waiting for your approval",
            "<p>Before approving, <b>phone the person on a number you already had</b> — not one from an email — and check they"
            + " added this device, at this time.</p>" + warning
            + "<table style=\"border-collapse:collapse;margin:0 auto;text-align:left\">" + rows + "</table>"
            + "<p>Decide in the back office: <b>Dashboard → Sign-in approvals</b>. There is deliberately no button in this email.</p>"
            + "<p style=\"font-size:12px;color:#888\">IP geolocation by DB-IP (db-ip.com).</p>");
    }

    private static void row(StringBuilder rows, String label, String value) {
        if (value == null || value.isBlank())
            return;
        rows.append("<tr><td style=\"padding:4px 12px;color:#606060\">").append(esc(label))
            .append("</td><td style=\"padding:4px 12px;color:#303030\"><b>").append(esc(value)).append("</b></td></tr>");
    }

    private static String deviceText(String kind, String label) {
        String base = KIND_TOTP.equals(kind) ? "An authenticator app" : "A passkey";
        return label == null || label.isBlank() ? base : base + " (“" + label + "”)";
    }

    private static String signInText(String methods) {
        if (methods == null)
            return null;
        List<String> parts = new ArrayList<>();
        for (String method : methods.split(","))
            switch (method.trim()) {
                case "pwd": parts.add("password"); break;
                case "pk": parts.add("passkey"); break;
                case "totp": parts.add("authenticator app"); break;
                case "ml": parts.add("emailed link"); break;
                case "code": parts.add("emailed code"); break;
                default: if (!method.isBlank()) parts.add(method.trim());
            }
        return String.join(" + ", parts);
    }

    private static String page(String title, String content) {
        return "<html lang=\"en\"><head><meta charset=\"UTF-8\"><title>" + esc(title) + "</title></head>"
               + "<body style=\"margin:0;padding:0;font-family:Arial,sans-serif;background:#f9fafb\">"
               + "<div style=\"max-width:600px;margin:20px auto;background:#fff;border-radius:12px;padding:20px 40px;"
               + "color:#505050;font-size:16px;text-align:center\">"
               + "<h2 style=\"color:#009fe3\">" + esc(title) + "</h2>" + content
               + "<p style=\"font-size:12px;color:#aaa\">Kadampa Booking System</p></div></body></html>";
    }

    /** HTML-escapes a value typed by somebody else — a name, a device label, a user agent. */
    static String esc(String value) {
        if (value == null)
            return "";
        // Format characters (bidi overrides, zero-width) dropped first: a name or a device label must not be able to
        // reorder what the approver reads
        value = value.replaceAll("\\p{Cf}", "");
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray())
            switch (c) {
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                case '&': escaped.append("&amp;"); break;
                case '"': escaped.append("&quot;"); break;
                case '\'': escaped.append("&#39;"); break;
                default: escaped.append(c);
            }
        return escaped.toString();
    }

    private static Future<Void> send(String to, String subject, String body) {
        if (to == null || to.isBlank())
            return Future.succeededFuture();
        MailMessage message = MailMessage.create(MAIL_FROM, to, subject, body);
        ModalityContext context = new ModalityContext(1 /* default organizationId, as for the magic-link mails */, null, null, null);
        return MailService.sendMail(new ModalityMailMessage(message, context, MAIL_FROM_NAME))
            .<Void>map(ignored -> null)
            .recover(e -> {
                Console.log(LOG_PREFIX + "⚠️ A second-factor notice could not be sent (" + e.getClass().getSimpleName() + ")");
                return Future.succeededFuture();
            });
    }

    private static String string(QueryResult result, int column) {
        return string(result, 0, column);
    }

    private static String string(QueryResult result, int row, int column) {
        Object value = result.getValue(row, column);
        return value == null ? null : value.toString();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value.trim();
    }
}

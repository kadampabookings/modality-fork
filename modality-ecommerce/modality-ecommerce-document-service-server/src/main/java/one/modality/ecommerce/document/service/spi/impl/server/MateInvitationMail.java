package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Batch;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import one.modality.base.server.mail.LocalizedMailTemplate;

/**
 * The email that tells somebody a room booker is holding a bed for them, and hands them the link that
 * joins it (room-mate plan Part C, the "they'll book themselves" arm).
 *
 * <p><b>Written here, not through MailService.</b> The ordinary path inserts a mail row and nothing else,
 * and this operation has to tie three rows together atomically: the message, its recipient, and the
 * record on the invite token saying it was sent and to which roommate slot. One
 * {@code executeSubmitBatch} joined by a generated-key reference does that, and gives us the mail's id,
 * which {@code MailService.sendMail} does not hand back. Same shape as {@code MemberMail.write}, for the
 * same reason the front-office write-authorization plan gives: the server writes the mail as part of the
 * operation it is recording, so there is no window in which one landed without the other.
 *
 * <p><b>What the caller does not choose.</b> The link is built on OUR configured origin, never on
 * anything that arrived with the request — the body carries a bearer token for a bed, so an
 * attacker-chosen origin would be handed that capability by the invitee's own mail client. The sending
 * account is fixed. The prose is a bundled template (English today; the loader falls back to it
 * for every other language until the six translations land). What the booker supplies is the address
 * and, through their own booking, the names — and the names are escaped, because a booking form's free
 * text must not become markup in a message we send under our own name.
 *
 * <p><b>Metered.</b> Nothing else bounds this: the bus has no throttling, the free-bed gate stays
 * satisfied until somebody actually books, and every call mints another live token. Without a limit a
 * signed-in booker with one shared-room booking could post branded mail from our own sending domain to
 * any address, as often as they liked, and leave a trail of capabilities for one bed. Same shape as the
 * guest booking-recovery throttle, for the reason its comment gives.
 *
 * <p><b>No origin configured, no invitation.</b> It is skipped and said so, rather than sending a mail
 * whose only button leads nowhere: the cart still offers the booker the link to copy, so a missing
 * invitation is recoverable where a misleading one is not.
 */
final class MateInvitationMail {

    private static final String LOG_PREFIX = "[MateInvite] ";
    private static final String CONFIG_PATH = "modality.ecommerce.document.service";
    private static final String MAIL_FROM = "kbs@kadampa.net";
    private static final String MAIL_FROM_NAME = "Kadampa Booking System";
    /** The same account the other KBS3 senders use until mail accounts are resolved per organisation. */
    private static final int MAIL_ACCOUNT_ID = 27;
    /** mail.subject is varchar(255); recipient.name is varchar(91); recipient.email is varchar(127). */
    private static final int MAX_SUBJECT = 255, MAX_NAME = 91, MAX_EMAIL = 127;
    /** Longer than any name this greeting needs, and short enough not to be a paragraph. */
    private static final int MAX_PRINTED_NAME = 40;

    /**
     * Loaded on first use rather than at class-load.
     *
     * The loader reaches for a WebFX resource provider, which is a runtime plugin — so loading it from a
     * static initialiser makes the whole class fail to initialise wherever that plugin is absent, and
     * takes the pure parts (what may be printed, how often it may be sent) down with it. Those two are
     * the security rules worth asserting, and they should not need a server to assert.
     */
    private static LocalizedMailTemplate mail;

    private static synchronized LocalizedMailTemplate mail() {
        if (mail == null)
            mail = LocalizedMailTemplate.load("RoommateInviteMailBody", "RoommateInviteMailMessages",
                MateInvitationMail.class);
        return mail;
    }

    private static final String MAIL_SQL =
        "insert into mail (account_id, out, subject, content, from_name, from_email)" +
        " values ($1, true, $2, $3, $4, $5) returning id";

    private static final String RECIPIENT_SQL =
        "insert into recipient (mail_id, name, email, \"to\", cc, bcc, ok)" +
        " values ($1, $2, $3, true, false, false, false)";

    /**
     * Records on the token that it was emailed, for which slot, and in which message — the row the cart
     * reads to tell the booker what was sent. Keyed by the token's hash because that is what identifies
     * the row; the raw token is never stored.
     */
    private static final String RECORD_SQL =
        "update mate_invite_token set invited_mate_slot = $1, invited_date = now(), invite_mail_id = $2" +
        " where token_hash = $3";

    // One invitation per address, and one per slot of a booking, per window. Two axes because they
    // answer different abuses: the address cap stops a victim's inbox being filled, the slot cap stops
    // one bed accumulating capabilities. In memory, like the recovery-mail throttle: the server runs as
    // one task and a restart merely resets it. Capped so a spray of made-up addresses cannot grow it
    // without bound; evicting a real entry only lets that address be written to once more.
    private static final long INVITE_INTERVAL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final int INVITE_TRACKED_KEYS = 10_000;
    private static final Map<String, Long> LAST_INVITE = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > INVITE_TRACKED_KEYS;
        }
    };

    /** Whether an invitation may go out for this address and this slot now; if so, both windows start. */
    static boolean maySend(String email, Object documentId, int mateSlot) {
        return maySend(email, documentId, mateSlot, System.currentTimeMillis());
    }

    static synchronized boolean maySend(String email, Object documentId, int mateSlot, long nowMillis) {
        String byAddress = "to:" + (email == null ? "" : email.trim().toLowerCase());
        String bySlot = "slot:" + documentId + '/' + mateSlot;
        // Both windows are tested before either is started, so a refusal on one does not silently
        // consume the other and leave the booker a send short with nothing sent.
        if (withinWindow(byAddress, nowMillis) || withinWindow(bySlot, nowMillis))
            return false;
        LAST_INVITE.put(byAddress, nowMillis);
        LAST_INVITE.put(bySlot, nowMillis);
        return true;
    }

    private static boolean withinWindow(String key, long nowMillis) {
        Long last = LAST_INVITE.get(key);
        return last != null && nowMillis - last < INVITE_INTERVAL_MILLIS;
    }

    private MateInvitationMail() { }

    /**
     * Composes and enqueues one invitation, and records it against the token.
     *
     * @param tokenHash  the minted token's hash — identifies the row to record against
     * @param inviteLink the complete URL, already built on the configured origin
     * @param mateSlot   which of the owner room line's seven name slots this is for, 1-based
     * @param email      where to write, as the booker typed it
     * @param mateName   the roommate's name as the booking records it
     * @param bookerName the room booker's name, as the message names them
     * @param eventName  the event, for the one line that says what this is about
     * @param lang       the language to write in; English when we have no better guess
     */
    static Future<Void> send(String tokenHash, String inviteLink, int mateSlot, String email,
                             String mateName, String bookerName, String eventName, String lang) {
        String body = mail().renderBody(lang)
            .replace("[inviteLink]", inviteLink)
            .replace("[mateName]", nameOrNeutral(mateName, mail().getMessage(lang, "neutralMateName")))
            .replace("[bookerName]", nameOrNeutral(bookerName, mail().getMessage(lang, "neutralBookerName")))
            // The event's name is ours, not a booker's free text, so it is escaped and printed as it is.
            .replace("[eventName]", escapeHtml(eventName));

        SubmitArgument mail = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            // " returning id" with setReturnGeneratedKeys is what the two references below resolve
            // against; without the pair, neither the recipient nor the record lands.
            .setStatement(MAIL_SQL)
            .setParameters(MAIL_ACCOUNT_ID, cut(mail().renderSubject(lang), MAX_SUBJECT), body,
                MAIL_FROM_NAME, MAIL_FROM)
            .setReturnGeneratedKeys(true)
            .build();
        SubmitArgument recipient = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(RECIPIENT_SQL)
            // No person_id: the invitee is a stranger to this system and may never become one. The
            // address is theirs and theirs alone, and it is written here exactly once.
            .setParameters(new GeneratedKeyReference(0), cut(mateName, MAX_NAME), cut(email, MAX_EMAIL))
            .build();
        SubmitArgument record = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(RECORD_SQL)
            .setParameters(mateSlot, new GeneratedKeyReference(0), tokenHash)
            .build();

        return SubmitService.executeSubmitBatch(
                new Batch<>(new SubmitArgument[] { mail, recipient, record }))
            .map(ignored -> null);
    }

    /**
     * The front office this invitation points at, or null when none is configured — in which case no
     * invitation is sent at all.
     */
    static String configuredFrontOfficeOrigin() {
        Config config = ConfigLoader.getRootConfig().childConfigAt(CONFIG_PATH);
        String origin = config == null ? null : config.getString("frontofficeBaseUrl");
        if (origin == null || origin.isBlank() || origin.contains("${")) {
            Console.log("⚠️ " + LOG_PREFIX + "No invitation sent: "
                        + CONFIG_PATH + ".frontofficeBaseUrl is unset or unresolved, so the link would lead"
                        + " nowhere. The cart still offers the booker the link to copy.");
            return null;
        }
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }

    /**
     * The link the invitee opens: the same shape the front office builds for the copy-link, so one route
     * cannot drift from the other.
     */
    static String inviteLink(String origin, Object eventId, String rawToken) {
        return origin + "/book-event/" + eventId + "/share/" + rawToken;
    }

    /**
     * A name as this message may print it, or a neutral word when what the booking holds is not one.
     *
     * Escaping stops markup; it does not stop PROSE. The name is free text on the booker's own line, and
     * this message goes out under our name to an address its subject never chose — so ninety characters
     * of it are an invitation to write "https://kbs-refund.example/claim" and have a mail client render
     * it as a link beside our branding. Anything carrying a scheme, an at-sign or something
     * domain-shaped is therefore not printed at all, and a long one is cut: a booking whose roommate is
     * genuinely called that is better greeted impersonally than used as a billboard.
     */
    static String nameOrNeutral(String value, String neutral) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > MAX_PRINTED_NAME)
            return name.isEmpty() ? neutral : neutral;
        String lower = name.toLowerCase();
        if (lower.contains("://") || lower.contains("www.") || lower.indexOf('@') >= 0
            || lower.matches(".*\\.[a-z]{2,}.*"))
            return neutral;
        return escapeHtml(name);
    }

    /**
     * Names reach this message from a booking form, so they are somebody's free text. Escaped rather
     * than trusted: this mail goes out under our own name, to an address its subject never chose.
     */
    private static String escapeHtml(String value) {
        if (value == null)
            return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

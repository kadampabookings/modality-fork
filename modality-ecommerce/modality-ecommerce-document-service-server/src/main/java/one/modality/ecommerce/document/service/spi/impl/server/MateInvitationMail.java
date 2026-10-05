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
 * account is fixed. The prose is a bundled template in the seven languages the front office ships,
 * with English as the fallback for anything else. What the booker supplies is the address
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
                             String mateName, String bookerName, String eventName, String roomName, String lang) {
        String body = mail().renderBody(lang)
            .replace("[inviteLink]", inviteLink)
            .replace("[mateName]", MateMails.nameOrNeutral(mateName, mail().getMessage(lang, "neutralMateName")))
            .replace("[bookerName]", MateMails.nameOrNeutral(bookerName, mail().getMessage(lang, "neutralBookerName")))
            // The event's name is ours, not a booker's free text, so it is escaped and printed as it is.
            .replace("[eventName]", MateMails.escapeHtml(eventName))
            // And the room's name, on the same terms: typed in the back office, so it is ours — but
            // escaped all the same, because "ours" describes who typed it and not what they typed.
            // It is what the booker chose and what the invitee will be shown on arrival, which is the
            // point of naming it: KBS cannot tell a double from a twin (capacity counts people, and no
            // column counts beds), so it prints the name rather than inferring the shape.
            .replace("[roomName]", MateMails.escapeHtml(roomName));

        SubmitArgument[] message = MateMails.mailAndRecipient(
            // No person: the invitee is a stranger to this system and may never become one.
            mail().renderSubject(lang), body, mateName, email, null);
        SubmitArgument record = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(RECORD_SQL)
            // The generated key is the mail's, from the first statement of the pair above.
            .setParameters(mateSlot, new GeneratedKeyReference(0), tokenHash)
            .build();

        return SubmitService.executeSubmitBatch(
                new Batch<>(new SubmitArgument[] { message[0], message[1], record }))
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

}

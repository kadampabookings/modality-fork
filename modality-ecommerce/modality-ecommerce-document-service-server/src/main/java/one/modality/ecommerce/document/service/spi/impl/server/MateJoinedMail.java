package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitService;
import one.modality.base.server.mail.LocalizedMailTemplate;

/**
 * The note that tells a room booker somebody has taken a bed in their room (room-mate plan Part C).
 *
 * <p>Until this, nothing told them. The cart and the orders page start saying every bed is booked, which
 * answers the question for a booker who looks — and a link used days later is used by somebody who is
 * not looking. The two are kept together on purpose (Bruno): the page answers "has anyone booked yet?"
 * when they wonder, and this catches the moment they would otherwise miss.
 *
 * <p><b>Both routes.</b> Sent whether the link was emailed by us or copied by the booker themselves. The
 * token records which (V0119's {@code invited_date}), but the booker wants to know either way, and the
 * copy-link route has never told them anything at all.
 *
 * <p><b>Metered, like the invitation.</b> One mail per successful link is normally bounded by the
 * room's capacity, but that gate treats a room item with no capacity as always having a bed, and a
 * cancelled mate line frees one again — so the bound is only as good as the data. A window per room
 * closes it, for the same reason the invitation has one: branded mail from our own domain, to an
 * address the sender need not even know, carrying forty characters of their own prose.
 *
 * <p><b>Best effort, like its neighbours.</b> It hangs off the same chain as {@code recordFirstUse} and
 * {@code stampOwnerName}, where a failure is logged and the link still stands. A bed that is taken is
 * taken; failing to mention it must never undo that.
 */
final class MateJoinedMail {

    private static LocalizedMailTemplate mail;

    /** Loaded on first use, for the reason {@link MateInvitationMail} gives: the loader needs a plugin. */
    private static synchronized LocalizedMailTemplate mail() {
        if (mail == null)
            mail = LocalizedMailTemplate.load("MateJoinedMailBody", "MateJoinedMailMessages",
                MateJoinedMail.class);
        return mail;
    }

    // One note per room per window, which is about a room filling up rather than about one address:
    // the recipient is always the same person, and what varies is how often somebody joins.
    private static final long JOIN_INTERVAL_MILLIS = Duration.ofMinutes(2).toMillis();
    private static final int TRACKED_ROOMS = 10_000;
    private static final Map<String, Long> LAST_NOTE = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > TRACKED_ROOMS;
        }
    };

    /** Whether a note may go out for this room now; if so, its window starts. */
    static boolean maySend(Object ownerDocumentLineId) {
        return maySend(ownerDocumentLineId, System.currentTimeMillis());
    }

    static synchronized boolean maySend(Object ownerDocumentLineId, long nowMillis) {
        String key = String.valueOf(ownerDocumentLineId);
        Long last = LAST_NOTE.get(key);
        if (last != null && nowMillis - last < JOIN_INTERVAL_MILLIS)
            return false;
        LAST_NOTE.put(key, nowMillis);
        return true;
    }

    private MateJoinedMail() { }

    /**
     * Enqueues the note, or does nothing when there is nobody to write to.
     *
     * @param bookerName  the room booker, as the message greets them
     * @param bookerEmail where to write; blank when the booking has no reachable address
     * @param mateName    who took the bed — the point of the message
     * @param eventName   the event, so a booker with two rooms knows which
     * @param lang        the booker's language; English when we have no better guess
     */
    static Future<Void> send(String bookerName, String bookerEmail, Object bookerPersonId,
                             String mateName, String eventName, String lang) {
        if (bookerEmail == null || bookerEmail.isBlank())
            // A guest booking with no address on it, or a person row with neither an email nor an
            // account login. Nothing to do, and nothing wrong: the cart still says it.
            return Future.succeededFuture();
        String body = mail().renderBody(lang)
            .replace("[mateName]", MateMails.nameOrNeutral(mateName, mail().getMessage(lang, "neutralMateName")))
            // The booker's own name, printed back to the booker. Escaped, but not put through the
            // name-shape test the mate's name gets: nobody can phish themselves, and that test would
            // replace a real name like "St.John" with "you" for no gain. The mate's name is the other
            // way round — they control it, it is printed to somebody else, and so it is tested.
            // Their own name, printed back to them: escaped, but not put through the name-shape test
            // the mate's name gets. Nobody can phish themselves, and that test would turn a real name
            // like "St.John" into a neutral word for no gain. Neutral only when there is no name at
            // all, so that a person row with neither first, last nor display name is not greeted
            // "Dear ,".
            .replace("[bookerName]", bookerName == null || bookerName.isBlank()
                ? MateMails.escapeHtml(mail().getMessage(lang, "neutralBookerName"))
                : MateMails.escapeHtml(bookerName))
            // The event's name is ours, not somebody's free text, so it is escaped and printed as it is.
            .replace("[eventName]", MateMails.escapeHtml(eventName));
        SubmitArgument[] message = MateMails.mailAndRecipient(
            // The booker IS somebody we know, so their recipient row says so — without it the row is
            // invisible to any person-keyed lookup, which is how a stored address escapes an erasure.
            mail().renderSubject(lang), body, bookerName, bookerEmail.trim(), bookerPersonId);
        return SubmitService.executeSubmitBatch(new Batch<>(message)).map(ignored -> null);
    }
}

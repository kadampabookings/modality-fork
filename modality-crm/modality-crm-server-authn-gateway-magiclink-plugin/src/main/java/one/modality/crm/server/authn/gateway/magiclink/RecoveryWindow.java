package one.modality.crm.server.authn.gateway.magiclink;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.orm.domainmodel.DataSourceModel;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.MagicLink;
import one.modality.base.shared.entities.MagicLinkType;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The short window after a tab redeems an emailed sign-in link or code, during which THAT tab may set a new
 * password without naming the old one — the one exception to "a password change proves the current password".
 *
 * <h3>What opens it, and for whom</h3>
 *
 * <p>Redeeming a LOGIN link or its code proves the mailbox, which is what somebody who has forgotten their
 * password has instead. The redemption stamps the link with the redeeming tab's run id, and this class
 * finds the window from that stamp. Three things must all hold:
 * <ul>
 *   <li>the link was redeemed by this caller's run id, and is a LOGIN link — never a booking-access link,
 *       which lives a year, is multi-use and is routinely forwarded, and never a support pass;</li>
 *   <li>it was redeemed within {@link #LENGTH};</li>
 *   <li>the caller is signed in, not as a support view, as the very account the link was for. The run id is
 *       chosen by the client, so on its own it would let a session that learned somebody else's run id
 *       borrow their recovery.</li>
 * </ul>
 * Anything else is "no window", deliberately one answer: the caller learns nothing about which it was.
 *
 * <h3>Used once</h3>
 *
 * <p>Setting a password spends the window ({@link #claim}). Otherwise a tab left open after a reset would
 * let whoever reached it next set another password without knowing the one just chosen, for the rest of
 * the fifteen minutes. Held in memory rather than in the table: the column that would record it,
 * {@code usageRunId}, is also how guest bookings recognise a redeemed address later in the same tab, so it
 * cannot be cleared, and a new column is a migration for a fifteen-minute fact. The cost of memory is that
 * a redeploy inside the window forgets it — the window then reopens for its remaining minutes, to the same
 * tab and account only, which is where it stood before this existed.
 */
final class RecoveryWindow {

    /**
     * Fifteen minutes because it is the lifetime of the emailed code itself: long enough to finish choosing a
     * password after signing in with it, short enough that a tab left open afterwards is not a standing
     * licence. The front office tells people "15 minutes" in its copy; change both together.
     */
    static final Duration LENGTH = Duration.ofMinutes(15);

    /** Links whose window has been spent, by id, with when — dropped once their window would have closed anyway. */
    private static final Map<String, Instant> SPENT = new ConcurrentHashMap<>();

    /** An open window: the link that opened it, the account it is for, and when it closes. */
    record Open(MagicLink magicLink, ModalityUserPrincipal target, Instant closesAt) {}

    private RecoveryWindow() {}

    /**
     * This caller's open window, or a future of null when there is none.
     *
     * <p>MUST be called on the caller's thread: it reads the run id and principal before its first async step,
     * because {@link ThreadLocalStateHolder} is restored once the synchronous part of the call returns.
     */
    static Future<Open> findForCaller(DataSourceModel dataSourceModel) {
        String usageRunId = ThreadLocalStateHolder.getRunId();
        Object caller = ThreadLocalStateHolder.getUserId();
        // A support view redeems a grant of its own, which stamps this same usageRunId onto that row — so
        // without this the agent's tab would satisfy "you recently redeemed a link" on a row whose email is
        // the CUSTOMER's, and could reset their password from a session that is supposed to be read-only.
        if (usageRunId == null || !(caller instanceof ModalityUserPrincipal callerPrincipal) || callerPrincipal.isSupportView())
            return Future.succeededFuture(null);
        Object callerAccountId = callerPrincipal.getUserAccountId();
        if (callerAccountId == null)
            return Future.succeededFuture(null);
        // Scoped to LOGIN rows IN THE QUERY, not just in the check below: one tab (one runId) can
        // legitimately stamp usageRunId onto more than one row — the account-creation-from-booking flows
        // mark BOOKING_ACCESS links as used with the same runId — and an unordered `limit 1` over that set
        // is a coin toss. The column is NOT NULL DEFAULT 'LOGIN', so the filter cannot miss legacy rows, and
        // `order by id desc` keeps the answer deterministic even so.
        return EntityStore.create(dataSourceModel)
            .<MagicLink>executeQuery("select email,linkType,usageDate from MagicLink where usageRunId=$1 and linkType=$2 order by id desc limit 1",
                usageRunId, MagicLinkType.LOGIN.name())
            .compose(magicLinks -> {
                if (magicLinks.isEmpty())
                    return Future.succeededFuture(null);
                MagicLink magicLink = magicLinks.get(0);
                // Belt and braces for the query filter: stated as an allowlist so a support pass of either
                // flavour (or any future type) is refused without this line needing to know about it.
                // linkType is selected explicitly because an unselected field reads as null, which
                // getLinkType() would charitably interpret as LOGIN.
                if (magicLink.getLinkType() != MagicLinkType.LOGIN)
                    return Future.succeededFuture(null);
                Instant usedAt = magicLink.getUsageDate();
                if (usedAt == null)
                    return Future.succeededFuture(null);
                Instant closesAt = usedAt.plus(LENGTH);
                if (!Instant.now().isBefore(closesAt) || isSpent(magicLink))
                    return Future.succeededFuture(null);
                return MagicLinkService.loadUserPersonFromMagicLink(magicLink)
                    .map(userPerson -> {
                        if (userPerson == null)
                            return null;
                        ModalityUserPrincipal target = new ModalityUserPrincipal(userPerson.getPrimaryKey(), userPerson.getForeignEntity("frontendAccount").getPrimaryKey());
                        // Compared as numbers because a principal decoded from a token carries small ids as
                        // Byte or Short while the entity's key is an Integer; equals() across them is false.
                        Object targetAccountId = target.getUserAccountId();
                        if (targetAccountId == null || !Numbers.identicalObjectsOrNumberValues(targetAccountId, callerAccountId))
                            return null;
                        return new Open(magicLink, target, closesAt);
                    });
            });
    }

    /**
     * How long this caller's window has left, in milliseconds — 0 when there is none. What the client asks
     * right after an emailed sign-in, so it can offer the password form without the old-password field.
     * Same threading rule as {@link #findForCaller}.
     */
    static Future<Integer> remainingMillisForCaller(DataSourceModel dataSourceModel) {
        return findForCaller(dataSourceModel)
            .map(open -> open == null ? 0 : (int) Math.max(0, Duration.between(Instant.now(), open.closesAt()).toMillis()));
    }

    /**
     * Spends the window, atomically: true for the one caller that may now set a password, false for any
     * other — a second tab of the same run, or a second click racing the first.
     */
    static boolean claim(MagicLink magicLink) {
        Instant now = Instant.now();
        // Nothing here can outlive LENGTH usefully: once the window would have closed, the date check refuses anyway.
        SPENT.values().removeIf(spentAt -> spentAt.plus(LENGTH).isBefore(now));
        return SPENT.putIfAbsent(keyOf(magicLink), now) == null;
    }

    /** Gives the window back after a password set that did not happen, so the person can try again. */
    static void release(MagicLink magicLink) {
        SPENT.remove(keyOf(magicLink));
    }

    private static boolean isSpent(MagicLink magicLink) {
        return SPENT.containsKey(keyOf(magicLink));
    }

    private static String keyOf(MagicLink magicLink) {
        return String.valueOf(magicLink.getPrimaryKey());
    }
}

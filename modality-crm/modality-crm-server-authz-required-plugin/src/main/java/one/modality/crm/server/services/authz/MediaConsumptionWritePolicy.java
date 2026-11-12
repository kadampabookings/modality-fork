package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.Set;

/**
 * A member records their own viewing: a {@code MediaConsumption} row is written by the account whose attendance it
 * is, or by the back office.
 *
 * <p><b>The hole (found 2026-11-10, from the production write census).</b> The front office records every play,
 * download and livestream view as a MediaConsumption row through the generic submit
 * (use-media-consumption-recorder.ts, AudioTrackRow.tsx), and no rule covered the table: it is not among the
 * per-entity rule's entities, the deny list or another row rule. So any client — signed in, signed out or anonymous —
 * could insert a row against anybody's attendance, or rewrite the duration of anybody's row by id (sequential). The
 * census showed the signed-out case live: {@code MediaConsumption UPDATE [durationMillis] by logged-out}, a player
 * still sending its heartbeat after its session lapsed. Nothing is READ through this, and media access comes from
 * orders, not from these rows, so the cost is the integrity of what the back office reads off them: the consumption
 * dashboard, the "still watching" audience, the viewing history on a booking. Each row is also a fact about an
 * identified person — what they watched, when, for how long.
 *
 * <p><b>The line, measured on staging before it was drawn (2026-11-10).</b> Of 411,867 rows, 411,810 trace to an
 * account through the two paths the front office reads its attendances by (use-video-sessions.ts,
 * use-audio-tracks.ts): the booking's person is in the account and linked to no other member
 * ({@code accountPerson} null), or the person is linked to a member of the account ({@code accountPerson}). 9,043 take
 * the second path, and every one of those persons also sits in a DIFFERENT account — the booker's — whose front office
 * no longer reads that attendance (linking transfers media). The 57 that trace to no account are old: the newest is
 * from 2026-03-01. So:
 * <ul>
 *   <li><b>A member</b> (any caller without a verified back-office token) inserts a row only on an attendance of
 *       their account, with only the fields the front office sets, and afterwards changes only its duration. Nothing
 *       else: no other field, no delete.</li>
 *   <li><b>The back office</b> (a verified back-office token) is not limited here: it deletes rows with a session
 *       or a media.</li>
 *   <li><b>No person to act as</b> — anonymous, signed out, the support view — records nothing. This is the one
 *       real cost: a player whose session lapses mid-livestream loses the minutes it would have added after that.</li>
 * </ul>
 *
 * <p>The verified audience, never the client's claim: see {@link BackofficeOnlyWritePolicy}.
 *
 * <p><b>Ownership is read from the database, never from the payload.</b> An insert names its attendance, which is
 * looked up; an update is judged on the stored row's attendance, looked up by the row's id. A target or attendance
 * the parser could not read is refused, since that is the statement that would reach someone else's row.
 *
 * <p><b>What this does not close.</b> A member can still insert a row on their own attendance for a session or a
 * media other than the one it is for, or an inflated duration on their own row: their own figures, not somebody
 * else's.
 *
 * <p><b>Observed first.</b> Row rules are enforced the moment they deploy; this ships logging "WOULD REFUSE a media
 * consumption write" and is switched on after a census. Its REFUSED line will need a CloudWatch filter of its own.
 *
 * <p>Cost: one or two reads by primary key per write, five joins each (~6 ms cold on staging). The busiest path is
 * the heartbeat, one update a minute per playing tab.
 *
 * @author Claude Code
 */
final class MediaConsumptionWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** False while it observes: decisions are logged ("WOULD REFUSE a media consumption write") but nothing is refused. */
    static final boolean ENFORCING = false;

    static final String ENTITY = "MediaConsumption";

    /** Told to the caller. */
    static final String REFUSED = "Viewing is recorded only by the account it belongs to";

    /** The fields the front office sets on a new row (use-media-consumption-recorder.ts, AudioTrackRow.tsx). */
    static final Set<String> MEMBER_INSERT_FIELDS = Set.of(
        "scheduledItem", "media", "attendance", "played", "downloaded", "livestreamed", "durationMillis");
    /** And afterwards: the heartbeat and the final update. */
    static final Set<String> MEMBER_UPDATE_FIELDS = Set.of("durationMillis");

    /** The reads ownership needs. An interface so the check can answer them without a database. */
    interface ConsumptionLookup {
        /** Whether this attendance's booking belongs to this account, by either path the front office reads. */
        Future<Boolean> attendanceInAccount(Object attendanceId, Object accountId);

        /** Whether this row's attendance belongs to this account. False when there is no such row. */
        Future<Boolean> consumptionInAccount(Object consumptionId, Object accountId);
    }

    static final ConsumptionLookup DATABASE_LOOKUP = new ConsumptionLookup() {
        @Override
        public Future<Boolean> attendanceInAccount(Object attendanceId, Object accountId) {
            return inAccount("Attendance", "documentLine.document.person", attendanceId, accountId);
        }

        @Override
        public Future<Boolean> consumptionInAccount(Object consumptionId, Object accountId) {
            return inAccount("MediaConsumption", "attendance.documentLine.document.person", consumptionId, accountId);
        }

        // Two queries rather than one OR: a dotted path outside SELECT is an inner join, so an OR reaching through
        // accountPerson would drop every row whose person is linked to nobody - the common case. The direct path is
        // asked first because it is the answer for all but ~2% of rows. The seam hands ids over as strings, which the
        // driver refuses for an integer column; see primaryKeyOf.
        private Future<Boolean> inAccount(String entityName, String personPath, Object id, Object accountId) {
            Object rowId = ProtectedEntityWritesJob.primaryKeyOf(id), account = ProtectedEntityWritesJob.primaryKeyOf(accountId);
            return exists("select id from " + entityName + " where id=$1 and " + personPath
                          + ".(frontendAccount=$2 and accountPerson=null)", rowId, account)
                .compose(direct -> direct ? Future.succeededFuture(true)
                    : exists("select id from " + entityName + " where id=$1 and " + personPath
                             + ".accountPerson.frontendAccount=$2", rowId, account));
        }

        private Future<Boolean> exists(String dql, Object... parameters) {
            return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel())
                .executeQuery(dql, parameters)
                .map(rows -> !rows.isEmpty());
        }
    };

    @Override
    public Future<String> refusalReason(WriteRequest write) {
        if (write != null && !ENTITY.equals(write.entityName()))
            return Future.succeededFuture(null);
        // Read on the caller's thread: the guard asks every row rule synchronously, and the state is gone after the
        // first async hop.
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        boolean backoffice = Boolean.TRUE.equals(StateAccessor.getVerifiedBackoffice(state));
        return judge(write, GrantTableWritePolicy.callerPersonId(userId), callerAccountId(userId), backoffice,
            DATABASE_LOOKUP)
            .recover(failure -> Future.succeededFuture("could not check it: " + failure.getMessage()))
            .map(reason -> {
                if (reason == null)
                    return null;
                // The census line. Account identities and entity names, no row ids and no durations.
                Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " a media consumption write: "
                            + (write == null ? "?" : write.verb() + " " + write.entityName()) + " by " + CallerLabel.of(userId)
                            + (backoffice ? " (back office)" : "") + " — " + reason);
                return ENFORCING ? REFUSED : null;
            });
    }

    /** The account a caller acts for, or null; the support view acts for nobody (see callerPersonId). */
    static Object callerAccountId(Object userId) {
        if (!(userId instanceof ModalityUserPrincipal principal) || principal.isSupportView())
            return null;
        return principal.getUserAccountId();
    }

    /**
     * The decision: a future of why this write may not run, or of null when it may. Apart from the thread, the
     * switch and the database, so the check can judge any caller against any stored rows.
     */
    static Future<String> judge(WriteRequest write, Object callerPersonId, Object callerAccountId, boolean backoffice,
                                ConsumptionLookup lookup) {
        if (write == null)
            return refuse("the write could not be read");
        if (!ENTITY.equals(write.entityName()))
            return ok();
        if (backoffice && callerPersonId != null)
            return ok();
        if (callerPersonId == null || callerAccountId == null)
            return refuse("the caller has no person to record viewing as");
        switch (write.verb()) {
            case INSERT: {
                if (!onlyFields(write, MEMBER_INSERT_FIELDS))
                    return refuse("records viewing with fields the front office does not set");
                Object attendance = write.writtenValues() == null ? null : write.writtenValues().get("attendance");
                if (!isId(attendance))
                    return refuse("names no attendance it can be judged on");
                return lookup.attendanceInAccount(attendance, callerAccountId)
                    .map(own -> Boolean.TRUE.equals(own) ? null : "records viewing on somebody else's attendance");
            }
            case UPDATE: {
                if (!onlyFields(write, MEMBER_UPDATE_FIELDS))
                    return refuse("changes a field of a viewing record other than its duration");
                if (!isId(write.targetId()))
                    return refuse("names no row it can be judged on");
                return lookup.consumptionInAccount(write.targetId(), callerAccountId)
                    .map(own -> Boolean.TRUE.equals(own) ? null : "changes somebody else's viewing record");
            }
            default:
                return refuse("deletes a viewing record");
        }
    }

    private static boolean onlyFields(WriteRequest write, Set<String> allowed) {
        if (write.writtenFields() == null)
            return false;
        for (String written : write.writtenFields())
            if (!allowed.contains(written))
                return false;
        return true;
    }

    /** A plain stored id: not null, not unresolved, not a reference into the batch. */
    private static boolean isId(Object value) {
        return value != null && value != ProtectedEntityWriteRegistry.UNRESOLVED_VALUE
               && !(value instanceof GeneratedKeyReference);
    }

    private static Future<String> ok() {
        return Future.succeededFuture(null);
    }

    private static Future<String> refuse(String reason) {
        return Future.succeededFuture(reason);
    }
}

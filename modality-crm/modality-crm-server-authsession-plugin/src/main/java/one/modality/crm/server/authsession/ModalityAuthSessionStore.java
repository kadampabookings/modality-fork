package one.modality.crm.server.authsession;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.uuid.Uuid;
import dev.webfx.stack.db.query.QueryArgument;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import dev.webfx.stack.session.token.SessionFamilyStore;
import dev.webfx.stack.session.token.SessionLifetime;
import dev.webfx.stack.session.token.SessionTier;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps each login session's generation counter in the {@code auth_session} table (V0083).
 *
 * <p>Implements the framework's {@link SessionFamilyStore} — the stack knows how to sign a token and how
 * to compare generations, and deliberately does not know where a row could live. This is the half that
 * knows: one row per live session, no tokens stored, and nothing in it that authenticates anybody.
 *
 * <h3>Raw SQL rather than the entity model</h3>
 *
 * <p>{@code auth_session} is a KBS3-only table and the domain model is generated from the KBS2
 * configuration database, so there is no entity class to query through and adding one would mean editing
 * a system elsewhere for a table that system must never touch. These statements are server-internal and
 * parameterised — nothing in them comes from a client — so they are exactly the kind of caller the
 * eventual closing of the raw-SQL door is meant to keep working.
 *
 * <h3>Every statement runs as the SERVER, not as the caller</h3>
 *
 * <p>Renewal happens while handling somebody's message, so the thread carries their state. Left alone,
 * three things would go wrong and one of them silently: a support-view session is restricted to reads, so
 * its own renewal would be refused by the write gate; the statements would be marked as having come from
 * a client, which is precisely the property a future rule will refuse raw SQL on; and the audit actor
 * would name a member for a row they did not ask to be written. Running under an empty state removes all
 * three. Note it must be an EMPTY state and not null — {@code ThreadLocalStateHolder.open(null)} leaves
 * the current state in place rather than clearing it.
 *
 * @author Claude Code
 */
public final class ModalityAuthSessionStore implements SessionFamilyStore {

    /**
     * Inserts the row for a session that has just been established.
     *
     * <p>{@code to_timestamp($5::bigint / 1000.0)} rather than binding a timestamp: epoch milliseconds
     * are what the token carries and what Java computed, and converting in SQL avoids depending on how
     * any particular driver maps a Java time type onto {@code timestamptz}.
     */
    private static final String OPEN_SQL =
        "insert into auth_session (id, person_id, account_id, tier, generation, issued, last_renewed, absolute_expiry)" +
        " values ($1, $2::int, $3::int, $4, 0, now(), now(), to_timestamp($5::bigint / 1000.0))";

    /**
     * Advances the family by one, and reports what the row looked like either way, in one round trip.
     *
     * <p>The UPDATE's condition is evaluated against the LIVE row, which is what makes it atomic: two
     * instances renewing the same family cannot both succeed, because the second one re-checks
     * {@code generation = $2} against the row the first one just wrote. A read-then-write, or a condition
     * evaluated against the statement's snapshot, would let both through and leave one client holding a
     * generation the other had retired — a suspected theft that is really a second browser tab.
     *
     * <p>The SELECT reads the pre-statement snapshot on purpose: when the UPDATE did not fire, that is
     * the state that explains why. It can be stale if another renewal committed in between, which is why
     * {@code presented == snapshot} with no update is reported as undecided rather than guessed at.
     */
    private static final String RENEW_SQL =
        "with renewed as (" +
        "  update auth_session" +
        "     set generation = generation + 1, last_renewed = now()" +
        "   where id = $1 and generation = $2::int and revoked is null and absolute_expiry > now()" +
        "  returning generation, absolute_expiry" +
        ")" +
        " select r.generation                                                     as renewed_generation," +
        "        (extract(epoch from r.absolute_expiry) * 1000)::bigint           as renewed_absolute," +
        "        s.generation                                                     as snapshot_generation," +
        "        (s.revoked is not null)                                          as revoked," +
        "        (s.absolute_expiry <= now())                                     as expired," +
        "        (extract(epoch from (now() - s.last_renewed)) * 1000)::bigint    as millis_since_renewal," +
        "        (extract(epoch from s.absolute_expiry) * 1000)::bigint           as snapshot_absolute" +
        "   from auth_session s left join renewed r on true" +
        "  where s.id = $1";

    // Column order of RENEW_SQL. A raw-SQL QueryResult carries values but no column names, so these are
    // read by position — keep them in step with the SELECT above.
    private static final int RENEWED_GENERATION = 0, RENEWED_ABSOLUTE = 1, SNAPSHOT_GENERATION = 2,
        REVOKED = 3, EXPIRED = 4, MILLIS_SINCE_RENEWAL = 5, SNAPSHOT_ABSOLUTE = 6;

    /**
     * The revocations another instance may have performed, oldest first, in two forms.
     *
     * <p>The WINDOW form reads everything since a moment; the CURSOR form resumes strictly after the row a
     * full page ended on. Both are needed and {@code RevocationPoll} explains why: a window cannot walk
     * through thousands of families revoked by one statement, since they share a timestamp to the
     * microsecond, and a cursor cannot pick up a transaction that committed late behind one.
     *
     * <p>{@code revoked::text} rides along as the cursor's position. Milliseconds would not do: they are a
     * rounding of a microsecond timestamp and can land HALF A MILLISECOND PAST the row they came from,
     * which would skip the rows the cursor exists to resume from. The text form round-trips exactly.
     *
     * <p><b>It goes back in as {@code $1::text::timestamptz}, never {@code $1::timestamptz}.</b> Postgres
     * types a parameter from its cast, and the Vert.x client then refuses to encode anything but the Java
     * type it maps that SQL type to — a String bound to a {@code timestamptz} parameter fails with
     * "can not be coerced to the expected class OffsetDateTime" before the query is sent. Casting through
     * text makes the parameter text, so the stamp is sent as the string it is and parsed by the server
     * that produced it.
     *
     * <p>Ordered by {@code (revoked, id)} — the id breaks the tie that a mass revocation creates, and
     * makes the ordering total, which is what lets the cursor be a position rather than a guess.
     *
     * <p><b>The ORDER BY is qualified, and the stamp column is aliased, and both matter.</b> An
     * unqualified {@code order by revoked} binds to an OUTPUT column of that name before it binds to the
     * table's — so with {@code revoked::text} unaliased in the select list, the rows came back sorted by
     * TEXT: a sequential scan and a sort instead of the index, and an order that is not the one the
     * cursor's {@code (revoked, id)} comparison uses. Postgres trims trailing zeros from a timestamp's
     * text, so the two orders genuinely differ, and a cursor stepping through one while the rows arrive
     * in the other can step over rows. Verified against a real server: qualified, it is an index scan.
     */
    private static final String REVOKED_SINCE_WINDOW_SQL =
        "select id, (extract(epoch from revoked) * 1000)::bigint as revoked_millis, revoked::text as revoked_stamp" +
        "  from auth_session" +
        " where revoked is not null and revoked > to_timestamp($1::bigint / 1000.0)" +
        " order by auth_session.revoked, auth_session.id" +
        " limit " + REVOCATION_PAGE_SIZE;

    private static final String REVOKED_AFTER_CURSOR_SQL =
        "select id, (extract(epoch from revoked) * 1000)::bigint as revoked_millis, revoked::text as revoked_stamp" +
        "  from auth_session" +
        " where revoked is not null and (revoked, id) > ($1::text::timestamptz, $2)" +
        " order by auth_session.revoked, auth_session.id" +
        " limit " + REVOCATION_PAGE_SIZE;

    // Column order of the two statements above — a raw-SQL QueryResult carries values but no names.
    private static final int REVOKED_FAMILY_ID = 0, REVOKED_MILLIS = 1, REVOKED_STAMP = 2;

    private static final String REVOKE_SQL =
        "update auth_session set revoked = now(), revoked_reason = $2 where id = $1 and revoked is null";

    /**
     * Retention is deletion, not history. A day's grace keeps a row around long enough to explain a
     * session that has just ended, and no longer: this table records when each member was online, which
     * is personal data with no purpose once the session it describes is over.
     *
     * <p>This interval is also what a restarted instance can re-learn: it backfills the prompt-refusal
     * set from these rows, so {@code RevokedFamilies.RETENTION_MILLIS} is held to the same day. Lengthen
     * one without the other and two instances disagree about which tokens to refuse on sight.
     *
     * <p>Revoked rows are swept on the same clock rather than being kept until their original bound —
     * which for a member would be a year. Nothing needs them: a family that has been deleted and one that
     * has been revoked both answer "this session is over", so removing them early costs no enforcement
     * and holds a year less personal data. It also collects the rare orphan — a family opened for a login
     * whose token then failed to mint, revoked immediately by {@code SessionTokenService}.
     */
    /**
     * Counted separately, because the submit path cannot tell us how many rows went.
     *
     * <p>{@code SubmitResult.getRowCount()} is not rows-affected: {@code VertxSqlUtil.toWebFxSubmitResult}
     * derives it by walking the RowSet CHAIN, which for a single statement has one element whatever the
     * statement did. Reporting that as a deletion count made the sweep claim it had removed a row every
     * hour while removing nothing — the precise failure a retention log exists to rule out, since it says
     * "personal data is being deleted" whether or not any is.
     *
     * <p>Counting first costs one extra round trip an hour on rows nothing reads. The alternative — a
     * data-modifying CTE read back through the QUERY path — would report honestly but would put a DELETE
     * through the door that has no write gate on it, which is not a habit worth forming in a codebase
     * trying to close that door.
     */
    private static final String COUNT_EXPIRED_SQL =
        "select count(*) from auth_session where absolute_expiry < now() - interval '1 day'" +
        "    or (revoked is not null and revoked < now() - interval '1 day')";

    static final String PURGE_SQL =
        "delete from auth_session where absolute_expiry < now() - interval '1 day'" +
        "    or (revoked is not null and revoked < now() - interval '1 day')";

    @Override
    public Future<String> open(Object principal, SessionTier tier, long absoluteExpiryMillis) {
        String familyId = Uuid.randomUuid();
        Integer personId = personIdOf(principal);
        Integer accountId = accountIdOf(principal);
        return asServer(() -> SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(OPEN_SQL)
                .setParameters(familyId, personId, accountId, tier.code(), absoluteExpiryMillis)
                .build()))
            .map(ignored -> familyId);
    }

    @Override
    public Future<FamilyRenewal> renew(String familyId, int presentedGeneration, long nowMillis) {
        return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(RENEW_SQL)
                .setParameters(familyId, presentedGeneration)
                .build()))
            .map(result -> classify(result, familyId, presentedGeneration));
    }

    private FamilyRenewal classify(QueryResult result, String familyId, int presentedGeneration) {
        // No row: a family this server has never heard of, or one the retention sweep has removed because
        // it was long past its bound. Either way there is no live session behind this token.
        if (result == null || result.getRowCount() == 0)
            return FamilyRenewal.ended();
        Object renewedGeneration = result.getValue(0, RENEWED_GENERATION);
        if (renewedGeneration != null)
            return new FamilyRenewal(Verdict.RENEWED, result.getInt(0, RENEWED_GENERATION, 0),
                longAt(result, RENEWED_ABSOLUTE));
        if (result.getBoolean(0, REVOKED, false) || result.getBoolean(0, EXPIRED, false))
            return FamilyRenewal.ended();
        int snapshotGeneration = result.getInt(0, SNAPSHOT_GENERATION, 0);
        if (presentedGeneration > snapshotGeneration) {
            // Unreachable without the signing key: a generation ahead of the family's own would have had
            // to be minted by this server, and this server never minted it. Refuse rather than reason
            // about it — a token from the future is not a session anyone should be kept in.
            Console.log("🛡 Identity token names a generation ahead of its family — refusing it");
            return FamilyRenewal.ended();
        }
        if (presentedGeneration == snapshotGeneration)
            // The update should have fired, so something committed between this statement's snapshot and
            // its write: another renewal of the same family, from another instance or another tab.
            return FamilyRenewal.undecided();
        // Behind the family. Recent enough to be the losing half of a race the client cannot see — a
        // second tab, or a reply that never arrived — and it is handed the current generation. Old enough
        // and it is what it looks like: a copy of a retired token, in somebody else's hands.
        if (longAt(result, MILLIS_SINCE_RENEWAL) <= SessionLifetime.REUSE_GRACE_MILLIS)
            return new FamilyRenewal(Verdict.CURRENT, snapshotGeneration, longAt(result, SNAPSHOT_ABSOLUTE));
        return FamilyRenewal.reuseDetected();
    }

    @Override
    public Future<Void> revoke(String familyId, String reason) {
        return asServer(() -> SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(REVOKE_SQL)
                .setParameters(familyId, reason)
                .build()))
            .mapEmpty();
    }

    @Override
    public Future<RevocationPage> revokedSince(long sinceMillis, RevocationCursor after) {
        boolean catchingUp = after != null;
        QueryArgumentBuilder builder = new QueryArgumentBuilder()
            .setDataSourceId(dataSourceId())
            .setStatement(catchingUp ? REVOKED_AFTER_CURSOR_SQL : REVOKED_SINCE_WINDOW_SQL)
            // Droppable by construction, and the admission control should treat it that way: this poll
            // runs on every instance every twenty seconds, including through a deploy's reconnection
            // storm, and its own failure path is "refuse these families at renewal instead, for another
            // twenty seconds". Shedding it costs promptness; taking a connection from a member's booking
            // at capacity costs them the booking.
            .setShedWhenBusy(true);
        QueryArgument query = (catchingUp ? builder.setParameters(after.stamp(), after.familyId())
                                          : builder.setParameters(sinceMillis)).build();
        return asServer(() -> QueryService.executeQuery(query))
            .map(ModalityAuthSessionStore::toPage);
    }

    private static RevocationPage toPage(QueryResult result) {
        int rows = result == null ? 0 : result.getRowCount();
        if (rows == 0)
            return RevocationPage.EMPTY;
        List<Revocation> revocations = new ArrayList<>(rows);
        RevocationCursor next = null;
        for (int row = 0; row < rows; row++) {
            String familyId = result.getValue(row, REVOKED_FAMILY_ID);
            if (familyId == null) // a row with no id is not a family anything can be told about
                continue;
            revocations.add(new Revocation(familyId, longAt(result, row, REVOKED_MILLIS)));
            next = new RevocationCursor(result.getValue(row, REVOKED_STAMP), familyId);
        }
        return new RevocationPage(revocations, next);
    }

    /**
     * Removes rows whose session ended long enough ago to be of no further use, and reports how many.
     *
     * <p>Counts before deleting rather than trusting the submit's row count — see {@link #COUNT_EXPIRED_SQL}.
     * The count can be stale by whatever expired in between, which does not matter: the number is for a
     * human watching retention work, and the next sweep collects the remainder an hour later.
     */
    Future<Long> purgeExpired() {
        return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(COUNT_EXPIRED_SQL)
                .build()))
            .compose(result -> {
                long expired = result == null || result.getRowCount() == 0 ? 0 : longAt(result, 0);
                if (expired == 0) // nothing to say, and nothing to do
                    return Future.succeededFuture(0L);
                return asServer(() -> SubmitService.executeSubmit(new SubmitArgumentBuilder()
                        .setDataSourceId(dataSourceId())
                        .setStatement(PURGE_SQL)
                        .build()))
                    .map(ignored -> expired);
            });
    }

    /**
     * Runs a database call with the caller's state removed, so it is the server asking and not them.
     *
     * <p>Read on THIS thread, before the call is handed to the async queue, because that is where the
     * query and submit providers read the caller's state — {@code ThreadLocalStateHolder} is restored as
     * soon as the synchronous part returns, so wrapping anything later would wrap nothing.
     */
    private static <T> T asServer(java.util.function.Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
    }

    private static Object dataSourceId() {
        return DataSourceModelService.getDefaultDataSourceId();
    }

    /**
     * The person behind a principal, or null when there is none.
     *
     * <p>Null is a real case rather than a defect: a guest booker has a principal and no Person row, and
     * their session is worth rotating for exactly the same reasons anyone else's is. Recorded when it can
     * be, so that "sign out everywhere" and offboarding have something to select on, and left null when
     * it cannot.
     */
    private static Integer personIdOf(Object principal) {
        return principal instanceof ModalityUserPrincipal user ? toInteger(user.getUserPersonId()) : null;
    }

    private static Integer accountIdOf(Object principal) {
        return principal instanceof ModalityUserPrincipal user ? toInteger(user.getUserAccountId()) : null;
    }

    private static Integer toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /** A bigint column comes back as whatever the driver chose; read it as a number, not by cast. */
    private static long longAt(QueryResult result, int columnIndex) {
        return longAt(result, 0, columnIndex);
    }

    private static long longAt(QueryResult result, int rowIndex, int columnIndex) {
        Object value = result.getValue(rowIndex, columnIndex);
        return value instanceof Number number ? number.longValue() : 0;
    }
}

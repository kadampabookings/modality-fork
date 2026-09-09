package one.modality.crm.server.authsession;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.uuid.Uuid;
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

    private static final String REVOKE_SQL =
        "update auth_session set revoked = now(), revoked_reason = $2 where id = $1 and revoked is null";

    /**
     * Retention is deletion, not history. A day's grace keeps a row around long enough to explain a
     * session that has just ended, and no longer: this table records when each member was online, which
     * is personal data with no purpose once the session it describes is over.
     *
     * <p>Revoked rows are swept on the same clock rather than being kept until their original bound —
     * which for a member would be a year. Nothing needs them: a family that has been deleted and one that
     * has been revoked both answer "this session is over", so removing them early costs no enforcement
     * and holds a year less personal data. It also collects the rare orphan — a family opened for a login
     * whose token then failed to mint, revoked immediately by {@code SessionTokenService}.
     */
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

    /** Removes rows whose session ended long enough ago to be of no further use. */
    Future<Integer> purgeExpired() {
        return asServer(() -> SubmitService.executeSubmit(new SubmitArgumentBuilder()
                .setDataSourceId(dataSourceId())
                .setStatement(PURGE_SQL)
                .build()))
            .map(submitResult -> submitResult == null ? 0 : submitResult.getRowCount());
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
        Object value = result.getValue(0, columnIndex);
        return value instanceof Number number ? number.longValue() : 0;
    }
}

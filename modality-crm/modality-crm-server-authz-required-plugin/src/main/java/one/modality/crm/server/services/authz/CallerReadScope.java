package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.EntityList;
import dev.webfx.stack.orm.entity.EntityStore;
import one.modality.base.shared.entities.AuthorizationOrganizationAdmin;
import one.modality.base.shared.entities.AuthorizationOrganizationUserAccess;
import one.modality.base.shared.entities.Event;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a back-office caller is granted to READ, flattened into the two arrays a scope predicate binds.
 *
 * <p>The grants themselves are not invented here: {@code authorization_organization_user_access} already holds
 * one row per grant, scoped to an organisation and optionally to a single event or — since V0133 — to an event
 * type. This turns a user's rows into the shape a predicate can use in one indexed comparison:
 *
 * <ul>
 *   <li>a row with no event and no type is <b>organisation-wide</b>, and lands in {@code organizationIds};</li>
 *   <li>a row naming an event lands in {@code eventIds};</li>
 *   <li>a row naming an event type is <b>expanded</b> into the ids of that type's events in that organisation,
 *       and lands in {@code eventIds} too.</li>
 * </ul>
 *
 * <p><b>Why the type rows are expanded rather than left as a type test.</b> {@code event.type} is not a column
 * on {@code document}, so a predicate testing it would have to navigate — and the join injection point refuses
 * a scope condition that navigates, by name. Flattening moves that work here, once per caller, instead of into
 * every booking query in the system.
 *
 * <p><b>Why an organisation-wide grant is NOT expanded.</b> {@code document.organization_id} is NOT NULL, so
 * the whole organisation is one column test. Expanding it would mean carrying every event id of a centre in
 * every query's parameters, which is the cost the array form exists to avoid.
 *
 * <p><b>One query per (type, organisation) pair, not one query with a pair list.</b> A single query would have
 * to be built from the caller's own pairs, so its text would differ per caller and never hit the statement
 * cache; and the obvious shortcut — {@code organization in ?orgs and type in ?types} — is a CROSS PRODUCT that
 * would grant an event of type T in an organisation where only type U was granted. Per-pair queries share one
 * statement text, and a caller usually has none or one.
 *
 * <p><b>{@code read_only} is not consulted.</b> It keeps only the operations marked read-only, so it restricts
 * WRITING; a read-only grant grants reading, which is the whole of its purpose.
 *
 * <p><b>Read synchronously, computed asynchronously.</b> The resolver that binds these values runs on the
 * endpoint thread before the first async hop, where the principal is still known and no query can be awaited —
 * see {@code ServerCallerParameters}. So the scope is computed when a caller's grants are computed (the same
 * trigger, the same TTL, the same invalidation) and only looked up at the endpoint. {@link #peek} returns null
 * when nothing has been computed yet, which is NOT the same as a caller with no grants, and the difference is
 * the caller's to act on.
 *
 * <p><b>The TTL governs recomputation, not the read.</b> {@link #peek} has never checked it: an entry is served
 * whatever its age, until it is pruned or {@link #clearCache} drops it. So an entry's age is not a reason the
 * read path stopped finding it, and a miss means the entry was never there or is no longer there — which is why
 * the prune now goes by last use (it used to go by age, and evicted callers mid-session).
 *
 * @author Claude Code
 */
public final class CallerReadScope {

    /**
     * One caller's flattened read scope. Empty arrays are a legitimate value — a signed-in user with no grants
     * at all — and are what the predicate's floor relies on: {@code = any('{}')} is false for every row.
     */
    public record Scope(int[] eventIds, int[] organizationIds, boolean unrestricted) {
        public boolean isEmpty() {
            return !unrestricted && eventIds.length == 0 && organizationIds.length == 0;
        }
    }

    private static final Scope EMPTY = new Scope(new int[0], new int[0], false);
    /**
     * A super administrator reads everything, and says so as a THIRD state rather than as a very long array.
     * Which is right twice over: the array would have to hold every event in the database and travel with
     * every query, and a caller who may read anything should have no predicate injected at all rather than
     * one that happens to be true — the scope token, not the values, is where that is decided.
     */
    private static final Scope UNRESTRICTED = new Scope(new int[0], new int[0], true);

    // Same reasoning and same figures as the grants cache next door (AUTHZ_CACHE_*): a deploy reconnects every
    // client at once, grants change rarely, and a fresh login always computes fresh because it has no entry.
    private static final long CACHE_TTL_MILLIS = 120_000;
    private static final int CACHE_PRUNE_SIZE = 100;

    /**
     * {@code lastUsedAtMillis} is when a read last PEEKED this entry, which is not when it was computed. The
     * prune needs the first and the TTL refresh needs the second, and conflating them is what evicted callers
     * who were in the middle of working — see {@link #peek}.
     */
    private record CachedScope(Future<Scope> future, long computedAtMillis, AtomicLong lastUsedAtMillis,
                               Scope lastGood) {
        CachedScope(Future<Scope> future, long computedAtMillis, Scope lastGood) {
            this(future, computedAtMillis, new AtomicLong(computedAtMillis), lastGood);
        }

        /** What a read gets: the computed value, or the previous one while a recomputation is in flight. */
        Scope servable() {
            return CallerReadScope.servable(future, lastGood);
        }
    }

    private static final Map<Object, CachedScope> cache = new ConcurrentHashMap<>();

    /** When a re-warm was last started, for the whole server — see {@link #sinceLastRewarmExceedsCooldown}. */
    private static final AtomicLong lastRewarmAtMillis = new AtomicLong();
    private static final long REWARM_COOLDOWN_MILLIS = 1_000;

    /** How often a bind found nothing computed. Reported rather than guessed at — see {@link #peek}. */
    private static final AtomicLong missCount = new AtomicLong();

    private CallerReadScope() {
    }

    /**
     * The scope already computed for this person, or null when none has been.
     *
     * <p>Null is a different answer from {@link Scope#isEmpty()}: one means "nobody has worked out what this
     * caller may read", the other "this caller may read nothing". Binding an empty array for the first would
     * turn a cold cache into a silently empty screen, which is the failure this plan keeps trying to avoid, so
     * the distinction is handed back rather than flattened here.
     */
    public static Scope peek(Object personId) {
        if (personId == null)
            return null;
        Object key = Entities.getPrimaryKey(personId);
        CachedScope cached = cache.get(key);
        Scope scope = cached == null ? null : cached.servable();
        if (scope != null) {
            long now = System.currentTimeMillis();
            // Stamped on every read, so the prune can tell a caller who is working from one who has gone.
            cached.lastUsedAtMillis.set(now);
            // REFRESH-AHEAD, and it is a SECURITY property of the prune change rather than a nicety.
            //
            // Pruning on last use means a caller who keeps reading is never evicted, so without this their
            // entry would be served for as long as they kept clicking, however old it had become. Before the
            // change the age prune eventually threw a stale entry away -- badly, by breaking the next read,
            // but it did bound how long a withdrawn grant could go on being honoured. Keeping an entry alive
            // for an active caller must not quietly remove that bound.
            //
            // So an entry past its TTL starts a recomputation for the NEXT read while this one is served
            // from what is already known. That leaves the same staleness the TTL always allowed, and no more.
            // An edit made through the back office does not wait for it: invalidateAllRuleRegistries clears
            // this cache outright. This is the floor under the edits that do not go through there.
            if (needsRefresh(cached.computedAtMillis(), now) && sinceLastRewarmExceedsCooldown(now))
                startRewarm(personId);
            return scope;
        }
        long n = missCount.incrementAndGet();
        // The cache SIZE is in the line because it separates the two ways a miss happens, which need different
        // fixes and are indistinguishable after the fact: well under CACHE_PRUNE_SIZE means nobody had computed
        // this caller's scope yet (a first read racing the grants), at or over it means a prune may have thrown
        // the entry away while the caller was still working.
        if (ClientWriteInventory.isPowerOfTen(n))
            Console.log("⚠️ CallerReadScope: no scope computed for a caller binding one (×" + n
                + ", cache " + cache.size() + "/" + CACHE_PRUNE_SIZE + ")");
        // RE-WARM. Fire and forget: this runs on the endpoint thread before the first async hop and cannot
        // await, so it cannot help THIS read -- it makes the next one find a scope instead of repeating the
        // miss. load() dedupes through the in-flight future, so a burst of misses costs one computation, and
        // the measured rate (4 misses in 3.5 production hours, 2026-11-12) makes that cost a rounding error.
        //
        // What it deliberately does NOT do is turn the miss into an empty scope. A caller whose scope is
        // unknown must not be told they may read nothing, and must not be let through either; the choice
        // between those belongs to whoever is enforcing, which is why null still comes back here.
        //
        // The cooldown is what stops a re-warm becoming a stampede. load() dedupes a computation that is in
        // FLIGHT, but a computation that FAILS is not cached -- deliberately, so the next caller retries
        // rather than inheriting the failure -- and "the next caller" is now every read. With the database
        // unreachable, failures return fast and every back-office read would start another query. One attempt
        // per second server-wide cannot do that, and at four misses in three and a half hours two callers
        // colliding inside one second is a rounding error against a mechanism that cannot storm.
        if (sinceLastRewarmExceedsCooldown(System.currentTimeMillis()))
            startRewarm(personId);
        return null;
    }

    /** How many times {@link #peek} found nothing computed, for the observation mode to report. */
    public static long getMissCount() {
        return missCount.get();
    }

    /**
     * True when a re-warm may be started now, claiming the slot if so. One attempt per second for the whole
     * server, not per caller: the failure mode it exists for (a computation that fails fast, every read
     * starting another) is server-wide, and a per-caller cooldown would let a hundred callers storm in step.
     */
    private static boolean sinceLastRewarmExceedsCooldown(long now) {
        long last = lastRewarmAtMillis.get();
        return now - last >= REWARM_COOLDOWN_MILLIS && lastRewarmAtMillis.compareAndSet(last, now);
    }

    /** Starts a recomputation and lets this read continue whatever happens to it. */
    private static void startRewarm(Object personId) {
        try {
            load(personId);
        } catch (Throwable e) {
            Console.error("CallerReadScope: could not start a re-warm", e);
        }
    }

    /**
     * What a read is served, apart from the record so that a check can hold it: the computed value once it is
     * there, and until then the previous one. Null only when there has never been a value to carry forward,
     * which is the one case where a caller's scope is genuinely unknown.
     */
    static Scope servable(Future<Scope> future, Scope lastGood) {
        return future.succeeded() ? future.result() : lastGood;
    }

    /**
     * Whether an entry that is still being read should be recomputed for the next read. The age of the VALUE,
     * not of its last use: the two are the same only for a caller who reads once.
     */
    static boolean needsRefresh(long computedAtMillis, long now) {
        return now - computedAtMillis >= CACHE_TTL_MILLIS;
    }

    /**
     * Whether a cache entry may be pruned, as a function of its state rather than of the map, so that a check
     * can hold it. An entry is prunable when its computation has finished AND no read has touched it for a
     * TTL. The second half is the fix: the predicate used to read {@code computedAtMillis}, which measured how
     * old the entry was rather than whether anyone still needed it.
     */
    static boolean prunable(boolean computationComplete, long lastUsedAtMillis, long now) {
        return computationComplete && now - lastUsedAtMillis >= CACHE_TTL_MILLIS;
    }

    /**
     * Computes this person's scope, or returns the computation already in flight or recently done.
     *
     * <p>Called where a caller's grants are computed, so that the endpoint's synchronous {@link #peek} finds it
     * there. A pending future doubles as in-flight deduplication, exactly as the grants cache does.
     */
    public static Future<Scope> load(Object personId) {
        if (personId == null)
            return Future.succeededFuture(EMPTY);
        Object key = Entities.getPrimaryKey(personId);
        long now = System.currentTimeMillis();
        CachedScope cached = cache.get(key);
        if (cached != null && (!cached.future.isComplete()
                               || cached.future.succeeded() && now - cached.computedAtMillis < CACHE_TTL_MILLIS))
            return cached.future;
        // Pruned on LAST USE, not on age. On age, an entry older than the TTL was dropped however recently it
        // had been read -- and peek never consulted the TTL, so the entry's age said nothing about whether the
        // read path still needed it. A caller working steadily through the back office was evicted mid-session
        // by somebody else's login, and their next reads found nothing: the clustered misses of 2026-11-12.
        if (cache.size() > CACHE_PRUNE_SIZE)
            cache.values().removeIf(c -> prunable(c.future.isComplete(), c.lastUsedAtMillis.get(), now));
        // The previous value is CARRIED FORWARD, and this is the fix the first live run produced.
        //
        // Replacing a succeeded entry with an in-flight future opened a hole: for as long as the two scope
        // queries took, every read by that caller found a future that had not succeeded and counted a miss,
        // although a perfectly good value had just been thrown away. The local run of 2026-11-13 caught it
        // exactly -- the recomputation's queries start at 11:29:32.455, six reads miss between .464 and .471
        // with "cache 1/100" (so no prune could be involved), and the queries return at .625. A ~170ms hole,
        // once per refresh, hitting every read in it: the clustered misses production logged.
        //
        // Carrying the old value forward serves it until the new one succeeds. The extra staleness is the
        // length of one recomputation, where the alternative is a caller who may read nothing at all.
        Scope previous = cached == null ? null : cached.servable();
        Future<Scope> future = computeScope(personId);
        cache.put(key, new CachedScope(future, now, previous));
        // A failed computation must not be cached: the next caller should try again rather than inherit it.
        future.onFailure(e -> {
            CachedScope current = cache.get(key);
            if (current != null && current.future == future)
                cache.remove(key);
        });
        return future;
    }

    /** Forgets everything, for when grants are edited — called from the same place the grants cache is cleared. */
    public static void clearCache() {
        cache.clear();
    }

    /**
     * The three sources of a back-office caller's reach, which are the same three the grants computation reads.
     *
     * <p>Taking only the access rows was the first version and it was wrong in the most visible way possible:
     * a super administrator holds no access row, so their scope came out empty and they would have seen nothing
     * at all. An organisation administrator holds no access row either, and their reach is their organisation.
     * Anything the grants computation treats as a source of authority has to be a source here too, or the read
     * path and the write path disagree about who someone is.
     */
    private static Future<Scope> computeScope(Object personId) {
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return Future.all(
                SuperAdminMembership.isSuperAdminPerson(personId, entityStore),
                entityStore.<AuthorizationOrganizationUserAccess>executeQuery(
                    "select organization.id,event.id,eventType.id from AuthorizationOrganizationUserAccess where user=$1", personId),
                entityStore.<AuthorizationOrganizationAdmin>executeQuery(
                    "select organization.id from AuthorizationOrganizationAdmin where admin=$1", personId)
            ).compose(composite -> {
                if (Boolean.TRUE.equals(composite.resultAt(0)))
                    return Future.succeededFuture(UNRESTRICTED);
                return flattenAccessRows(composite.resultAt(1), composite.resultAt(2));
            });
    }

    private static Future<Scope> flattenAccessRows(EntityList<AuthorizationOrganizationUserAccess> accessRows,
                                                   EntityList<AuthorizationOrganizationAdmin> organizationAdminRows) {
        List<GrantRow> grants = new ArrayList<>();
        for (AuthorizationOrganizationUserAccess access : accessRows)
            grants.add(new GrantRow(intOrNull(Entities.getPrimaryKey(access.getOrganizationId())),
                                    intOrNull(Entities.getPrimaryKey(access.getEventId())),
                                    intOrNull(Entities.getPrimaryKey(access.getEventTypeId()))));
        List<Integer> adminOrganizationIds = new ArrayList<>();
        for (AuthorizationOrganizationAdmin admin : organizationAdminRows) {
            Integer organizationId = intOrNull(Entities.getPrimaryKey(admin.getOrganizationId()));
            if (organizationId != null)
                adminOrganizationIds.add(organizationId);
        }
        List<GrantRow> typeRows = typeRowsNeedingExpansion(grants);
        if (typeRows.isEmpty())
            return Future.succeededFuture(assemble(grants, adminOrganizationIds, new int[0]));
        EntityStore entityStore = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        // Collected inside each query rather than read off the batch's result array, because the batch's
        // result type would be a generic EntityList<Event> and its array generator cannot express that.
        Set<Integer> expanded = ConcurrentHashMap.newKeySet();
        return new Batch<>(typeRows.toArray(new GrantRow[0]))
            .<Object>executeParallel(Object[]::new, typeRow ->
                // The organisation conjunct is not redundant: an event type may be GLOBAL
                // (event_type.organization_id is nullable), and a grant on a global type means "within this
                // organisation" rather than everywhere - decided with V0133, and this is where that holds.
                //
                // Note what is NOT done here: one query with `organization in ?orgs and type in ?types`. That
                // is a CROSS PRODUCT, and it would grant an event of type T in an organisation where only type
                // U was granted. One query per row, sharing one statement text, is both correct and cacheable.
                entityStore.<Event>executeQuery("select id from Event where type=$1 and organization=$2",
                        typeRow.eventTypeId(), typeRow.organizationId())
                    .map(events -> {
                        for (Event event : events) {
                            Integer id = intOrNull(Entities.getPrimaryKey(event));
                            if (id != null)
                                expanded.add(id);
                        }
                        return null;
                    }))
            .map(ignored -> assemble(grants, adminOrganizationIds, toIntArray(expanded)));
    }

    /**
     * One grant row, reduced to the three ids that decide what it scopes. Null means the column was null.
     *
     * <p>A plain value rather than the entity, so that the POLICY below — which row means an organisation,
     * which means an event, which has to be expanded — can be stated and tested without a database.
     */
    public record GrantRow(Integer organizationId, Integer eventId, Integer eventTypeId) {}

    /**
     * The rows whose event type has to be turned into event ids: a type key, and an organisation to bound it.
     *
     * <p>A type row with no organisation is skipped rather than widened. {@code organization_id} is NOT NULL on
     * the table, so this cannot happen today; if it ever could, the alternative reading is "every event of that
     * type everywhere", and a grant should never grow reach because a column was missing.
     */
    static List<GrantRow> typeRowsNeedingExpansion(List<GrantRow> grants) {
        List<GrantRow> typeRows = new ArrayList<>();
        for (GrantRow grant : grants)
            if (grant.eventId() == null && grant.eventTypeId() != null && grant.organizationId() != null)
                typeRows.add(grant);
        return typeRows;
    }

    /**
     * THE POLICY: which bucket each grant row lands in, with the type rows already expanded.
     *
     * <p>Separated from the queries above so that every decision it makes is checkable without a database —
     * and there are more of them than the three-line version suggested: at most one scope key per row (a CHECK
     * since V0133), an event row contributing nothing to its organisation (that is the point of naming an
     * event), an organisation-wide row being what every pre-V0133 row is, and an administrator's organisation
     * counting as a grant.
     */
    static Scope assemble(List<GrantRow> grants, List<Integer> adminOrganizationIds, int[] expandedEventIds) {
        Set<Integer> organizationIds = new LinkedHashSet<>(adminOrganizationIds);
        Set<Integer> eventIds = new LinkedHashSet<>();
        for (int eventId : expandedEventIds)
            eventIds.add(eventId);
        for (GrantRow grant : grants) {
            // Three cases and never a combination: at most one scope key per row is enforced by a CHECK.
            // An event row does NOT widen to its organisation - naming an event is how a grant is narrowed.
            if (grant.eventId() != null)
                eventIds.add(grant.eventId());
            else if (grant.eventTypeId() != null)
                ; // already expanded into eventIds, or skipped for want of an organisation
            else if (grant.organizationId() != null)
                organizationIds.add(grant.organizationId());
        }
        return new Scope(toIntArray(eventIds), toIntArray(organizationIds), false);
    }

    private static Integer intOrNull(Object primaryKey) {
        Integer value = Numbers.toInteger(primaryKey);
        return value;
    }

    /**
     * Sorted, so that one scope always binds the SAME array.
     *
     * <p>Not cosmetic: a push subscription is keyed by its QueryArgument, whose equality compares parameter
     * values, so two computations of one caller's scope that differed only in order would be two subscriptions
     * instead of one — and the expansion above collects from parallel queries, which do not finish in a fixed
     * order.
     */
    private static int[] toIntArray(Set<Integer> values) {
        int[] array = new int[values.size()];
        int i = 0;
        for (Integer value : values)
            array[i++] = value;
        java.util.Arrays.sort(array);
        return array;
    }
}

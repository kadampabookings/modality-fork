package one.modality.crm.server.services.authz;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.ClientReadInspectionRegistry;
import dev.webfx.stack.session.state.RestrictedPrincipalRegistry;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * What the clients actually read, recorded so the rule that constrains the rest can be written from evidence
 * rather than from a grep.
 *
 * <p>Step 0 of the read-authorization plan ({@code docs/security/read-authorization-plan.md}), and the twin of
 * {@link ClientWriteInventory}, which it follows closely on purpose: the same caps, the same power-of-ten
 * logging, the same refusal to record a single value. It refuses nothing and changes no behaviour.
 *
 * <h3>Why this comes before the rule, and why a grep was not enough</h3>
 *
 * <p>The plan's inventory was built by grepping the front end: 135 statements over 32 entity types. That is a
 * floor and it knows it. It cannot see a statement composed at runtime, it cannot see the legacy JavaFX clients
 * at all, and it counts call sites rather than traffic — so it says nothing about which shapes are actually sent,
 * which is the only thing an allowlist can be written against.
 *
 * <h3>The judgement this class makes, which the framework deliberately does not</h3>
 *
 * <p>{@link ClientReadInspectionRegistry} reports facts, and reports only what a statement GUARANTEES: the
 * entity, the tables reached, the field paths tied to a value, the functions used as guards. Only this side knows
 * that {@code accountCanAccessPersonOrders} is what an ownership predicate looks like here, so only this side can
 * say whether a read is the plan's <b>shape A</b> — scoped by a predicate whose argument the CLIENT supplies — or
 * something with no scope at all.
 *
 * <p>That distinction is the whole reason for the inventory. A statement already carrying the predicate needs a
 * one-token change in step 2 ({@code $2} becomes {@code callerAccount()}); a statement with no predicate needs an
 * endpoint. Counting which is which is what says how big step 2 really is.
 *
 * <h3>What the construct list is for</h3>
 *
 * <p>{@code uses=[…]} names the expression kinds a statement contains. It answers the question a restricted
 * client dialect has to be designed against — which of the grammar's ~59 term classes clients actually send —
 * and it answers it by OBSERVATION. Reading the front-end source cannot: it misses everything composed at
 * runtime, and it cannot see the Java clients at all, which build statements programmatically rather than as
 * text. A construct nobody anticipated is recorded here rather than refused, which is the one place in this
 * work where an unknown is data instead of a refusal.
 *
 * <h3>What is deliberately not logged</h3>
 *
 * <p>Names, never values. No parameter value, no literal, no target id, no principal identity. A read's literals
 * are where an email address or a surname would be, and the framework masks them before this class is told
 * anything at all. An inventory does not need one of them to say which entities and columns a client reads —
 * and an inventory that held them would be a new store of personal data outside the reach of the erasure path,
 * which is the mistake the plan's own GDPR section exists to prevent.
 *
 * @author Claude Code
 */
final class ClientReadInventory implements ClientReadInspectionRegistry.ReadInspector {

    /**
     * The domain's ownership predicates, as they appear in DQL.
     *
     * <p>Registered as inline functions in {@code DomainModelSnapshotLoader}, and already used by the orders,
     * media and document paths. <b>Their argument is the account id, and the client supplies it</b> — which is
     * the finding the plan turns on: the predicate is right and only its argument is untrustworthy. So a read
     * counted here as scoped is scoped to whichever account the caller named, and step 2 is what makes it mean
     * something. Recorded so the count exists before the change, not as an assurance about today.
     */
    private static final Set<String> OWNERSHIP_FUNCTIONS =
        Set.of("accountcanaccesspersonorders", "accountcanaccesspersonmedias");

    /**
     * How many distinct shapes are worth remembering before concluding that something is generating them rather
     * than someone. The apps have a few hundred read sites between them, so a healthy inventory settles well
     * under this; reaching it is itself the finding.
     */
    private static final int MAX_SHAPES = 1_000;

    /**
     * How many table names a shape names before the rest are counted instead.
     *
     * <p>A read reaches more tables than a write touches — a booking with its person, event, site and items is
     * ordinary — and the whole list on one log line stops being readable long before it stops being true.
     */
    private static final int MAX_TABLES_NAMED = 12;

    /** How many bound fields or guard functions a shape names before the rest are counted instead. */
    private static final int MAX_FIELDS_NAMED = 12;

    /**
     * How many expression kinds a shape names.
     *
     * <p>Higher than the others because this list is the point of the exercise: it is what a restricted client
     * dialect would be defined from, and a truncated one would hide exactly the construct nobody expected. The
     * whole grammar is under 60 classes and a single statement uses a handful, so 24 truncates nothing real.
     */
    private static final int MAX_CONSTRUCTS_NAMED = 24;

    /** How long one name may be — a dot path can be walked round a foreign-key cycle indefinitely. */
    private static final int MAX_ENTRY_LENGTH = 60;

    /**
     * And the whole key, so that no single log line or map entry can be made unbounded by composing one.
     *
     * <p>Raised from 400 when the construct list joined: the parts are each capped, but four capped lists plus a
     * verdict no longer fit in 400, and a shape truncated mid-list silently merges with its neighbours.
     */
    private static final int MAX_SHAPE_LENGTH = 800;

    /**
     * One entry per distinct shape.
     *
     * <p><b>Capped, because the key is the client's to choose.</b> A shape includes the bound-field list, and a
     * caller free to add conditions can mint distinct shapes at will — which against an uncapped map is unbounded
     * heap and against the log is a line per variation. Both are reachable by anyone who can open a socket, which
     * is precisely the population this inventory exists to describe.
     */
    private final Map<String, AtomicLong> countsByShape = new ConcurrentHashMap<>();

    /** Occurrences of shapes arriving after {@link #MAX_SHAPES} was reached — counted, never named. */
    private final AtomicLong overflowCount = new AtomicLong();

    /**
     * Where a line goes. Injectable for the same reason as the write inventory's: the property worth pinning
     * about this class is how MANY lines it writes, not what they say.
     */
    private final Consumer<String> logger;

    ClientReadInventory() {
        this(Console::log);
    }

    ClientReadInventory(Consumer<String> logger) {
        this.logger = logger;
    }

    /**
     * Only client traffic is inventoried.
     *
     * <p>Server-internal reads are not a finding and are the bulk of the volume — every job, every trigger
     * follow-up, every sign-in reading its own magic link. Including them would make this an inventory of the
     * server, and would put an extra compilation on paths that have nothing to do with this.
     *
     * <p>Read here rather than passed in because this is the one moment it can be: the framework asks before the
     * first async hop, while the caller's state is still on the thread.
     */
    @Override
    public boolean isInspecting() {
        return ThreadLocalStateHolder.isClientOrigin();
    }

    @Override
    public void onRead(ClientReadInspectionRegistry.ReadShape shape) {
        record(shapeOf(shape, callerClass()));
    }

    /**
     * A client statement the framework could not describe.
     *
     * <p>Counted as its own shape, with the statement's own text masked of every literal. The text is worth
     * keeping where the description failed — it is the only thing that says WHICH statements this inventory is
     * blind to — and the number of them is what says whether it covers all of the client's traffic or most of
     * it. A shape that never appears is the answer we are hoping for.
     */
    @Override
    public void onUndescribableStatement(String maskedStatement) {
        record("UNDESCRIBABLE by " + callerClass() + ": " + maskedStatement);
    }

    /**
     * A client READ a watched capability column — the thing the enforced rule would have refused.
     *
     * <p>Recorded as its own shape so it stands out in the report rather than blending into the traffic, and
     * prefixed so one Insights filter finds every occurrence. This is the number that decides when the enforced
     * rule can come back: while it is above zero there are still clients sending the old statement, and turning
     * the rule on would refuse them — which is how invitation.token came to tell real invitees their invitation
     * was invalid for fifteen hours.
     *
     * <p>Zero is not instantly conclusive either. The rate to beat is the one the refusals showed while the rule
     * was on, about twelve a day, so a few quiet hours mean little and a quiet week means a great deal.
     */
    @Override
    public void onObservedCapabilityColumnRead(String maskedStatement, String[] columns, boolean unanalysable) {
        // NOT through record(). That logs a shape at occurrence 1, 10, 100, 1000 — right for an inventory,
        // catastrophic here. At the rate the refusals actually showed, about twelve a day from one stale
        // statement, it would log twice on the first day and then go quiet until roughly the eighth, while
        // clients went on reading the token. Six silent days read as "zero for a week", which is the exact
        // sentence that would restore the enforced rule and repeat the outage. A throttle that manufactures
        // the all-clear it is being consulted for is worse than no instrument at all.
        //
        // So: every occurrence, with the running total IN the line, so one log entry answers "how many so
        // far" without anyone having to count them. Capped only against a client minting them deliberately,
        // and the cap keeps the powers of ten so the count stays legible past it.
        long n = capabilityReads.incrementAndGet();
        if (n > MAX_CAPABILITY_LINES && !ClientWriteInventory.isPowerOfTen(n))
            return;
        logger.accept("🛡 " + (unanalysable ? "CAPABILITY-UNREADABLE " : "CAPABILITY-READ ")
                      + String.join(",", columns == null ? new String[0] : columns)
                      + " (#" + n + ") by " + callerClass() + ": " + maskedStatement);
    }

    /** Occurrences, not shapes: the watch is counting how OFTEN, not how many ways. */
    private final AtomicLong capabilityReads = new AtomicLong();

    /**
     * Past this many lines the watch falls back to powers of ten. High enough that a real stale-client rate
     * (tens a day) is logged in full for months, low enough that a client minting reads cannot flood the log.
     */
    private static final long MAX_CAPABILITY_LINES = 5_000;

    /** Occurrences seen so far — for a check, and for anyone asking the count without reading the log. */
    long capabilityReadCount() {
        return capabilityReads.get();
    }

    /**
     * The key an allowlist entry would be written against.
     *
     * <p>Everything in it is sorted, so the same read composed in a different order is one shape and not two.
     *
     * <p><b>Bounded in length, not only in number.</b> The caps below matter for the same reason the shape count
     * is capped: every part of this key is the client's to compose. Foreign keys form cycles, so a dot path can
     * be made arbitrarily deep, and conditions can be added at will — so a key built by naively joining them is
     * unbounded, as a map entry and as a log line, for anyone who can open a socket. Truncation can in principle
     * fold two pathological shapes into one key, which is the right trade: the shapes it folds are the ones
     * nobody was going to read individually anyway.
     */
    static String shapeOf(ClientReadInspectionRegistry.ReadShape shape, String callerClass) {
        // The verdict and the caller come FIRST, ahead of the three lists, because the key is truncated at
        // MAX_SHAPE_LENGTH and the lists are what make it long. Put last, they were the first thing a long
        // shape lost - and a shape with no `scope=` matches none of the report's filters, so it vanishes from
        // every section rather than appearing in the wrong one.
        String key = (shape.entityName() == null ? "?" : shape.entityName())
               + " " + shape.statementKind()
               + " " + scopeOf(shape)
               + " by " + callerClass
               + (shape.hasWhere() ? "" : " no-where")
               + " " + boundOf(shape)
               + " fn=" + capped(shape.guardFunctions(), MAX_FIELDS_NAMED)
               + " tables=" + capped(shape.touchedTables(), MAX_TABLES_NAMED)
               // LAST, and the cap raised to fit it. Inserted ahead of `tables=` it pushed the table list past
               // MAX_SHAPE_LENGTH, so two reads touching different tables collapsed into one key — the same
               // truncation trap the verdict was moved to the front to escape, one field further down.
               + " uses=" + capped(shape.constructs(), MAX_CONSTRUCTS_NAMED);
        return key.length() <= MAX_SHAPE_LENGTH ? key : key.substring(0, MAX_SHAPE_LENGTH) + "…";
    }

    /**
     * What the statement ties its rows to.
     *
     * <p>Three renderings, because two of them are easy to confuse and the confusion is expensive:
     *
     * <ul>
     *   <li>{@code bound=[a,b]} — tied down, and by the same fields whichever branch a row came through.</li>
     *   <li>{@code bound=any-of[a,b]} — tied down, but by different fields in different branches. This is the
     *       orders union: {@code person.frontendAccount} in one branch and
     *       {@code person.accountPerson.frontendAccount} in the other, scoped to one account all the same. It
     *       read as {@code bound=[]} until 2026-09-23 and so was filed as returning the whole table — the first
     *       thing production traffic said, and a rule written from it would have refused the orders page.</li>
     *   <li>{@code bound=[]} — nothing ties the rows down at all.</li>
     * </ul>
     */
    private static String boundOf(ClientReadInspectionRegistry.ReadShape shape) {
        if (shape.boundFields().length > 0)
            return "bound=" + capped(shape.boundFields(), MAX_FIELDS_NAMED);
        if (shape.bounded() && shape.alternativeFields().length > 0)
            return "bound=any-of" + capped(shape.alternativeFields(), MAX_FIELDS_NAMED);
        return "bound=[]";
    }

    /**
     * A bracketed list, capped in how many entries it names and in how long each may be.
     *
     * <p>Both caps are needed: the count bounds a caller adding conditions, and the per-entry length bounds one
     * walking a foreign-key cycle to build a single enormous path.
     */
    private static String capped(String[] values, int maxEntries) {
        StringBuilder sb = new StringBuilder("[");
        int named = Math.min(values.length, maxEntries);
        for (int i = 0; i < named; i++) {
            if (i > 0)
                sb.append(',');
            String value = values[i];
            sb.append(value.length() <= MAX_ENTRY_LENGTH ? value : value.substring(0, MAX_ENTRY_LENGTH) + "…");
        }
        if (values.length > named)
            sb.append(",+").append(values.length - named);
        return sb.append(']').toString();
    }

    /**
     * Whether this read could be constrained to its caller at all — the question step 3 will ask of every shape
     * in this inventory, and the one that sorts the plan's five shapes into work.
     *
     * <p>Three answers, and the distinctions are the ones a rule turns on:
     *
     * <ul>
     *   <li>{@code scope=ownership-fn} — the predicate is already there, as a guard the statement GUARANTEES.
     *       Step 2 binds its argument, and that is the whole of the change.</li>
     *   <li>{@code scope=UNSCOPED} — the statement guarantees nothing about which rows it returns: no WHERE, or
     *       one that ties no column to a value and uses no guard, or an OR or union with one such alternative.
     *       This read returns the table. It is the read twin of the write inventory's {@code target=UNBOUNDED},
     *       and it is the line to look for first.
     *       <p>One shape lands here that is not in fact unconstrained: {@code x in (select …)}. A subquery can
     *       select anything, so nothing here can verify what it bounds. Kept conservative on purpose, and worth
     *       knowing when reading a report.</li>
     *   <li>{@code scope=fn?} — restricted only by a function this side does not recognise. Not a verdict: a
     *       request for one. Either it is an ownership predicate missing from {@code OWNERSHIP_FUNCTIONS}, or
     *       it is a search condition that scans the table, and the shape's {@code fn=} list says which to go
     *       and look at.</li>
     *   <li>{@code scope=fields} — something is tied down, and what it is appears in the shape's own
     *       {@code bound=} list. <b>Bound is not scoped.</b> {@code where id=$1} binds and reaches anybody's row;
     *       {@code where cart.uuid=$1} binds and is a capability; {@code where person=$1} binds to an id the
     *       client chose. Telling those three apart is reading, not counting, which is why this says only that
     *       there is something to read.</li>
     * </ul>
     *
     * <p>Every input here is a GUARANTEE rather than a mention, which is the framework's doing and is what makes
     * this classification worth anything: a predicate behind an OR, or present in only one branch of a union,
     * never reaches {@code guardFunctions} in the first place. Judging {@code scope=ownership-fn} from a name
     * appearing anywhere in the WHERE would have filed {@code accountCanAccess…($1, person) or cart.uuid=$2} —
     * which returns every document — as already scoped.
     */
    private static String scopeOf(ClientReadInspectionRegistry.ReadShape shape) {
        for (String function : shape.guardFunctions())
            if (OWNERSHIP_FUNCTIONS.contains(function.toLowerCase()))
                return "scope=ownership-fn";
        // `bounded`, not an empty field list. Two branches can each tie their rows down and share no field, and
        // reading the empty intersection as "unconstrained" is what called the orders page unscoped.
        if (shape.bounded())
            return "scope=fields";
        // A guard this side does not recognise. Neither bucket above is honest about it: calling it scoped
        // would file `searchMatchesPerson(p)` - a table scan when the search term is empty - as constrained,
        // and calling it UNSCOPED would bury a real ownership predicate nobody has added to the list yet.
        // Named so a person looks, which is the only thing that can settle it.
        return shape.guardFunctions().length == 0 ? "scope=UNSCOPED" : "scope=fn?";
    }

    /**
     * Which KIND of caller this is, by principal class rather than by identity — anonymous, guest, registered,
     * which is what the plan's baseline is written in terms of. A class name is not personal data, where the
     * principal itself names a person.
     */
    private static String callerClass() {
        Object userId = ThreadLocalStateHolder.getUserId();
        if (userId == null)
            return "anonymous";
        String name = userId.getClass().getSimpleName();
        return RestrictedPrincipalRegistry.isUserRestricted(userId) ? name + "(restricted)" : name;
    }

    /**
     * Records one occurrence, and logs only when the count says something new.
     *
     * <p>As on the write side, {@code get} then {@code computeIfAbsent} is deliberately not atomic as a whole:
     * two threads racing on a brand-new shape can both log it once. The alternative is a lock on the read path
     * to prevent a duplicate log line.
     */
    private void record(String shape) {
        AtomicLong counter = countsByShape.get(shape);
        if (counter == null) {
            if (countsByShape.size() >= MAX_SHAPES) {
                long overflow = overflowCount.incrementAndGet();
                if (ClientWriteInventory.isPowerOfTen(overflow))
                    logger.accept("🛡 client read shapes past the " + MAX_SHAPES + " cap (×" + overflow
                                + ") — not recorded. Something is GENERATING shapes; read the shapes already"
                                + " listed rather than waiting for more.");
                return;
            }
            counter = countsByShape.computeIfAbsent(shape, key -> new AtomicLong());
        }
        long count = counter.incrementAndGet();
        if (ClientWriteInventory.isPowerOfTen(count))
            logger.accept("🛡 client read shape " + (count == 1 ? "(new)" : "(×" + count + ")") + ": " + shape);
    }

    /** Distinct shapes seen so far — the inventory's size, for a summary line or a check. */
    int distinctShapeCount() {
        return countsByShape.size();
    }
}

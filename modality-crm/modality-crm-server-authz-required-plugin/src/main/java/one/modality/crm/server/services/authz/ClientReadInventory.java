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

    /** How long one name may be — a dot path can be walked round a foreign-key cycle indefinitely. */
    private static final int MAX_ENTRY_LENGTH = 60;

    /** And the whole key, so that no single log line or map entry can be made unbounded by composing one. */
    private static final int MAX_SHAPE_LENGTH = 400;

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
        String key = (shape.entityName() == null ? "?" : shape.entityName())
               + " " + shape.statementKind()
               + " bound=" + capped(shape.boundFields(), MAX_FIELDS_NAMED)
               + " fn=" + capped(shape.guardFunctions(), MAX_FIELDS_NAMED)
               + " tables=" + capped(shape.touchedTables(), MAX_TABLES_NAMED)
               + (shape.hasWhere() ? "" : " no-where")
               + " by " + callerClass
               + " " + scopeOf(shape);
        return key.length() <= MAX_SHAPE_LENGTH ? key : key.substring(0, MAX_SHAPE_LENGTH) + "…";
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
     *       one that ties no column to a value and uses no guard, or a union with one such branch. This read
     *       returns the table. It is the read twin of the write inventory's {@code target=UNBOUNDED}, and it is
     *       the line to look for first.</li>
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
        return shape.boundFields().length == 0 ? "scope=UNSCOPED" : "scope=fields";
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

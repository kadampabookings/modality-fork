package one.modality.crm.server.services.authz;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.session.state.RestrictedPrincipalRegistry;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * What the clients actually write, recorded so the rule that refuses the rest can be written from
 * evidence rather than from a grep.
 *
 * <p>Step 0 of the front-office write-authorization plan
 * ({@code docs/security/frontoffice-write-authorization-plan.md}). It refuses nothing and changes no
 * behaviour. Its whole output is a log of the DISTINCT SHAPES of write arriving from client origins —
 * entity, verb, which fields, which kind of caller, and whether the row being changed could be
 * identified at all.
 *
 * <p><b>Why this comes before the rule.</b> A default-deny needs an allowlist, and an allowlist built
 * from reading the front-end source is a list of what the code can do, not of what it does — it misses
 * whatever a code path composes at runtime, and it includes call sites nobody reaches any more. Both
 * errors are expensive in opposite directions: the first locks out real users on the day enforcement is
 * switched on, the second leaves the door open for something that was supposed to be gone.
 *
 * <h3>Shapes, not writes</h3>
 *
 * Writing one line per write would produce millions of them — a media-consumption heartbeat alone
 * writes every few seconds per viewer — and would bury the very thing this exists to find. So each
 * distinct shape is logged ONCE when first seen, and afterwards only as its count crosses a power of
 * ten. A shape that arrives a million times costs seven lines, and a shape that arrives once still
 * cannot be missed.
 *
 * <h3>What is deliberately not logged</h3>
 *
 * Field NAMES, never field VALUES, and no target id. The values are members' names, addresses, phone
 * numbers and emails, and an inventory does not need a single one of them to say which entities and
 * columns a client writes. The same reasoning the raw-statement reporter already applies to its
 * parameters, and the reason the target is recorded only as its KIND — a new row, an identified row, or
 * one this could not identify. Whether an ownership rule could constrain a statement is the useful
 * part, and it is answerable without saying whose row it was.
 *
 * @author Bruno Salmon
 */
final class ClientWriteInventory implements ProtectedEntityWriteRegistry.WriteInspector {

    /**
     * How many distinct shapes are worth remembering before concluding that something is generating
     * them rather than someone.
     *
     * <p>The apps have on the order of a hundred write sites between them, so a healthy inventory
     * settles well under this. Reaching it is itself the finding.
     */
    private static final int MAX_SHAPES = 500;

    /**
     * One entry per distinct shape.
     *
     * <p><b>Capped, because the key is the client's to choose.</b> A shape includes the SET field list,
     * and a caller free to send any subset of a table's columns can mint 2^n distinct shapes from one
     * entity — which against an uncapped map is unbounded heap, and against the log below is one line
     * per subset. Both are reachable by anyone who can open a socket, which is precisely the population
     * this inventory exists to describe. Past the cap, new shapes are counted in one place and no
     * longer named.
     */
    private final Map<String, AtomicLong> countsByShape = new ConcurrentHashMap<>();

    /** Occurrences of shapes arriving after {@link #MAX_SHAPES} was reached — counted, never named. */
    private final AtomicLong overflowCount = new AtomicLong();

    /**
     * Where a line goes.
     *
     * <p>Injectable for one reason: the property worth pinning about this class is how MANY lines it
     * writes, not what they say. The cap exists because a hostile client could otherwise mint a log line
     * per column subset, and an assertion about log volume is the only shape of test that would catch
     * that coming back.
     */
    private final Consumer<String> logger;

    ClientWriteInventory() {
        this(Console::log);
    }

    ClientWriteInventory(Consumer<String> logger) {
        this.logger = logger;
    }

    /**
     * Only client traffic is inventoried.
     *
     * <p>Server-internal writes are not a finding and are the bulk of the volume — every job that
     * stamps a mail as transmitted, every trigger-driven repair. Including them would make the
     * inventory an inventory of the server, and would put a parse on paths that have nothing to do
     * with this.
     *
     * <p>Read here rather than passed in because this is the one moment it can be: the framework asks
     * before the first async hop, while the caller's state is still on the thread.
     */
    @Override
    public boolean isInspecting() {
        return ThreadLocalStateHolder.isClientOrigin();
    }

    @Override
    public void onWrite(ProtectedEntityWriteRegistry.WriteRequest request) {
        record(shapeOf(request));
    }

    /**
     * A statement from a client that would not parse.
     *
     * <p>Counted as its own shape rather than logged in full: the text is the sender's own, but it is
     * also unbounded, and the number of them is what says whether this inventory is describing all of
     * the client's traffic or most of it.
     */
    @Override
    public void onUnparseableStatement(String statement) {
        record("UNPARSEABLE by " + callerClass());
    }

    /**
     * The key an allowlist entry would be written against: entity, verb, fields, caller, target.
     *
     * <p>Fields are sorted, so the same write composed in a different order is one shape and not two —
     * a form that sets the same columns is the same permission whatever order the client serialised
     * them in.
     */
    static String shapeOf(ProtectedEntityWriteRegistry.WriteRequest request, String callerClass) {
        String[] fields = request.writtenFields().clone();
        Arrays.sort(fields);
        return request.entityName()
               + " " + request.verb()
               + " [" + String.join(",", fields) + "]"
               + " by " + callerClass
               + " " + targetShapeOf(request);
    }

    /**
     * Whether an ownership rule could constrain this write to a row — the question step 5 will ask of
     * every shape in this inventory.
     *
     * <p>An INSERT is reported separately rather than as unreadable, and the distinction is the whole
     * value of the field. An insert has no WHERE and so never yields a target id, but it is not thereby
     * unconstrainable: a new row names its owner in the values it SETS, which is what
     * {@code writtenValues} is for. Folding the two together would have told the allowlist that no
     * client insert can be ownership-checked — false, and false in the direction that makes a rule
     * look impossible to write.
     *
     * <p>{@code target=UNBOUNDED} is separated from {@code target=UNREADABLE} for the same reason, and it
     * was added late, after a rule had already been written on the strength of a grep. Both have a null
     * target id, but {@code delete from ListItem where list=$1} is an ordinary set-based delete while
     * {@code delete from ListItem} rewrites the table, and an inventory that reported them alike could
     * not answer the one question {@code UnscopedWritePolicy} needed answering: does anything out there
     * actually send the second? <b>An inventory is only worth the distinctions it records</b>, and the
     * distinction a rule turns on has to be one of them.
     */
    private static String targetShapeOf(ProtectedEntityWriteRegistry.WriteRequest request) {
        if (request.verb() == ProtectedEntityWriteRegistry.WriteVerb.INSERT)
            return "target=new"; // ownership lives in the SET values, not in a WHERE
        if (request.unbounded())
            return "target=UNBOUNDED";
        return request.targetId() == null ? "target=UNREADABLE" : "target=id";
    }

    private String shapeOf(ProtectedEntityWriteRegistry.WriteRequest request) {
        return shapeOf(request, callerClass());
    }

    /**
     * Which KIND of caller this is, by principal class rather than by identity.
     *
     * <p>The class name is the answer the plan's baseline is written in terms of — anonymous, guest,
     * registered — and it is not personal data, where the principal itself names a person. Read as a
     * class name rather than with {@code instanceof} so this stays free of a dependency on the
     * authentication module's types, which is also what keeps a new principal type visible here instead
     * of silently falling into an {@code else}.
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
     * <p>{@code computeIfAbsent} then {@code incrementAndGet} is deliberately not atomic as a whole: two
     * threads racing on a brand-new shape can both see count 1 and log it twice. That is the right trade
     * here — the alternative is a lock on the write path to prevent a duplicate log line.
     */
    private void record(String shape) {
        AtomicLong counter = countsByShape.get(shape);
        if (counter == null) {
            // A shape already known is counted whatever the map's size; only an UNKNOWN one can grow it,
            // so the cap is tested here and not on the common path.
            if (countsByShape.size() >= MAX_SHAPES) {
                long overflow = overflowCount.incrementAndGet();
                if (isPowerOfTen(overflow))
                    logger.accept("🛡 client write shapes past the " + MAX_SHAPES + " cap (×" + overflow
                                + ") — not recorded. Something is GENERATING shapes; read the shapes"
                                + " already listed rather than waiting for more.");
                return;
            }
            counter = countsByShape.computeIfAbsent(shape, key -> new AtomicLong());
        }
        long count = counter.incrementAndGet();
        if (isPowerOfTen(count))
            logger.accept("🛡 client write shape " + (count == 1 ? "(new)" : "(×" + count + ")") + ": " + shape);
    }

    /** True for 1, 10, 100, … — the thresholds at which a shape's volume is worth another line. */
    static boolean isPowerOfTen(long count) {
        if (count < 1)
            return false;
        while (count % 10 == 0)
            count /= 10;
        return count == 1;
    }

    /** Distinct shapes seen so far — the inventory's size, for a summary line or a check. */
    int distinctShapeCount() {
        return countsByShape.size();
    }
}

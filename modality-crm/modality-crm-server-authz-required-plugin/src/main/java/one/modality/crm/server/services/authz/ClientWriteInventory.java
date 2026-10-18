package one.modality.crm.server.services.authz;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.WriteIntent;
import dev.webfx.stack.session.state.RestrictedPrincipalRegistry;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
     * Only traffic that arrived from outside is inventoried.
     *
     * <p>Server-internal writes are not a finding and are the bulk of the volume — every job that
     * stamps a mail as transmitted, every trigger-driven repair. Including them would make the
     * inventory an inventory of the server, and would put a parse on paths that have nothing to do
     * with this.
     *
     * <p><b>What this actually tests, and the caveat that follows.</b> {@code clientOrigin} is stamped
     * by the bridge on the inbound REQUEST, so it says where the request came from, not who composed
     * the write. Server code writing while it handles a client bus call is inventoried too, unless it
     * runs under a cleared state — which is what {@code ServerWrite.asServer} and
     * {@code asServerActingFor} do, by starting from {@code StateAccessor.createEmptyState()}. Writes
     * through those are correctly absent; writes from a server path that does not clear state are
     * present and indistinguishable from a client's. {@code ServerPaymentServiceProvider.insertPayment}
     * is the known instance: its three MoneyTransfer shapes are logged here, composed wholly by the
     * server, carrying {@code pending} and {@code successful} values no client chooses.
     *
     * <p><b>So a shape logged here does not establish that a client can send it.</b> That matters
     * exactly where this class is meant to help: the inventory is the evidence for the allowlist, and
     * a shape assumed to be a client's — then allowed for, so as not to break it — widens a client
     * rule to fit a write no client makes. Check a shape against the code that composes it before
     * allowing for it.
     *
     * <p><b>The two paths, named, because the difference decides what a deny rule will actually do.</b>
     * This inventory is invoked from {@code DqlSubmitInterceptorInitializer} — the INTERCEPTOR, which
     * runs on every submit, server ones included. {@code ClientWriteDenyList} is consulted from
     * {@code ClientSubmitGuard}, which only {@code ExecuteSubmitMethodEndpoint} and
     * {@code ExecuteSubmitBatchMethodEndpoint} call. The inventory is therefore a strict SUPERSET of
     * what a deny rule can refuse. A shape appearing here is a prompt to find out which path produced
     * it — never on its own a reason to believe {@code denyTable} would block it, nor that it would
     * not.
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
        Submit submit = openSubmit.get();
        if (submit != null)
            submit.add(request);
    }

    /**
     * One submit is beginning on this thread.
     *
     * <p>Thread-local because that is exactly the scope the framework guarantees: a submit's writes are
     * all started synchronously on the caller's own thread before any future completes. Two callers
     * cannot interleave within one thread, and a thread's own group is closed before it yields.
     *
     * <p>A begin arriving while one is open should not happen — submits do not nest — so the open one is
     * closed rather than discarded: losing the writes already accumulated would under-report composition,
     * which is the one thing this is here to measure.
     */
    @Override
    public void onSubmitBegin(int statementCount) {
        if (openSubmit.get() != null)
            onSubmitEnd();
        openSubmit.set(new Submit(statementCount));
    }

    /**
     * What one statement of the open submit declared. Untrusted, and nothing here acts on it.
     *
     * <p>Folded into the submit rather than paired with a write, because the question is about the
     * caller's ACTION: did the statements of one flush name one operation, or several? Several is the
     * composite case the declared-intent step must either split or keep the per-entity map for, and it
     * is invisible one statement at a time.
     */
    @Override
    public void onDeclaredIntent(WriteIntent intent) {
        Submit submit = openSubmit.get();
        if (submit != null)
            submit.declare(intent);
    }

    @Override
    public void onSubmitEnd() {
        Submit submit = openSubmit.get();
        openSubmit.remove();
        // A submit none of whose statements was inspected says nothing about composition: it is a job's
        // write, or raw SQL, or touches no protected entity. Counting those would bury the ones that do.
        if (submit != null && submit.writes > 0)
            record(submit.shape());
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

    /** The submit being accumulated on this thread, or null between submits. */
    private final ThreadLocal<Submit> openSubmit = new ThreadLocal<>();

    /**
     * What ONE caller action contained — the question a stream of statement shapes cannot answer.
     *
     * <p>Two things are being measured, both for the declared-intent step of the write model
     * ({@code docs/security/server-side-authorization-spec.md}, amendment 2026-10-17):
     *
     * <ol>
     *   <li><b>Can each submit name a single operation?</b> Answered by its COMPOSITION — how many
     *       statements, over which entities and verbs. A submit writing one entity is one action by
     *       construction; one writing four is the composite case the amendment says must either split
     *       or keep the per-entity map.</li>
     *   <li><b>Does any submit span two contexts?</b> Answered only PARTLY, and the limit is worth
     *       stating where somebody will read the output: a statement's context is derivable here only
     *       when the statement NAMES it — an insert setting {@code organization}, say. An update like
     *       {@code set confirmed=true where id=5} carries no context at all; resolving it means reading
     *       the row, which an inventory has no business doing. So a submit reported as touching two
     *       organizations really does; one reported as touching one, or none, is not thereby proven
     *       single-context. The amendment says as much — "the proof is the declared context plus a
     *       log" — and this is the filter before that proof, not a substitute for it.</li>
     * </ol>
     *
     * <p>Contexts are COUNTED, never named. Which centre a write belonged to is not needed to answer
     * whether one submit touched two of them, and an id is a reference to somebody's centre.
     */
    private static final class Submit {

        private final int statementCount;
        private final Set<String> parts = new TreeSet<>();
        private final Set<String> contexts = new HashSet<>();
        private final Set<String> declaredCodes = new TreeSet<>();
        private int writes;
        private boolean anyWithoutContext;
        private boolean anyDeclaredWithoutContext;

        Submit(int statementCount) {
            this.statementCount = statementCount;
        }

        void add(ProtectedEntityWriteRegistry.WriteRequest request) {
            writes++;
            parts.add(request.entityName() + " " + request.verb());
            String context = contextOf(request);
            if (context == null)
                anyWithoutContext = true;
            else
                contexts.add(context);
        }

        /**
         * One statement's declaration, as the caller sent it.
         *
         * <p>The CODE is named in the shape — it is an operation code, which is configuration rather
         * than anybody's data, and the whole point is to read which codes an action claims. The
         * declared context is not named, for the same reason the derived one is not: counting answers
         * the question.
         */
        void declare(WriteIntent intent) {
            if (intent == null)
                return;
            if (intent.getOperationCode() != null)
                declaredCodes.add(intent.getOperationCode());
            if (intent.isGlobal())
                contexts.add("global");
            else if (intent.getOrganizationId() != null)
                contexts.add(String.valueOf(intent.getOrganizationId())
                             + (intent.getEventId() == null ? "" : "/" + intent.getEventId()));
            else
                // A code with no context at all: refusable once anything enforces this, and worth
                // seeing now, because it is what a call site declaring the easy half looks like.
                anyDeclaredWithoutContext = true;
        }

        /**
         * The context this statement names, or null when it names none.
         *
         * <p>Read from the written VALUES, which is the only place a statement says where it belongs
         * without the row being fetched. {@code UNRESOLVED_VALUE} is the registry's sentinel for a
         * value it could not read — present, but unusable — and is treated as naming nothing rather
         * than as a context of its own.
         */
        private static String contextOf(ProtectedEntityWriteRegistry.WriteRequest request) {
            Map<String, Object> values = request.writtenValues();
            if (values == null)
                return null;
            Object organization = values.get("organization");
            if (organization == null || organization == ProtectedEntityWriteRegistry.UNRESOLVED_VALUE)
                return null;
            Object event = values.get("event");
            return event == null || event == ProtectedEntityWriteRegistry.UNRESOLVED_VALUE
                   ? String.valueOf(organization)
                   : organization + "/" + event;
        }

        /** The shape of this submit: its size, its parts, and how many contexts it named. */
        String shape() {
            return "SUBMIT n=" + statementCount
                   + (writes == statementCount ? "" : " (inspected " + writes + ")")
                   + " {" + String.join(", ", parts) + "}"
                   + " codes=" + (declaredCodes.isEmpty() ? "none" : declaredCodes.toString())
                   + (anyDeclaredWithoutContext ? "+NO-CONTEXT" : "")
                   + " contexts=" + contexts.size() + (anyWithoutContext ? "+unnamed" : "")
                   + " by " + callerClass();
        }
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

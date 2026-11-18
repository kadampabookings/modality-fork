package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import dev.webfx.stack.orm.entity.Entity;
import one.modality.crm.server.authn.gateway.shared.OperationAuthority;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Who may touch personal-data removal and anonymisation at all, asked again on EVERY call of every endpoint that
 * uses it. One audience since V0168 (removing a past event's guest data moved from the super administrator to the
 * Data Protection Officer):
 *
 * <ul>
 *   <li>{@link #whenDataProtectionOfficer} — anonymising a customer (V0153) or somebody who only booked without an
 *       account (V0163), removing a past event's guest data (V0077, V0168-V0170), retention periods: a Data
 *       Protection Officer, a person holding the {@code DataProtection} operation through a role. <b>A super
 *       administrator does NOT pass without it</b> (David, 2026-11-06 and 2026-11-17: strictly DPO); a platform super
 *       administrator can still assign the role, to anybody including themself, visibly in Authorizations.</li>
 * </ul>
 *
 * <p>The actions are irreversible — the only way back is a point-in-time restore of the whole database — and act on
 * every booking of an event, or every trace of a person, whichever organisation they belong to. The checks, in
 * order:
 *
 * <ol>
 *   <li><b>Not a support view.</b> A support view carries the VIEWED member's person id in its principal, so
 *       every check after this one would be asked about the wrong person; the agent behind it acts through
 *       their own session or not at all. Asked first only so the log can name the agent: the next check
 *       refuses a support view too.</li>
 *   <li><b>A back-office caller</b>, as {@link RouteAccessGuard#backOfficeCallerPersonId} — the fence every
 *       back-office endpoint stands behind — decides it: a registered user (a guest principal and an
 *       anonymous caller are refused), on a session this server established (a session family, not an
 *       identity a caller merely asserted), whose token says it was opened for the back office, and naming a
 *       person.</li>
 *   <li>The audience:
 *     <ul>
 *       <li><b>the DataProtection operation</b>, asked of the authorization framework through
 *           {@link OperationAuthority} (admin-tiers plan, Phase 3): held for a whole organisation, in some
 *           organisation (a grant scoped to one event or event type is not an answer about all of them), on an access
 *           row that is not read-only. A super administrator's {@code operation:*} does not cover it because
 *           DataProtection is explicit-only (V0166) — which is why that flag is CHECKED here on every call
 *           ({@link #dataProtectionStaysExplicitOnly}): cleared, every super administrator would pass. Every endpoint
 *           behind {@link #whenDataProtectionOfficer} asks it FRESH, from grants recomputed for the call — the old SQL
 *           was live on every call, and a cached answer would let a Data Protection Officer whose role was just
 *           revoked keep finding people and previewing their data for up to two minutes. Two kinds of call read the
 *           cache: {@link #isDataProtectionOfficer}, which decides what the browser draws and is asked on every page,
 *           and {@link #whenDataProtectionOfficerReadingStatus}, the removal-run status the page polls every few
 *           seconds while a removal runs (no person in it, nothing changed by it). Until 2026-11-18 this asked the role tables in SQL ({@code RoleOperationMembership}), a second
 *           copy of the grant rules.</li>
 *     </ul></li>
 * </ol>
 *
 * <p>Every refusal is {@link RouteAccessGuard#refused()}: the caller learns that it was refused, and no more —
 * in particular not which check said no. The log says which, by person id only (never an email): it ships to
 * aggregation. A membership lookup that fails is a no.
 *
 * <p>The caller's state is read on the CALLING thread, synchronously, before the first asynchronous step:
 * {@link ThreadLocalStateHolder} is restored as soon as the synchronous part of the bus call returns.
 *
 * @author Claude Code
 */
final class EventErasureAccess {

    private EventErasureAccess() {
    }

    private static final String LOG_PREFIX = "[event-erasure] ";

    /** The operation a Data Protection Officer's role carries (seeded by V0163). */
    static final String DATA_PROTECTION_OPERATION = "DataProtection";

    /**
     * The staff member a call was allowed for.
     *
     * @param personId their person id, normalised to an Integer (a principal decoded from the wire may carry a
     *                 Byte or a Long for the same person) — written into the run ledger and the history
     *                 markers, and named in the log
     */
    record Caller(Integer personId) {
    }

    /** A back-office caller, before the audience check: their principal and person id. */
    private record BackOfficeCaller(ModalityUserPrincipal principal, Integer personId) {
    }

    /**
     * Runs {@code work} for a Data Protection Officer and refuses everybody else, super administrators without the
     * role included. MUST be called on the caller's thread (see the class comment).
     *
     * @param operation which endpoint is asking, for the log line of a refusal
     * @param work      what to do once allowed — not started otherwise
     */
    static <T> Future<T> whenDataProtectionOfficer(String operation, Function<Caller, Future<T>> work) {
        // Fresh: these screens are few and seldom used, and each one shows or changes personal data — a revoked
        // role must stop answering at once (see the class comment). A few queries per call is the price.
        return when(operation, DATA_PROTECTION_OPERATION, "does not hold " + DATA_PROTECTION_OPERATION,
            caller -> holdsDataProtection(true), work);
    }

    /**
     * {@link #whenDataProtectionOfficer} from the CACHED grants, for the removal-run status a page polls: the run
     * (every 3 s while one runs) and the runs list (every minute). They show ids, counts and dates — no person — and
     * change nothing, so a revocation reaching them up to two minutes late costs nothing, while a fresh grant
     * computation on every poll would cost several queries each time. Anything that acts, or shows or finds a person,
     * stays on {@link #whenDataProtectionOfficer}. The flag guard runs either way.
     */
    static <T> Future<T> whenDataProtectionOfficerReadingStatus(String operation, Function<Caller, Future<T>> work) {
        return when(operation, DATA_PROTECTION_OPERATION, "does not hold " + DATA_PROTECTION_OPERATION,
            caller -> holdsDataProtection(false), work);
    }

    /**
     * Whether the caller is a Data Protection Officer, for the browser to decide what to draw — never a refusal:
     * anybody who is not (or not on a back-office session) is simply told no, and no refusal is logged (a failed
     * lookup still is). The endpoints that act still ask {@link #whenDataProtectionOfficer} themselves. MUST be
     * called on the caller's thread.
     */
    static Future<Boolean> isDataProtectionOfficer() {
        BackOfficeCaller caller = backOfficeCaller("access", false);
        return caller == null ? Future.succeededFuture(false)
            : failClosed(() -> holdsDataProtection(false), "access", DATA_PROTECTION_OPERATION, caller.personId());
    }

    /** The gate both audiences share: the caller checks, then the audience's membership, failing closed. */
    private static <T> Future<T> when(String operation, String check, String refusal,
                                      Function<BackOfficeCaller, Future<Boolean>> membership, Function<Caller, Future<T>> work) {
        BackOfficeCaller caller = backOfficeCaller(operation, true);
        if (caller == null)
            return RouteAccessGuard.refused();
        return failClosed(() -> membership.apply(caller), operation, check, caller.personId())
            .compose(allowed -> allowed
                ? work.apply(new Caller(caller.personId()))
                : refuse(operation, "person " + caller.personId() + " " + refusal));
    }

    /**
     * The first two checks of the class comment; null when either refuses. A support view is refused by
     * {@link RouteAccessGuard#backOfficeCallerPersonId} too; it is asked first only so the log can name the agent.
     *
     * @param logRefusal whether a refusal is logged — not for {@link #isDataProtectionOfficer}, asked on every page
     */
    private static BackOfficeCaller backOfficeCaller(String operation, boolean logRefusal) {
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        ModalityUserPrincipal principal = userId instanceof ModalityUserPrincipal p ? p : null;
        String refusal = null;
        Integer personId = null;
        if (principal != null && principal.isSupportView())
            // The agent's id, not the viewed member's: the member did not ask for anything
            refusal = "support view (agent person " + principal.getSupportAgentPersonId() + ")";
        else {
            // The decision is the shared fence's; the log line only says whose call it was
            Object callerPersonId = RouteAccessGuard.backOfficeCallerPersonId(state);
            if (callerPersonId == null || principal == null)
                refusal = principal == null ? "no signed-in user"
                    : "person " + principal.getUserPersonId() + " is not on a verified back-office session";
            else if ((personId = Numbers.toInteger(callerPersonId)) == null)
                refusal = "the principal names no person";
        }
        if (refusal == null)
            return new BackOfficeCaller(principal, personId);
        if (logRefusal)
            logRefusal(operation, refusal);
        return null;
    }

    /**
     * Whether the caller holds DataProtection — the framework's answer AND the guard on the operation itself. The
     * framework's question is started first, synchronously: it reads the caller from the thread-local state, which is
     * gone after the first asynchronous step.
     */
    private static Future<Boolean> holdsDataProtection(boolean fresh) {
        if (!OperationAuthority.isRegistered()) // still refused below; said here, because nothing else would say it
            Console.log(LOG_PREFIX + "refused: no operation authority is registered (is the authz plugin running?)");
        Future<Boolean> held = OperationAuthority.holdsInSomeOrganization(DATA_PROTECTION_OPERATION, fresh);
        Future<Boolean> guarded = dataProtectionStaysExplicitOnly(EntityStore.create(DataSourceModelService.getDefaultDataSourceModel()));
        return Future.all(held, guarded).map(both -> Boolean.TRUE.equals(both.resultAt(0)) && Boolean.TRUE.equals(both.resultAt(1)));
    }

    /**
     * Whether every operation coded DataProtection is still explicit-only, and neither public nor guest — the flags
     * the framework's answer depends on. Explicit-only is all that keeps a super administrator's {@code operation:*}
     * from covering it (V0166); public or guest would hand it to everybody signed in. Codes are not unique (a back-
     * and a front-office row may share one), so every row with the code must hold, and there must be at least one.
     * Asked on every call: a flag cleared by hand must close the door at once, not at the next deploy.
     */
    static Future<Boolean> dataProtectionStaysExplicitOnly(EntityStore entityStore) {
        return entityStore.<Entity>executeQuery("select explicitOnly, public, guest from Operation where operationCode=$1", DATA_PROTECTION_OPERATION)
            .map(rows -> {
                if (rows.isEmpty()) {
                    Console.log(LOG_PREFIX + "refused: no " + DATA_PROTECTION_OPERATION + " operation exists");
                    return false;
                }
                for (Entity row : rows) {
                    if (!Boolean.TRUE.equals(row.getBooleanFieldValue("explicitOnly"))
                        || Boolean.TRUE.equals(row.getBooleanFieldValue("public"))
                        || Boolean.TRUE.equals(row.getBooleanFieldValue("guest"))) {
                        Console.log(LOG_PREFIX + "refused: the " + DATA_PROTECTION_OPERATION
                                    + " operation is not explicit-only, or is public or guest — every super administrator would pass");
                        return false;
                    }
                }
                return true;
            });
    }

    /**
     * A membership lookup, failing closed: one that throws or fails is not a yes, and the database's words go to the
     * log.
     */
    private static Future<Boolean> failClosed(Supplier<Future<Boolean>> lookup, String operation, String check, Integer personId) {
        Future<Boolean> membership;
        try {
            membership = lookup.get();
        } catch (RuntimeException e) {
            membership = Future.failedFuture(e);
        }
        return membership
            .recover(cause -> {
                Console.log(LOG_PREFIX + operation + ": the " + check + " check failed for person " + personId
                            + " (" + (cause == null ? null : cause.getMessage()) + ")");
                return Future.succeededFuture(false);
            })
            .map(Boolean.TRUE::equals);
    }

    private static void logRefusal(String operation, String why) {
        Console.log(LOG_PREFIX + operation + " refused: " + why);
    }

    private static <T> Future<T> refuse(String operation, String why) {
        logRefusal(operation, why);
        return RouteAccessGuard.refused();
    }
}

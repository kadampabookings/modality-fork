package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Ticking payments verified, and taking the tick back: the server half of
 * {@code DocumentService.verifyMoneyTransfers}, for the back office's Finance › Payments and Finance › Statements
 * pages.
 *
 * <p><b>Why an endpoint.</b> No client may write {@code money_transfer} (ClientWriteSecrets.denyTable, 2026-10-19):
 * a client choosing a payment row's columns could mark its own booking paid. The tick still has to be made by
 * somebody, so it is a named act here, granted by its own operation and scoped to one organisation, rather than a
 * column write a browser composes.
 *
 * <p><b>Fences, in order.</b> The caller's state is read on the caller's thread (by the provider). Then:
 * <ol>
 *   <li>a verified back-office session ({@link VerifiedBackoffice}) of a person of their own —
 *       {@link RouteAccessGuard#backOfficeCallerPersonId}, which also refuses a support view, a guest and a session
 *       this server did not establish. Before anything is read, so an anonymous caller cannot make the server
 *       look ids up;</li>
 *   <li>the shape: 1 to {@link #MAX_IDS} positive ids (duplicates collapse) — the bus endpoint has checked it
 *       already; this is the same fence for any other caller of the service;</li>
 *   <li>{@link #VERIFY_PAYMENTS} held for the WHOLE organisation: {@code eventId} null matches only a grant with
 *       no event and no event type (RoleOperationMembership.TARGET_SCOPE_CLAUSE). A grant for one event does not
 *       reach the organisation's payments, whose accounts span all its events. Super administrators pass, as
 *       through every RouteAccessGuard check.</li>
 * </ol>
 *
 * <p><b>The server chooses the rows.</b> One query reads the ids that belong to the organisation — through the
 * account the money went to, the organisation's own or one of its events', the pin the React pages read with —
 * and puts each in a bucket. An id it does not return is {@code notFound}, whether it does not exist or belongs
 * to another organisation: the two answer alike, so the endpoint is no oracle for other centres' payments.
 * A split payment's child and a contra row carry no account (none of 83,113 children and 13,025 contra rows on
 * staging, 2026-10-28), so today they answer {@code notFound} too, and {@code ineligible} is the backstop for one
 * that ever does. Either way they are refused.
 *
 * <p><b>Classified before, never counted after.</b> {@code SubmitResult.getRowCount()} counts RowSets, not rows
 * (see MateInviteTokenStore), so the counts come from the classification. The UPDATE re-asserts every condition
 * the classification applied — the organisation, eligibility, the payout amount, the closed event — so a row that
 * changed between the two statements is left alone rather than written; it is then reported as changed while
 * still as it was, which the page's own re-read corrects.
 *
 * <p><b>The verifier is the database's.</b> The UPDATE runs as the server acting for the caller
 * ({@link #asServerActingFor}, as ServerWrite does in the person plugin): the client origin is dropped and the
 * principal kept, so the submit path stamps {@code kbs.audit_person_id} and V0149's {@code stamp_verification}
 * trigger records {@code verified_date}, {@code verifier_person_id} and the {@code verifier} name. Nothing the
 * browser sends names a verifier. It must go through {@code executeSubmitBatch}: the single-statement
 * {@code executeSubmit} sets no audit context.
 *
 * <p><b>The lock is here, not in the database</b> (David, 2026-10-28): a tick may be taken back until the event
 * is reconciled. KBS2's own screens write the column directly and are not held by it.
 *
 * @author Claude Code
 */
final class MoneyTransferVerification {

    /** Seeded by V0149 with no {@code grant_route}: it gates a control, not a page (V0106's precedent). */
    static final String VERIFY_PAYMENTS = "VerifyPayments";

    /** The most ids one call may carry; the client sends larger selections in chunks. */
    static final int MAX_IDS = 1000;

    static final String MALFORMED = "[VerifyPaymentsError] Malformed request";

    /** What the page is told when the database refuses a payout tick it cannot square with its amount. */
    static final String PAYOUT_AMOUNT_MISMATCH = "[PayoutAmountMismatchError] A payout's amount is not the amount expected";

    /** Any other failure of the write: the database's own message stays in the server log. */
    static final String NOT_UPDATED = "[VerifyPaymentsError] The payments could not be updated";

    /** KBS2's {@code on_verified} trigger raises this when a payout whose amount is not the expected one is ticked. */
    static final String ON_VERIFIED_REFUSAL = "Can't mark a transfer with error as verified";

    private static final String LOG_PREFIX = "[VerifyPayments] ";

    /** What each id turned out to be. */
    enum Bucket { CHANGE, UNCHANGED, MISMATCH, FROZEN, INELIGIBLE }

    // ── The statements ─────────────────────────────────────────────────────────────────────────────────────────
    //
    // Assembled from the constants below only. The ids, the organisation and the direction are bound as parameters;
    // nothing a caller sends is concatenated into SQL. Raw SQL rather than DQL: these read and write across the
    // payment, its children, its batch members and their bookings' events in one set-based statement each.

    /** A split payment's child, or a contra row (method 6): never verified on its own. */
    static final String NEVER_VERIFIED = "(mt.parent_id is not null or mt.method_id = 6)";

    /** A payout whose amount is not the one expected: KBS2's {@code on_verified} refuses to tick it. */
    static final String PAYOUT_AMOUNT_ERROR =
        "(mt.receipts_transfer and mt.expected_amount is not null and mt.expected_amount <> mt.amount)";

    /**
     * Whether a payment reaches an event whose accounts are closed (RECONCILED, FINALISED or ARCHIVED) — through
     * its own booking, its split children's, or, for a payout batch, its members' and their children's. These are
     * exactly the rows an untick re-writes: {@code on_verified} unticks a batch's members, and
     * {@code cascade_children_verified} (V0148) a split payment's children.
     *
     * <p>Driven from the payment's side — index lookups on {@code parent_id}, {@code transfer_id} and the bookings'
     * primary keys. Written the other way round ("the bookings of closed events, one of which is this payment's"),
     * Postgres would read every booking of every closed event for each payment.
     */
    static final String REACHES_CLOSED_EVENT = "exists(select 1 from ("
        + "select mt.document_id"
        + " union all select c.document_id from money_transfer c where c.parent_id = mt.id"
        + " union all select m.document_id from money_transfer m where m.transfer_id = mt.id"
        + " union all select mc.document_id from money_transfer m join money_transfer mc on mc.parent_id = m.id"
        + " where m.transfer_id = mt.id"
        + ") reached(document_id)"
        + " join document d on d.id = reached.document_id"
        + " join event e on e.id = d.event_id"
        + " where e.state in ('RECONCILED', 'FINALISED', 'ARCHIVED'))";

    /**
     * The organisation's payments: the account the money went to is the organisation's own or one of its events'.
     * The same pin as the React pages' {@code organizationAccounts} — through sub-selects, so an account that
     * belongs to no event is kept.
     *
     * @param parameter the placeholder holding the organisation id — a constant, never input
     */
    static String ofOrganization(String parameter) {
        return "mt.to_money_account_id in (select ma.id from money_account ma where ma.organization_id = " + parameter
               + " or ma.event_id in (select ev.id from event ev where ev.organization_id = " + parameter + "))";
    }

    /**
     * {@code $1} the ids, {@code $2} the organisation. One row per id of the organisation:
     * id, verified, never-verified, payout amount error, reaches a closed event. Only the test the direction
     * needs is run; the other column reads false.
     */
    static String classifySql(boolean verifying) {
        return "select mt.id, mt.verified, " + NEVER_VERIFIED
               + ", " + (verifying ? PAYOUT_AMOUNT_ERROR : "false")
               + ", " + (verifying ? "false" : REACHES_CLOSED_EVENT)
               + " from money_transfer mt where mt.id = any($1) and " + ofOrganization("$2");
    }

    /**
     * {@code $1} the ids to change, {@code $2} the direction, {@code $3} the organisation. Every condition of the
     * classification is asserted again, so the statement is safe on its own.
     */
    static String updateSql(boolean verifying) {
        return "update money_transfer mt set verified = $2"
               + " where mt.id = any($1) and mt.verified is distinct from $2"
               + " and " + ofOrganization("$3")
               + " and not " + NEVER_VERIFIED
               + " and not " + (verifying ? PAYOUT_AMOUNT_ERROR : REACHES_CLOSED_EVENT);
    }

    private MoneyTransferVerification() {
    }

    // ── The decision ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Ticks ({@code verified} true) or unticks the organisation's payments among {@code transferIds}, and answers
     * the counts as JSON — see {@link Tally#json()}.
     *
     * @param callerState the caller's state, captured on the caller's thread before any async step
     */
    static Future<String> verify(Object callerState, Object organizationId, Object[] transferIds, boolean verified) {
        // 1. Who — before anything is read.
        Object callerPersonId = RouteAccessGuard.backOfficeCallerPersonId(callerState);
        if (!VerifiedBackoffice.of(callerState) || callerPersonId == null)
            return RouteAccessGuard.refused();
        // 2. What — refused, never defaulted.
        Integer organization = positiveInt(organizationId);
        Integer[] ids = distinctIds(transferIds);
        if (organization == null || ids == null)
            return Future.failedFuture(MALFORMED);
        // 3. May — over the whole organisation. The principal is kept for the write: it is who the trigger stamps.
        Object callerUserId = StateAccessor.getUserId(callerState);
        return RouteAccessGuard.whenCallerHoldsOperation(callerState, VERIFY_PAYMENTS, organization, null,
            () -> classifyThenUpdate(organization, ids, verified, callerUserId, callerPersonId));
    }

    private static Future<String> classifyThenUpdate(Integer organization, Integer[] ids, boolean verifying,
                                                     Object callerUserId, Object callerPersonId) {
        return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(classifySql(verifying))
                .setParameters((Object) ids, organization)
                .build()))
            .compose(rows -> {
                Tally tally = new Tally();
                List<Integer> change = classify(rows, verifying, tally);
                // A QUERY's row count is real rows; every id comes back at most once (primary key, distinct ids).
                tally.notFound = ids.length - rows.getRowCount();
                if (change.isEmpty())
                    return Future.succeededFuture(logged(tally, verifying, callerPersonId));
                SubmitArgument update = new SubmitArgumentBuilder()
                    .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                    .setStatement(updateSql(verifying))
                    .setParameters((Object) change.toArray(new Integer[0]), verifying, organization)
                    .build();
                // A batch of one, not executeSubmit: only the batch path stamps kbs.audit_person_id.
                return asServerActingFor(callerUserId, () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { update })))
                    .map(ignored -> logged(tally, verifying, callerPersonId))
                    .recover(failure -> {
                        String refusal = failureMessage(failure);
                        Console.log(LOG_PREFIX + "Update refused for person " + callerPersonId + ": " + refusal);
                        return Future.failedFuture(refusal);
                    });
            });
    }

    /** Puts each returned row in its bucket and answers the ids to change. */
    private static List<Integer> classify(QueryResult rows, boolean verifying, Tally tally) {
        List<Integer> change = new ArrayList<>();
        for (int row = 0; row < rows.getRowCount(); row++) {
            // Through Object locals, NEVER straight into a typed call: getValue is generic, and an inferred
            // type there is a cast that can fail (see MateInviteTokenStore.revokeInvitations).
            Object id = rows.getValue(row, 0);
            Object verifiedNow = rows.getValue(row, 1);
            Object neverVerified = rows.getValue(row, 2);
            Object amountError = rows.getValue(row, 3);
            Object closedEvent = rows.getValue(row, 4);
            Bucket bucket = bucketOf(verifying, Boolean.TRUE.equals(verifiedNow), Boolean.TRUE.equals(neverVerified),
                Boolean.TRUE.equals(amountError), Boolean.TRUE.equals(closedEvent));
            tally.add(bucket);
            if (bucket == Bucket.CHANGE)
                change.add(((Number) id).intValue());
        }
        return change;
    }

    /**
     * Which bucket a payment of the organisation falls in. Pure.
     *
     * <p>In this order: a row that is never verified says so even when nothing would change; a row already as
     * asked is unchanged, whatever else is true of it — taking back a tick that is not there writes nothing, so it
     * is not refused for a closed event; then the test of the direction.
     */
    static Bucket bucketOf(boolean verifying, boolean verifiedNow, boolean neverVerified, boolean payoutAmountError,
                           boolean reachesClosedEvent) {
        if (neverVerified)
            return Bucket.INELIGIBLE;
        if (verifiedNow == verifying)
            return Bucket.UNCHANGED;
        if (verifying && payoutAmountError)
            return Bucket.MISMATCH;
        if (!verifying && reachesClosedEvent)
            return Bucket.FROZEN;
        return Bucket.CHANGE;
    }

    /** What the caller is told about a failed write. Pure. */
    static String failureMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message != null && message.contains(ON_VERIFIED_REFUSAL) ? PAYOUT_AMOUNT_MISMATCH : NOT_UPDATED;
    }

    /**
     * The distinct ids, or null unless {@code transferIds} holds 1 to {@link #MAX_IDS} values that are all
     * positive {@code int}s. Duplicates collapse, so the reply's counts add up to the number of DISTINCT ids.
     */
    static Integer[] distinctIds(Object[] transferIds) {
        if (transferIds == null || transferIds.length < 1 || transferIds.length > MAX_IDS)
            return null;
        Set<Integer> ids = new LinkedHashSet<>();
        for (Object value : transferIds) {
            Integer id = positiveInt(value);
            if (id == null)
                return null;
            ids.add(id);
        }
        return ids.toArray(new Integer[0]);
    }

    /** A positive {@code int} from any integral number; null for anything else. */
    static Integer positiveInt(Object value) {
        if (!(value instanceof Number number))
            return null;
        double d = number.doubleValue();
        if (d < 1 || d > Integer.MAX_VALUE || d != Math.floor(d))
            return null;
        return number.intValue();
    }

    /** The census line — counts, the direction and who; never a payment id, an amount or a name. */
    private static String logged(Tally tally, boolean verifying, Object callerPersonId) {
        String json = tally.json();
        Console.log(LOG_PREFIX + (verifying ? "Ticked" : "Unticked") + " by person " + callerPersonId + ": " + json);
        return json;
    }

    /** The counts the reply carries. */
    static final class Tally {
        int changed, unchanged, mismatch, frozen, ineligible, notFound;

        void add(Bucket bucket) {
            switch (bucket) {
                case CHANGE -> changed++;
                case UNCHANGED -> unchanged++;
                case MISMATCH -> mismatch++;
                case FROZEN -> frozen++;
                case INELIGIBLE -> ineligible++;
            }
        }

        /** {@code {"changed":n,"unchanged":n,"mismatch":n,"frozen":n,"ineligible":n,"notFound":n}} — numbers only. */
        String json() {
            return "{\"changed\":" + changed + ",\"unchanged\":" + unchanged + ",\"mismatch\":" + mismatch
                   + ",\"frozen\":" + frozen + ",\"ineligible\":" + ineligible + ",\"notFound\":" + notFound + "}";
        }
    }

    // ── As whom ────────────────────────────────────────────────────────────────────────────────────────────────

    /** For reads, which have nothing to audit and no reason to name anybody. */
    private static <T> T asServer(Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
    }

    /**
     * For the write: the server's origin, the caller's name — what {@code ServerWrite.asServerActingFor} does in
     * the person plugin, and what submitDocumentChanges does for its own writes.
     *
     * @param callerUserId read on the caller's own thread, before the first async step
     */
    private static <T> T asServerActingFor(Object callerUserId, Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.setUserId(StateAccessor.createEmptyState(), callerUserId), call);
    }
}

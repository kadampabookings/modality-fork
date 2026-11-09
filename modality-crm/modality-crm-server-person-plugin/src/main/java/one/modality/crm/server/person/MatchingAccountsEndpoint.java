package one.modality.crm.server.person;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.CallerRateLimit;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * "Which of these addresses have accounts, and have I already invited them?" — ids and nothing else.
 *
 * <p>Argument: the addresses, as {@code [address, address, …]}. Reply: an {@link AstObject} keyed by the
 * lower-cased addresses that HAVE an account, each mapping to {@code {id}}. An address
 * with no account is simply absent, which is the whole of what a caller learns about it.
 *
 * <p><b>The reply reaches the client WRAPPED, as {@code {$codec: 'AstNode', v: {…}}}</b>, not as a bare
 * object: an AstObject crosses the bus through its codec like anything else. A caller that reads the outer
 * object as the map finds two keys named {@code $codec} and {@code v} and concludes that nobody has an
 * account — which is what happened on the first real call, and shows as every member losing the badge rather
 * than as an error. Unwrap {@code v}.
 *
 * <p><b>On the budget, measured rather than assumed.</b> A members-page load asks about one address at the
 * median on staging, three at the 95th centile, five at the 99th — and 27 in the worst case, against a budget
 * of 30 per ten minutes. That budget is SHARED with {@link EmailHasAccountEndpoint} and the invite form's own
 * check, so a heavy load does not merely risk its own second refresh: it can starve the invite the member
 * then tries to send. Two staging accounts are over fifteen unlinked members and none over thirty. The cheap
 * answer is for the caller not to re-ask per render; if that is not enough, this wants a budget of its own
 * rather than a larger shared one.
 *
 * <p><b>It answers existence and identity, and deliberately nothing about invitations.</b> It briefly carried
 * pending flags; they are gone. The first version had one, which was wrong for both callers — an invitation
 * carries {@code inviter_payer}, the members page excludes a member only on a pending SHARE request
 * ({@code true}) and the invite manager asks about a manager invitation ({@code false}), so one boolean
 * covering either would have hidden a member card over an outstanding "book for me". Splitting it in two
 * fixed that and left two fields nothing called. They are cut rather than kept on spec: a reply field with no
 * consumer is a contract no test exercises, and that is exactly where the first bug was — the argument
 * parsing had nineteen probed cases, the reply had none. Pending stays where it already works, in each
 * caller's own {@code inviter=$1} invitation query.
 *
 * <h3>What it replaces, and why an endpoint rather than a rule</h3>
 *
 * <p>The members page and the booked-member linking matched a member's address against other accounts with a
 * client statement: {@code select id,firstName,lastName,email,… from Person where owner=true and
 * frontendAccount!=$1 and lower(frontendAccount.username) in $3}. The names came out of it long after the
 * display that used them was removed — one still reached an outbound email greeting — and narrowing the
 * columns (kbs3-react, the profile-menu work) left the real problem: the shape itself.
 *
 * <p><b>That shape is a batch, unmetered copy of a metered question.</b>
 * {@link EmailHasAccountEndpoint} answers "is there an account for this address?" under
 * {@link CallerRateLimit#EMAIL_LOOKUP} — 30 per ten minutes per caller — because a yes/no is a membership
 * oracle. The client statement answers the same question with no limit at all and in bulk, since
 * {@code in $3} takes an array the caller chooses. It is the pattern {@code NoPrincipalReadPolicy} records
 * one tier down: an endpoint does not close an oracle while the raw statement still runs.
 *
 * <p>So this is not a convenience wrapper. It exists so that the read rule can say "another account's person
 * rows are not readable by a member" with no carve-out for ids and usernames — a rule nobody has to be told
 * the exception to.
 *
 * <h3>The metering is per ADDRESS, not per call</h3>
 *
 * <p>A batch endpoint on a per-call limit is the same amplification wearing a uniform: thirty calls of a
 * hundred addresses is three thousand answers on a budget of thirty. Each address spends one permit, so this
 * can never answer more per budget than the same questions asked one at a time. Permits already taken stay
 * taken when a later one is refused, which is what a limiter under pressure is for.
 *
 * @author Claude Code
 */
public final class MatchingAccountsEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.MATCHING_ACCOUNTS. */
    public static final String MATCHING_ACCOUNTS_ADDRESS = "modality/service/person/matchingAccounts";

    static final String BAD_REQUEST_KEY = "MatchingAccountsBadRequestError";

    /**
     * The most addresses one call may ask about. A household's unlinked members are a handful; this is
     * generous for that and still a bound, which an array from a caller otherwise is not. It is a second
     * limit, not the limit — the per-address metering above is what stops the budget being multiplied.
     */
    static final int MAX_ADDRESSES = 50;

    /**
     * Ids and the caller's own addresses back, never a name.
     *
     * <p><b>{@code min(p.id)} and {@code group by}, because an account can carry MORE THAN ONE
     * {@code owner=true} person row</b> — five accounts do on staging. Keyed by address, a second row would
     * otherwise silently overwrite the first and which one won would depend on scan order. The lowest id is
     * chosen so that two calls agree.
     *
     * <p>{@code fa.id <> $2} excludes the caller's own account: a member matching their own address against
     * their own account learns nothing, and leaving it in would answer "which of my own rows are owners?"
     * through a path meant for other people's.
     */
    private static final String MATCHING_SQL =
        "select lower(fa.username) as username, min(p.id) as person_id"
        + " from frontend_account fa"
        + " join person p on p.frontend_account_id = fa.id"
        + " where fa.corporation_id = 1 and p.owner = true and p.removed = false"
        + " and fa.id <> $2 and lower(fa.username) = any($1)"
        + " group by lower(fa.username)";

    public MatchingAccountsEndpoint() {
        super(MATCHING_ACCOUNTS_ADDRESS, argument -> {
            String[] addresses = addressesOf(argument);
            if (addresses == null)
                return Future.failedFuture("[" + BAD_REQUEST_KEY + "] Not a list of email addresses");
            // Read on the caller's thread: the stamp is the bridge's and is gone after the first async step.
            String clientAddress = ThreadLocalStateHolder.getClientAddress();
            // Spent only once the caller is known to be a verified member, so an anonymous call cannot use
            // up anyone's budget — the order CreateInvitationEndpoint settled on for the same reason.
            // personId is supplied by the guard and no longer used: the statement stopped binding it when the
            // pending flags went, and only the ACCOUNT is needed, to exclude the caller's own rows.
            return MemberSessionGuard.whenCallerIsVerifiedMember((personId, accountId) -> {
                for (int i = 0; i < addresses.length; i++)
                    if (!CallerRateLimit.EMAIL_LOOKUP.tryAcquire(clientAddress))
                        return Future.failedFuture("[" + EmailHasAccountEndpoint.RATE_LIMITED_KEY
                                                   + "] Too many address checks, please try again later");
                return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                    .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                    .setStatement(MATCHING_SQL)
                    .setParameters(addresses, accountId)
                    .build())
                ).map(MatchingAccountsEndpoint::toReply);
            });
        });
    }

    /**
     * The lower-cased, de-duplicated addresses from {@code [address, …]}, or null when the argument is not
     * a list of them.
     *
     * <p>De-duplicated before the permits are counted, so a caller cannot be charged twice for one question
     * — and cannot send the same address fifty times to look like a bigger batch than it is. Validated with
     * {@link EmailHasAccountEndpoint#emailOf}, so one address is judged the same here as there. Pure.
     */
    static String[] addressesOf(Object argument) {
        Object[] array = argument instanceof Object[] a ? a : null;
        if (array == null || array.length == 0 || array.length > MAX_ADDRESSES)
            return null;
        Set<String> addresses = new LinkedHashSet<>();
        for (Object element : array) {
            String address = EmailHasAccountEndpoint.emailOf(new Object[] { element });
            if (address == null)
                return null; // one bad address refuses the call rather than being skipped silently
            addresses.add(address.toLowerCase());
        }
        return addresses.toArray(new String[0]);
    }

    /** The matched rows as {@code {address: {id}}}. Absent means no account, which is the answer. */
    private static AstObject toReply(dev.webfx.stack.db.query.QueryResult result) {
        AstObject reply = AST.createObject();
        if (result != null)
            for (int row = 0; row < result.getRowCount(); row++) {
                Object username = result.getValue(row, 0);
                if (username == null)
                    continue;
                // setScalar, not set: the set(String, …) overloads are ambiguous for a bare Object, and
                // picking one by casting would decide the wire type by which cast was written. The id is
                // whatever the driver returned for min(p.id) — a Number of some width — so it is passed as
                // the scalar it is rather than narrowed here.
                AstObject match = AST.createObject();
                match.setScalar("id", result.getValue(row, 1));
                reply.setObject(String.valueOf(username), match);
            }
        return reply;
    }
}

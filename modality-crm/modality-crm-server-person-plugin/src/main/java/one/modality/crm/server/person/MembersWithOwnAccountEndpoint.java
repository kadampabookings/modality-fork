package one.modality.crm.server.person;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

/**
 * "Which of my own members already have an account of their own?" — the booking form's badge.
 *
 * <p>Takes no argument. Reply: {@code {personIds: [...]}} — the ids of <b>this caller's own</b>
 * unlinked member rows whose address is the login of some other account. The booking wizard badges
 * those {@code NEEDS_VALIDATION}, because booking for them should go through the link rather than
 * silently duplicating a person who already exists.
 *
 * <h3>The reply is the thinnest it can be, and that is the point</h3>
 *
 * <p>What this replaces returned <b>other people's login addresses</b>:
 * {@code select id,frontendAccount.username from Person where owner=true and frontendAccount!=$1 and
 * lower(frontendAccount.username) in $2}, with {@code $2} an array of addresses the CALLER chose. The
 * client then folded both sides and matched. So it was the same membership oracle
 * {@link EmailHasAccountEndpoint} meters one question at a time — asked in bulk, unmetered, and
 * answering with the matched address itself.
 *
 * <p>This answers it with ids of rows the caller can already read — rows on their own account, which
 * {@code PersonReadScope}'s first arm permits. Nothing about the other account crosses the wire: not its
 * id, not its holder, not the address that matched.
 *
 * <h3>What this is NOT, and the sentence not to inherit from its siblings</h3>
 *
 * <p>{@link ClaimableMembersEndpoint} and {@link ManagerAccountOwnersEndpoint} are safe because their
 * derived input cannot be chosen: an address only the server writes, and a link the caller cannot create
 * alone. <b>This endpoint does not have that property, and must not be read as though it does.</b> Its
 * input is {@code person.email} on the caller's own member rows, and a member writes that at will:
 * {@code PersonFields} makes {@code email} client-settable, {@code PersonDetailsRules.whereClause}
 * permits it on any non-owner row of the caller's own account — exactly the rows selected below — and
 * neither {@code AddMemberEndpoint} nor {@code UpdatePersonDetailsEndpoint} spends a rate-limit permit.
 *
 * <p>So a member can add rows carrying any addresses they like, call this once, and read back which of
 * those addresses hold an account. That is the membership oracle {@link EmailHasAccountEndpoint} meters
 * at 30 per ten minutes, in bulk, with the meter removed — the amplification
 * {@link MatchingAccountsEndpoint} describes as "the same amplification wearing a uniform" and solves by
 * spending one permit per address.
 *
 * <p><b>Why it ships anyway, and what that is contingent on.</b> It grants nothing today:
 * {@code FrontendAccount} is itself read-unscoped — {@code PersonReadScope} narrows {@code Person} alone
 * and {@code ClientReadSecrets} denies only the password columns — so one DQL statement already returns
 * every login at once. See {@code docs/security/read-authorization-plan.md}, which records that.
 *
 * <p><b>The danger is that it outlives that.</b> This runs under {@code ServerWrite.asServer} and raw
 * {@code QueryService}, so no read scope is ever injected into it. When the {@code FrontendAccount} arm
 * lands and every ordinary route closes, this one keeps answering. <b>Metering it, or re-deriving it so
 * the addresses stop being caller-chosen, belongs in that same change.</b> Metering it on
 * {@code CallerRateLimit.EMAIL_LOOKUP} is not obviously right: that budget is shared with the invite
 * form and already measured near exhaustion (27 addresses against 30 at the staging worst case), so a
 * booking form spending from it could starve a real invitation. A budget of its own is the likelier
 * answer — which is the remedy {@code MatchingAccountsEndpoint}'s own note proposes for itself.
 *
 * <h3>Why it exists at all</h3>
 *
 * <p>Scoping {@code Person} to the caller's own account made the old statement return zero rows, so
 * every member stopped being badged. It failed silently: the surrounding {@code try/catch} catches
 * throws, and an empty result is indistinguishable from "nobody here has their own account". This was
 * the fourth front-office read the scope closed, found during review of the first three.
 *
 * <h3>Shape, measured rather than assumed</h3>
 *
 * <p>Written as a JOIN and not as a correlated {@code exists}. On staging the {@code exists} form
 * builds a hash of all 49,650 owner rows joined to all 49,664 accounts before testing anything
 * ({@code 531 ms} for the worst account); the join below hashes the caller's own member rows — one at
 * the median, seventeen at the worst — and streams {@code frontend_account} past it once
 * ({@code 134 ms}). Neither can use an index: the comparison is on
 * {@code lower(canonical_email_domain(...))}, and the unique index on {@code (corporation_id,
 * username)} cannot serve an expression. {@link MatchingAccountsEndpoint} pays the same cost for the
 * same reason.
 *
 * <p>{@code public.canonical_email_domain} (V0173) folds {@code @googlemail.com} into
 * {@code @gmail.com} — one Gmail mailbox. Applied to BOTH sides, as the client's {@code canonicalEmail}
 * did, so this keeps the behaviour it replaces rather than quietly narrowing it. Schema-qualified, as
 * that migration's own triggers call it: unqualified, a {@code search_path} without {@code public}
 * makes the statement error rather than answer.
 *
 * <p>{@code btrim} outside it, on both sides, because the function trims whitespace only on the branch
 * that matches: {@code 'a@googlemail.com  '} folds to {@code 'a@gmail.com'} while
 * {@code 'a@gmail.com  '} keeps its blanks, so without this a stored address with a trailing space
 * silently loses its badge — which is the exact silent false negative this endpoint exists to fix.
 *
 * <p>{@code corporation_id = 1} mirrors {@link MatchingAccountsEndpoint}: there is one corporation,
 * and an account outside it is not an account this question is about.
 *
 * <p>Behind {@link MemberSessionGuard#whenCallerIsVerifiedMember}, as its siblings are. Best-effort at
 * the caller: a badge must never fail a booking form, so a refusal there leaves everyone unbadged.
 *
 * @author Claude Code
 */
public final class MembersWithOwnAccountEndpoint extends AsyncFunctionBusCallEndpoint<Object, Object> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.MEMBERS_WITH_OWN_ACCOUNT. */
    public static final String MEMBERS_WITH_OWN_ACCOUNT_ADDRESS =
        "modality/service/person/membersWithOwnAccount";

    /**
     * The caller's own unlinked members whose address is some other account's login.
     *
     * <p>{@code distinct} because one address can be the login of more than one account row in
     * principle, and the answer is per member row rather than per match.
     *
     * <p>Every condition on {@code m} is the caller's own account's: {@code frontend_account_id = $1}
     * is what makes the whole reply rows they may already read. {@code account_person_id is null} is
     * the "unlinked" the badge is about — a member already linked needs no validation.
     *
     * <p>Parameter: {@code $1} the caller's account, from the principal.
     */
    private static final String MEMBERS_WITH_OWN_ACCOUNT_SQL =
        "select distinct m.id from person m" +
        " join frontend_account fa" +
        "   on lower(btrim(public.canonical_email_domain(fa.username)))" +
        "    = lower(btrim(public.canonical_email_domain(m.email)))" +
        " join person o on o.frontend_account_id = fa.id and o.owner = true and o.removed = false" +
        " where m.frontend_account_id = $1 and m.owner = false and m.removed = false" +
        "   and m.account_person_id is null and m.email is not null and btrim(m.email) <> ''" +
        "   and fa.id <> $1 and fa.corporation_id = 1";

    public MembersWithOwnAccountEndpoint() {
        super(MEMBERS_WITH_OWN_ACCOUNT_ADDRESS, argument ->
            MemberSessionGuard.whenCallerIsVerifiedMember((callerPersonId, callerAccountId) ->
                ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                    .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                    .setStatement(MEMBERS_WITH_OWN_ACCOUNT_SQL)
                    .setParameters(callerAccountId)
                    .build())
                ).map(MembersWithOwnAccountEndpoint::toReply)));
    }

    /**
     * The ids as {@code {personIds: [...]}}.
     *
     * <p>An object rather than a bare array, so the reply can gain a field later without changing
     * shape, and so it reads like its two siblings. {@code setScalar} because the id's width is the
     * driver's to decide.
     */
    private static Object toReply(QueryResult result) {
        AstArray personIds = AST.createArray();
        if (result != null)
            for (int row = 0; row < result.getRowCount(); row++)
                // pushScalar, not push: a bare Object matches both push(ReadOnlyAstArray) and
                // push(String), so the overload is ambiguous and casting to pick one would decide
                // the wire type by which cast was written. Same reasoning as setScalar elsewhere.
                personIds.pushScalar(result.getValue(row, 0));
        AstObject reply = AST.createObject();
        reply.setArray("personIds", personIds);
        return reply;
    }
}

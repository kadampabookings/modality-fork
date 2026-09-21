package one.modality.crm.server.person;

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
import one.modality.base.shared.entities.MagicLink;
import one.modality.crm.server.authn.gateway.shared.MagicLinkService;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner row of a brand-new account, created by the server rather than by the browser.
 *
 * <p>Signing up ran in two halves. The server made the {@code frontend_account} from a magic link it had
 * verified, and handed its primary key back; then the BROWSER inserted the person — choosing the account
 * the row landed in, choosing its email, and setting {@code owner = true}. Everything that decides whose
 * account this is was decided by the side that had just been asked to prove nothing more than reading an
 * inbox.
 *
 * <p>What that bought an attacker was not hypothetical, though it was narrower than it looks: the
 * username trigger fires {@code AFTER UPDATE OF email} and not on insert, so planting an owner row in a
 * victim's account did not by itself move their login, and a later attempt to update it is refused by
 * {@code OwnerLoginWritePolicy}. What it did buy is a person in somebody else's account, flagged as the
 * row that speaks for it — which is the shape the plan's account-creation note calls load-bearing,
 * because the {@code accountPerson} rules elsewhere are written on the assumption that an insert lands
 * in the caller's own account.
 *
 * <h3>What proves the caller, with nobody signed in</h3>
 *
 * <p>This runs BEFORE the new user can sign in — there is no principal, so {@link MemberSessionGuard}
 * has nothing to ask. The proof is the credential the account was just created with:
 * {@link MagicLinkService#loadMagicLinkRedeemedByThisSession} finds the LOGIN link carrying both that
 * credential and the caller's own {@code usage_run_id}, which means "this session redeemed it". So the
 * caller is the session that read the email and turned it into an account, and the address is the
 * link's — not a field in the request.
 *
 * <p>It asks for a link already SPENT, which reads oddly until you see what the alternative did. The
 * sibling loader that accepts an unspent one cannot answer for a six-digit code at all: its code branch
 * drops a used row inside the lookup, before the "claimed by this session" leniency further up can
 * apply, and {@code finaliseAccountCreation} spends the credential a moment earlier. Every sign-up
 * through the booking wizard and the public-talk card — the two code routes — would have been refused
 * here, leaving a password account with no person row and no way to sign in to it. Caught by review,
 * not by a check: the check asserted the shape of the generated SQL and never the credential's lifecycle.
 *
 * <p>Everything that matters is then derived rather than accepted: the account is the one whose username
 * IS that address, {@code owner} is true because that is what this operation means, and {@code email} is
 * the link's. The client supplies only ordinary profile fields, through the same {@link PersonFields}
 * allowlist every other person write uses.
 *
 * <p><b>Once per account</b>, and enforced by a read of the account rather than by the client not asking
 * twice: an account that already has an owner is refused. Not "already has a person" — the gateway's
 * {@code GuestPersonLinker} may have just moved guest rows onto this account, and those are members, not
 * owners.
 *
 * @author Claude Code
 */
final class AccountOwnerRules {

    private AccountOwnerRules() {
    }

    /**
     * The credential did not prove anything, OR the account already has an owner — deliberately the
     * same answer.
     *
     * <p>They were two keys until a review pointed out what the second one was: {@code ALREADY_OWNED}
     * is reachable only AFTER the credential has been accepted, so on an endpoint that needs no session
     * and writes nothing when it refuses, the two answers together are a free yes/no on whether a
     * six-digit verification code is live. Ten to the sixth is not a large space to sweep, and a live
     * LOGIN code is redeemable at the sign-in gateway.
     *
     * <p>The cost is a slightly vague sentence on a retry nobody should be making — the client keeps
     * the person id it got and does not ask twice — and that is the right side to be wrong on.
     */
    static final String CREDENTIAL_KEY = "AccountOwnerCredentialError";
    /**
     * The email is the link's, so a caller sending one is a caller trying to choose it.
     *
     * <p>Refused rather than ignored, for the reason {@code collect} refuses an unknown field: a client
     * and a server that disagree about who decides a value should say so, not save the row and drop it.
     */
    static final String EMAIL_NOT_YOURS_KEY = "AccountOwnerEmailError";

    /**
     * Fields that are membership state rather than sign-up profile, and are refused here.
     *
     * <p>{@code removed} is the one that matters and it is not obvious: it is an ordinary editable field
     * everywhere else, and {@code removed = true} on the row this creates would produce an owner that
     * the once-per-account test below does not count — so the same credential could mint owner rows
     * without limit, each carrying whatever name and address the caller chose. The statement's
     * {@code not exists} deliberately ignores {@code removed} as well, so neither alone is load-bearing.
     *
     * <p>{@code occasional} is here for coherence rather than danger: whether somebody is kept between
     * bookings is a thing an account decides about its members, and this row is the account itself.
     */
    private static final java.util.Set<String> NOT_AT_SIGN_UP = java.util.Set.of("removed", "occasional");

    /**
     * The account this credential's address signs in to.
     *
     * <p>Exact on username, never {@code lower()}: the unique constraint is on
     * {@code (corporation_id, username)} and is case-sensitive, so a case-insensitive match could name a
     * DIFFERENT account — the same trap V0062 documents for the username trigger. The gateway writes the
     * username from the link's address verbatim, so exact is also what finds it.
     *
     * <p><b>And the corporation, because half a unique constraint is not unique.</b> Written without it,
     * this matched on username alone and took whichever row came back first. <b>That was latent rather
     * than live</b> — staging holds one corporation and 49,447 accounts all on it, and there is no plan
     * for a second — so the honest reason to fence it is not a lurking second corporation but that a
     * lookup standing in for a unique constraint should ask what the constraint asks. It also lets the
     * index be used from its leading column rather than scanned.
     *
     * <p>Hard-coded to 1 because the account was hard-coded to 1 a moment earlier, by
     * {@code finaliseAccountCreation} itself — the two belong together, and a lookup that read it from
     * anywhere else would be reading it from the caller. If {@code corporation} is ever dropped (it
     * carries one value and nothing depends on there being more), these two go together.
     */
    private static final String ACCOUNT_SQL =
        "select id from frontend_account where corporation_id = 1 and username = $1";

    /** Everything but the profile columns, which are appended from the allowlist. */
    private static final String INSERT_SQL_PREFIX =
        "insert into person (frontend_account_id, owner, email";

    /**
     * Once per account, asked INSIDE the insert rather than before it.
     *
     * <p>It was a separate read first, which is the check-then-write shape {@code PersonDetailsRules}
     * argues against three files away: two overlapping calls — a double-tapped Submit, a bus retry, a
     * StrictMode double-invoke — both read "no owner" and both insert, and two {@code owner = true} rows
     * on one account is the state that already breaks sign-in resolution and the customer merge. A
     * unique index would be the stronger answer and is <b>not available yet</b>: staging holds 4
     * accounts with several owner rows (and 17 with none at all), so creating one would simply fail.
     * Those rows are being cleaned up separately (2026-09-21) — one owner per account, and the
     * person-less accounts deleted. <b>When that lands, add the partial unique index</b>
     * ({@code on person (frontend_account_id) where owner and not removed}) and this condition becomes
     * belt and braces rather than the only thing holding the invariant.
     *
     * <p>{@code removed} is deliberately not tested — a removed owner still blocks. The row is evidence
     * this operation has already run for this account, whatever state it was later put in.
     */
    private static final String ONCE_PER_ACCOUNT =
        " where not exists (select 1 from person where frontend_account_id = $1 and owner = true)";

    /**
     * Creates the owner person for the account this credential just created.
     *
     * @param credential      the magic-link token or verification code the account was finalised with
     * @param namesAndValues  alternating profile field name and value, as the endpoint received them
     * @param runId           the caller's session, READ ON THE CALLER'S THREAD — by the time the first
     *                        async step returns there is no thread-local left to read it from
     * @return the new person's id
     */
    static Future<Object> createOwner(String credential, Object[] namesAndValues, String runId) {
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        String rejection = rejectionFor(namesAndValues, names, values);
        if (rejection != null)
            return refusal(rejection);
        return MagicLinkService
            .loadMagicLinkRedeemedByThisSession(credential, runId, DataSourceModelService.getDefaultDataSourceModel())
            // Its own refusal carries a token in the message, which is fine where it came from — the
            // login screen — and is not something this endpoint should echo back.
            //
            // LOGGED, because this recover has no predicate and cannot have a useful one: a pool
            // exhaustion or a query timeout in there reaches the member as "this link is no longer
            // valid", and their account has ALREADY been created and the link ALREADY consumed, so
            // they cannot get a new one. Discarding the cause made that outage invisible in the log
            // and visible only as sign-up complaints.
            //
            // The CLASS of failure, never the error's own text: the loader's messages end in
            // "(token: <the credential>)", and this line goes to a rolling file that ships to log
            // aggregation and backups. loadSupportViewLinkAndMarkAsUsed refuses to log a token for
            // exactly that reason, and a line that fires on a routine miss must not be the exception.
            .recover(error -> {
                Console.log("createAccountOwner: no redeemed account-creation link for this session ("
                            + (error == null ? "unknown" : error.getClass().getSimpleName()) + ")");
                return refusal(CREDENTIAL_KEY);
            })
            // Typed rather than inferred: the module system needs this module to READ the entity module,
            // and webfx derives that from what the source names — an inferred generic names nothing.
            .compose((MagicLink magicLink) -> {
                String email = magicLink.getEmail();
                if (email == null)
                    return refusal(CREDENTIAL_KEY);
                return accountOf(email)
                    .compose(accountId -> accountId == null ? refusal(CREDENTIAL_KEY)
                        : insertOwner(accountId, email, names, values));
            });
    }

    /**
     * Why these pairs are not acceptable, or null when they are — the whole of what can be judged
     * without asking the database anything.
     *
     * <p>Separate from {@link #createOwner} so a check can reach it: every rule here is about what the
     * CLIENT may say, and those are the rules worth pinning without a running stack.
     *
     * @param names  filled with the accepted field names, in order
     * @param values filled with their values, in the same order
     */
    static String rejectionFor(Object[] namesAndValues, List<String> names, List<Object> values) {
        if (PersonDetailsRules.collect(namesAndValues, names, values) != null)
            return PersonDetailsRules.FIELD_NOT_EDITABLE_KEY;
        if (names.contains(PersonFields.EMAIL))
            return EMAIL_NOT_YOURS_KEY;
        for (String name : names)
            if (NOT_AT_SIGN_UP.contains(name))
                return PersonDetailsRules.FIELD_NOT_EDITABLE_KEY;
        if (PersonDetailsRules.firstTooLong(names, values) != null)
            return PersonDetailsRules.VALUE_TOO_LONG_KEY;
        if (PersonDetailsRules.firstNotAnId(names, values) != null)
            return PersonDetailsRules.NOT_AN_ID_KEY;
        return null;
    }

    private static Future<Object> accountOf(String email) {
        return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(ACCOUNT_SQL)
            .setParameters(email)
            .build())
        ).map(AccountOwnerRules::firstId);
    }

    /**
     * The insert itself, with the three columns that are not the client's written first.
     *
     * <p>Run {@code asServer} rather than {@code asServerActingFor}: every other write in this package
     * carries the caller's principal so {@code person}'s audit triggers can stamp
     * {@code changed_by_person_id}, and here there is genuinely nobody — the person being created is the
     * first one this account has, and nobody was signed in to do it. A null actor on this row is the
     * honest record, not a lost one.
     */
    private static Future<Object> insertOwner(Object accountId, String email,
                                              List<String> names, List<Object> values) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(accountId);
        parameters.add(email);
        for (String name : names)
            parameters.add(values.get(names.indexOf(name)));
        SubmitArgument insert = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(insertStatementFor(names))
            .setParameters(parameters.toArray())
            .setReturnGeneratedKeys(true)
            .build();
        return ServerWrite.asServer(
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { insert })))
            .compose(results -> {
                Object[] keys = results == null || results.getArray().length == 0 ? null
                    : results.getArray()[0].getGeneratedKeys();
                // No row back is the once-per-account test refusing, and the ONLY way it refuses —
                // which is why it is the same answer as a bad credential rather than its own.
                return keys == null || keys.length == 0 ? refusal(CREDENTIAL_KEY)
                    : Future.succeededFuture(keys[0]);
            });
    }

    /**
     * The one statement builder, which the real insert and the check both read — so the condition a
     * check asserts cannot be a condition the running statement has lost.
     *
     * <p>{@code insert ... select ... where not exists} rather than {@code values}, so the once-per-
     * account test and the write are one statement and one decision. Verified against PostgreSQL 17
     * (what staging and production run): the parameter types are inferred from the target columns in a
     * SELECT list exactly as they are in a VALUES list, so nothing here needs a cast it did not already
     * carry.
     */
    static String insertStatementFor(List<String> names) {
        StringBuilder columns = new StringBuilder(INSERT_SQL_PREFIX);
        StringBuilder selected = new StringBuilder(") select $1, true, $2");
        int n = 2;
        for (String name : names) {
            n++;
            columns.append(", ").append(PersonFields.fieldFor(name).column());
            selected.append(", ").append(PersonFields.valueExpression(name, n));
        }
        return columns + selected.toString() + ONCE_PER_ACCOUNT + " returning id";
    }

    private static Object firstId(QueryResult result) {
        return result == null || result.getRowCount() == 0 ? null : result.getValue(0, 0);
    }

    /**
     * A refusal, with a sentence a signing-up person can act on.
     *
     * <p>The sign-up screen renders a server message verbatim, so these are read by members rather than
     * by a log. The bracketed key is the convention every gateway refusal follows and is what a screen
     * would map if it wanted its own wording; the sentence is what is shown until one does.
     *
     * <p>Two of the three say nothing useful on purpose. A caller sending {@code email}, or a field the
     * allowlist does not know, is a CLIENT that disagrees with the server — nothing the person reading
     * the screen did, and nothing they can fix.
     */
    private static <T> Future<T> refusal(String key) {
        return Future.failedFuture("[" + key + "] " + sentenceFor(key));
    }

    static String sentenceFor(String key) {
        // One sentence for both cases it covers, and true of both: it does not say WHICH, because
        // saying which is the oracle described on the key itself.
        if (CREDENTIAL_KEY.equals(key))
            return "This sign-up link can no longer be used. If your account is already set up, please sign in.";
        return "This operation is not available to you";
    }
}

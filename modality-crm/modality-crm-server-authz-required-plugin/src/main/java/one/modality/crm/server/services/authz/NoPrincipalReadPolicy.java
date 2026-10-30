package one.modality.crm.server.services.authz;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.ProtectedEntityReadRegistry;
import dev.webfx.stack.session.state.LogoutUserId;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Arrays;
import java.util.Set;

/**
 * No principal, therefore no people: a client caller who is nobody may not select FROM {@code Person} or
 * {@code FrontendAccount}.
 *
 * <p>The first arm of step 3 of the read-authorization plan, and the first thing on the read side that
 * refuses rather than counts. It is deliberately the smallest rule the mechanism can carry — "an empty set
 * of rows" needs no grant model, no audience decision and no migration — so that the machinery is exercised
 * by something whose blast radius is known before the per-entity catalogue arrives.
 *
 * <h3>What it is actually for</h3>
 *
 * <p><b>An endpoint does not close an oracle while the raw statement still runs.</b> The write session
 * replaced the client's email lookups with a rate-limited {@code emailHasAccount}, and then found in its own
 * review that an anonymous caller could still send {@code select id,firstName,lastName from Person where
 * lower(email)=$1} straight to the generic query endpoint, where there is no rate limit either. Closing the
 * path an app uses says nothing about the protocol.
 *
 * <p><b>The set is NOT empty, and the first census said it was only because it measured half of it.</b>
 * The 2026-10-19 reading covered ANONYMOUS callers, where both shapes were the lookups that session
 * deleted. The signed-out half — the larger one, see below — is live traffic. A back-office session that
 * idles out with the customers list open re-runs it on the operator's next keystroke, and until this
 * flipped the server answered with fifty people's names, emails, phones, passports and dates of birth,
 * twenty milliseconds after ruling that same caller {@code LOGOUT_USER_ID}. So this arm closes a
 * disclosure that is happening, not a regression that might.
 *
 * <h3>Two things that would each have made it wrong</h3>
 *
 * <p><b>ROOT ENTITY, NEVER THE TABLE.</b> Anonymous callers legitimately reach {@code person}: pay-cart and
 * resume-payment read {@code Document … tables=[document, money_transfer, person]} to show the booker's name
 * through a join. An arm keyed on the touched tables would refuse that and break the page finding 6 closed.
 * The per-entity catalogue later does key on tables, for the opposite and equally good reason — see
 * {@link ProtectedEntityReadRegistry} — which is why both facts are carried and neither is a default.
 *
 * <p><b>"No principal" is TWO populations, and the smaller one is the one that comes to mind.</b> The
 * production caller breakdown over that week: 2,278 {@code ModalityUserPrincipal}, 99 restricted, <b>245
 * {@code String}</b>, 120 {@code anonymous}. That {@code String} is the {@code LOGOUT_USER_ID} sentinel a
 * client sends after signing out, so a caller who has logged out is NOT anonymous, and a rule keyed on
 * {@code userId == null} would have missed twice as many callers as it caught. The condition is "no USABLE
 * principal", which is exactly what {@link LogoutUserId#isLogoutUserIdOrNull} answers.
 *
 * <h3>Enforcing, and what was measured before it was</h3>
 *
 * <p>{@link #ENFORCING} is a compile-time constant rather than configuration, so that turning it on is a
 * change somebody reviews — and the observing line existed first, because a refused read changes results
 * silently and gets reported as a data bug rather than as a control working.
 *
 * <p>The fear that kept it off was that refusing would break the page. It does not. The same scenario was
 * run twice against a local server with session lifetimes scaled: observing, the statement executed in
 * 621ms and returned fifty rows; enforcing, it never reached the database. The operator saw the sign-in
 * page in BOTH runs, because the redirect is driven by the identity verdict and not by the query — so
 * refusing costs the user nothing and saves the rows. The refusal removes the disclosure and nothing else.
 *
 * @author Claude Code
 */
final class NoPrincipalReadPolicy implements ProtectedEntityReadRegistry.ReadPolicy {

    /**
     * True since 2026-10-30. See the class javadoc for what was measured first.
     *
     * <p><b>The precondition this used to carry was wrong, and is recorded here because the mistake is
     * the instructive part.</b> It demanded that query 7 of
     * {@code scripts/check-prod-logs-read-aspect.sh} be empty for a week, reasoning that the arm "was
     * built to hold a closed door shut, so anything in it means a client lookup came back". The seven
     * entries that turned up were not a lookup coming back: they were staff whose sessions had ended with
     * a page still open. That set will never empty, because it is not a bug upstream waiting to be fixed
     * — it is what happens when a session ends. Waiting for zero meant waiting for ever with the door open.
     *
     * <p><b>The other precondition stands, and is independent: a production metric filter must match the
     * ENFORCING wording.</b> The observing line says {@code WOULD REFUSE READ} and this one says
     * {@code REFUSED READ}; the {@code kbs3-wg-*} filters were all written for
     * {@code Refused a client write} and match neither. Its purpose has changed, though — nobody is locked
     * out by this, they get the sign-in page — so the filter is for knowing how often a session ends with
     * a page open, not for catching breakage. The write side learned the same lesson the hard way: a new
     * refusal message needs its own filter.
     */
    static final boolean ENFORCING = true;

    /**
     * The roots a caller who is nobody may not select from. Says nothing about the schema to the client.
     */
    static final String REFUSED = "This query needs a signed-in caller";

    /**
     * Root entities only — never tables, see the class javadoc. {@code FrontendAccount} joins the list
     * because the sign-in columns are denied outright but the ROW's existence is itself the oracle: "is
     * there an account for this address" is the question the deleted lookups asked.
     */
    static final Set<String> CLOSED_ROOTS = Set.of("Person", "FrontendAccount");

    @Override
    public String refusalReason(ProtectedEntityReadRegistry.ReadRequest request) {
        if (!claims(request))
            return null;
        // Ends every line of this arm, so one log filter catches all of them and nothing else. The caller is
        // named by KIND, never by value — there is no principal to name, and the statement is not logged:
        // its literals are the email somebody was asked about.
        Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " READ of " + request.entityName()
                    + " by " + (ThreadLocalStateHolder.getUserId() == null ? "anonymous" : "a signed-out caller")
                    + " reaching " + Arrays.toString(request.touchedTables())
                    + (request.bounded() ? "" : " UNBOUNDED"));
        return ENFORCING ? REFUSED : null;
    }

    /**
     * Whether this arm claims the read — the whole rule, and nothing about the switch or the log.
     *
     * <p>Separate so that it can be asserted directly. Deciding it from the log line meant a check that
     * passed or failed on how logging happened to be wired, and deciding it from the refusal meant a check
     * that could say nothing at all while {@link #ENFORCING} is false — which is now, and for a while.
     */
    boolean claims(ProtectedEntityReadRegistry.ReadRequest request) {
        // A server read is not a client read. The document service, the mailer and the migration jobs all
        // run with no principal by design, and refusing them would break everything this rule is not about.
        if (!ThreadLocalStateHolder.isClientOrigin())
            return false;
        // "No USABLE principal": null OR the signed-out sentinel, which is the larger of the two populations.
        if (!LogoutUserId.isLogoutUserIdOrNull(ThreadLocalStateHolder.getUserId()))
            return false;
        String root = request.entityName();
        return root != null && CLOSED_ROOTS.contains(root);
    }
}

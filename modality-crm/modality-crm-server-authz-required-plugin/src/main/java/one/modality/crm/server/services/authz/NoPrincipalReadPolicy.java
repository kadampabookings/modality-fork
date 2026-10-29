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
 * <p>Measured on production over seven days (2026-10-19): every anonymous ROOT {@code Person} read was one
 * of two shapes, and both are the lookups that session deleted. So this arm enforces an already-empty set.
 * <b>That is the point, not a disappointment</b> — it closes a future regression rather than current
 * traffic, and what it stands between is a re-added client lookup and a live oracle.
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
 * <h3>Observed, not enforced</h3>
 *
 * <p>{@link #ENFORCING} is a compile-time constant and stays false until a census of its own line says the
 * set really is empty. A switch that can be flipped without a code review is one that can be flipped without
 * anyone reading the log it depends on. An injected or refused read changes results silently — a screen that
 * showed rows shows none — and it is reported as a data bug rather than as a control working, so the line
 * below has to exist before the refusal does.
 *
 * @author Claude Code
 */
final class NoPrincipalReadPolicy implements ProtectedEntityReadRegistry.ReadPolicy {

    /**
     * False until the census of the line below is clean. See the class javadoc: a compile-time constant
     * rather than configuration, so that turning it on is a change somebody reviews.
     */
    static final boolean ENFORCING = false;

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

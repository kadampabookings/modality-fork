package one.modality.crm.server.services.authz;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.ServerCallerParameters;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

/**
 * Answers {@code CALLER_PERSON}, {@code CALLER_ACCOUNT} and the caller's granted scope from the principal on
 * the thread.
 *
 * <p>The framework compiles those terms to bound parameters and knows nothing about a person or an account,
 * so this is the half that does. It is asked on the endpoint thread, before the first async hop, which is the
 * only place the principal is still there — see {@link ServerCallerParameters}.
 *
 * <p><b>Null is an answer, not a failure.</b> An anonymous booker has no person and no account, and a
 * predicate comparing a column against null matches no rows — which is the safe direction for a term whose
 * whole purpose is to narrow a read to its caller. The plan's open question 2 asks whether an anonymous
 * caller should instead be refused outright; until that is decided, matching nothing is the answer that
 * cannot leak, and the anonymous booking form keeps working because it uses no caller term.
 *
 * <p><b>"Not computed yet" is NOT that answer</b>, and used to be the same null. A back-office caller whose
 * read scope has not been worked out yet may in fact read a great deal, and binding null for them is an
 * empty screen indistinguishable from an honest one. That case answers
 * {@link ServerCallerParameters#UNAVAILABLE}, and the read is refused retriably — see
 * {@link #grantedScopeOf}.
 *
 * <p>The support view is deliberately NOT special-cased here. A support agent acting as someone else reads as
 * that someone else, which is what the principal already says and what the write policies already assume;
 * making this the one place that disagrees would put the two out of step.
 *
 * @author Claude Code
 */
public final class ModalityCallerParameterResolver implements ServerCallerParameters.Resolver {

    public static void register() {
        ServerCallerParameters.registerResolver(new ModalityCallerParameterResolver());
    }

    @Override
    public Object resolveCallerValue(String reservedName) {
        Object userId = ThreadLocalStateHolder.getUserId();
        switch (reservedName) {
            case "caller.person":  return ModalityUserPrincipal.getUserPersonId(userId);
            case "caller.account": return ModalityUserPrincipal.getUserAccountId(userId);
            // The caller's READ SCOPE, flattened by CallerReadScope when their grants were computed. Looked up
            // and never computed here: this runs before the first async hop, so there is no query to await.
            case "caller.grantedEvents":
                return grantedScopeOf(userId, true);
            case "caller.grantedOrganizations":
                return grantedScopeOf(userId, false);
            // A name the framework attaches and this does not know. Null rather than a throw: the binding
            // seam refuses a statement whose server term nothing resolved, by name, which is a better place
            // to fail than inside a resolver that cannot say which statement asked.
            default: return null;
        }
    }

    /**
     * One array of the caller's flattened scope, or — when none has been computed for them — the answer that
     * refuses the read rather than quietly narrowing it to nothing.
     *
     * <p><b>Never an empty array for "not computed yet".</b> An empty array is a real answer: a signed-in
     * operator holding no grants reads nothing. Binding it for an absent scope would turn a cold cache into
     * a screen that is silently empty instead of one that says something, and the two are indistinguishable
     * afterwards. That is the mistake that emptied the staging back-office inbox on 11 November, 249
     * conversations to 0.
     *
     * <p><b>Null was the half-measure, and this is what replaces it.</b> Null reaches the predicate as a
     * comparison against null, which also matches nothing — the same empty screen, merely counted. Since
     * 2026-11-13 the miss also starts a recomputation, so by the time a client retries the value is there;
     * {@link ServerCallerParameters#UNAVAILABLE} is what turns that into a retry the caller can make. The
     * measured cost, over four days and two restarts of production, is roughly one caller after some
     * restarts, on their first page load — see docs/security/caller-read-scope-miss.md.
     *
     * <p><b>Only for a back office with a person, and that limit is deliberate.</b> A scope is computed for
     * no one else: the front office reads by its account, and an anonymous booker has no person at all. For
     * those, an absent scope is not "not yet" but "never", a retry would never fix it, and a refusal would
     * be permanent — so they keep the null, which matches nothing, and a rule that binds a granted term for
     * them is reported as the rule bug it would be.
     */
    private static Object grantedScopeOf(Object userId, boolean events) {
        Object personId = ModalityUserPrincipal.getUserPersonId(userId);
        CallerReadScope.Scope scope = CallerReadScope.peek(personId);
        // A scope is computed for a back office and for nobody else, so that is what makes an absent one
        // mean "not yet" rather than "never" — see below.
        boolean scopeIsExpected = personId != null && ThreadLocalStateHolder.isEffectivelyBackoffice();
        Object answer = grantedScopeAnswer(scope, events, scopeIsExpected);
        if (scope == null && !scopeIsExpected)
            reportGrantedTermOffBackoffice();
        return answer;
    }

    /**
     * What a granted term binds, lifted out of where the caller comes from so that a check can hold it.
     *
     * <p>Three answers from two inputs, and the pair that used to be one is the point: a scope that exists
     * gives its arrays (empty included — that is a real answer), an absent one gives
     * {@link ServerCallerParameters#UNAVAILABLE} where a scope was expected, and null where none ever will
     * be. The thread state is read at the call site because this is the part worth pinning and that is the
     * part that cannot be.
     */
    static Object grantedScopeAnswer(CallerReadScope.Scope scope, boolean events, boolean scopeIsExpected) {
        if (scope != null)
            return events ? scope.eventIds() : scope.organizationIds();
        return scopeIsExpected ? ServerCallerParameters.UNAVAILABLE : null;
    }

    /** How often a caller with no scope of their own was asked for one. */
    private static long grantedTermOffBackofficeCount;

    /**
     * A granted term bound for a caller no scope is ever computed for: reported, and matching nothing.
     *
     * <p>Two kinds end up here and the line does not pretend to tell them apart, because it cannot. A client
     * reading before it has signed in has no person yet — {@code ChatReadScope} found one doing that
     * seventeen seconds before its login — and that one fixes itself. A FRONT-OFFICE caller binding a
     * granted term does not: it means some entity's rule names {@code CALLER_GRANTED_*} under a token other
     * than the back office's, and there the term can only ever match nothing.
     *
     * <p>Reported rather than refused either way. A refusal would make the front office fail permanently on
     * a rule mistake the back office would never see, and would fail the pre-login reads that are about to
     * succeed on their own. Powers of ten, so a systematic one cannot flood the log.
     */
    private static synchronized void reportGrantedTermOffBackoffice() {
        long n = ++grantedTermOffBackofficeCount;
        if (ClientWriteInventory.isPowerOfTen(n))
            Console.log("⚠️ A granted-scope term was bound for a caller with no read scope — not a back"
                + " office, or not signed in yet. It matches NOTHING for them (×" + n + ")");
    }
}

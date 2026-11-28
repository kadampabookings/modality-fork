package one.modality.crm.server.services.authz;

import java.util.Set;

/**
 * The data scope for {@code Document} — a booking — for the two callers that should not read all of them.
 *
 * <p>Not a provider of its own, for the reason {@link PersonReadScope} gives: {@code ClientReadScope} holds
 * exactly ONE provider and {@code registerProvider} REPLACES it, so a second registration would silently
 * unregister {@link ChatReadScope} and turn the live chat control off. {@link ChatReadScope} stays the
 * registered provider and delegates here.
 *
 * <h3>Why this exists at all</h3>
 *
 * <p>Until 2026-11-29 {@code Document} appeared in no read scope. The only rule touching it was
 * {@link NoPrincipalReadPolicy}'s bounded-only arm, which stops a caller with NO session asking for the
 * whole table — the anonymous exposure closed on 2026-11-27. Every SIGNED-IN caller read all 294,713 of
 * them: a member, a centre operator and a platform super administrator got the same number, and
 * {@code document} carries 56 denormalised {@code person_} columns holding names, e-mail, phone and
 * passport. Closing {@code Person} to a member never closed the copy of it living here.
 *
 * <h3>The two rules, and why the back-office one is nearly free</h3>
 *
 * <p><b>A member</b> reads the bookings OF the people on their own account — reusing
 * {@link PersonReadScope#MY_ACCOUNT_PEOPLE} verbatim rather than restating it, so "who is on my account"
 * cannot drift into two different answers. The path {@code person.frontendAccount} is refused in a scope
 * condition at both injection points, hence the subquery.
 *
 * <p><b>A back office</b> reads the bookings of events it is granted, which is
 * {@link ChatReadScope#GRANTED_EVENTS} applied directly: {@code Document.event} is a foreign key on the
 * row itself, so no subquery over people is needed and no {@code exists} either. Measured on staging
 * 2026-11-29: <b>49 ms</b> for organisation 1 — 162,360 of 294,713 documents — against 997 ms for the
 * Person rule over the same grant. The expensive direction is people, not bookings.
 *
 * <p>A platform super administrator is UNRESTRICTED and gets no predicate, as everywhere else.
 *
 * <h3>What this does not narrow</h3>
 *
 * <p>Reads that reach a booking by NAVIGATION rather than by rooting at it. The scope binds at both
 * injection points, so a statement that navigates to {@code document} is in scope too — but a select-list
 * navigation compiles to a LEFT OUTER join and returns NULL columns rather than dropping the row, and
 * {@code fk.id} is pruned to the foreign-key column with no join at all and is never scoped. That is the
 * 2026-11-25 finding recorded on {@link PersonReadScope}, and it applies here unchanged.
 *
 * <p>And it does not touch what a caller with no session may do: that is {@link NoPrincipalReadPolicy},
 * which refuses an unbounded ask outright rather than narrowing it. The two are complementary and neither
 * replaces the other — a signed-in caller is never "nobody", so the bounded-only arm never sees them.
 *
 * @author Claude Code
 */
final class DocumentReadScope {

    /**
     * <b>Enforced on {@code staging} only.</b> {@code prod} must carry {@code Set.of()}, for the same
     * reason {@link PersonReadScope#ENFORCED_ROOTS} does and with the same consequence: the deploys build
     * from branches, so a divergent line here turns an ordinary staging → prod merge into a CONFLICT that
     * somebody has to resolve on purpose. Resolve it by keeping {@code Set.of()} until the flip is a
     * decision of its own.
     *
     * <p>What is owed before that decision: a rehearsal of the four identities against this root the way
     * {@code docs/security/person-read-rehearsal-runbook.md} prescribes for {@code Person}, and an answer
     * for the front-office reads that navigate to a booking rather than rooting at it. Written for a
     * demonstration of per-caller narrowing, which is a reason to have the rule and not a reason to
     * enforce it in production.
     */
    private static final Set<String> ENFORCED_ROOTS = Set.of("Document");

    static boolean isEnforced(String entityName) {
        return ENFORCED_ROOTS.contains(entityName);
    }

    /** True while this scope enforces anything — so the token keeps being issued if the others stop. */
    static boolean enforcesAnything() {
        return !ENFORCED_ROOTS.isEmpty();
    }

    /** The bookings of the people on the caller's own account. */
    private static final String MY_ACCOUNT_BOOKINGS =
        "person in (select id from Person where " + PersonReadScope.MY_ACCOUNT_PEOPLE + ")";

    /**
     * The rule for one entity under one scope, or null when this scope does not narrow it.
     *
     * <p>Package-private and taking strings for the same reason the others are: the check asserts the rules
     * themselves, with no domain model to parse them against.
     */
    static String conditionDql(String entityName, Object scopeToken) {
        if (!"Document".equals(entityName))
            return null;
        if (ChatReadScope.MEMBER.equals(scopeToken))
            return MY_ACCOUNT_BOOKINGS;
        if (ChatReadScope.BACKOFFICE.equals(scopeToken))
            return ChatReadScope.GRANTED_EVENTS;
        // UNRESTRICTED reads everything by design, and gets no predicate at all.
        return null;
    }

    private DocumentReadScope() {}
}

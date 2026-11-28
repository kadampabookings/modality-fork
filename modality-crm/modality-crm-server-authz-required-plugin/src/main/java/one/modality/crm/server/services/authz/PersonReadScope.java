package one.modality.crm.server.services.authz;

import java.util.Set;

/**
 * The data scope for {@code Person} — the rules, and which of them are enforced.
 *
 * <p>Not a provider of its own: {@code ClientReadScope} holds exactly ONE provider and
 * {@code registerProvider} REPLACES it, so a second registration would silently unregister
 * {@link ChatReadScope} and turn the live chat control off. {@link ChatReadScope} stays the registered
 * provider and delegates here for the entities it does not own.
 *
 * <h3>The rule, settled 2026-10-19</h3>
 *
 * <p>A person has no single event and no reliable organisation, so the rule is a relationship rather than a
 * column test, and it differs by who is asking:
 *
 * <ul>
 *   <li><b>A member</b> reads the people on their own account — themselves and whoever books with them.</li>
 *   <li><b>A back office</b> reads people who booked an event in scope, or who belong to a centre in scope.
 *       <b>Written 2026-11-29</b>, and not in the shape this line used to describe — see
 *       {@link #GRANTED_PEOPLE}, which expands organisations into events because an organisation-wide
 *       grant binds {@code CALLER_GRANTED_EVENTS} empty.</li>
 * </ul>
 *
 * <p>Both arms of the member rule avoid a PATH, which a scope condition is refused for at both injection
 * points: {@code accountPerson.frontendAccount} becomes a subquery, the same transformation
 * {@code ChatReadScope.GRANTED_EVENTS} makes for {@code event.organization}. The catalogue states the same
 * relationship as {@code accountCanAccessPersonOrders}, whose body is
 * {@code p?.frontendAccount=a or p?.accountPerson?.frontendAccount=a}.
 *
 * <h3>Both arms are written; only staging enforces them</h3>
 *
 * <p>The member arm was proved on 11 November, the back-office arm written on 29 November for a demo of
 * what the server narrows per caller. Of the two things the previous version of this note said had to
 * happen first, one is answered and one is not:
 *
 * <ul>
 *   <li><b>Timed, and it is not the feared shape.</b> 997 ms for organisation 1 on an unfiltered
 *       {@code select count(1) from Person}: Postgres hashes the subplan over the granted events'
 *       documents rather than probing once per candidate row. Not a per-row disaster, but a second on the
 *       worst question the back office can ask, against 49 ms for the Document equivalent. Detail on
 *       {@link #GRANTED_PEOPLE}.</li>
 *   <li><b>The 11,245 are STILL NOT ANSWERED FOR.</b> Re-measured on staging 2026-11-29: 11,245 persons
 *       match neither arm — no centre recorded and no booking at all — against 11,235 on 2026-10-19, so
 *       the population is stable rather than shrinking. A further 9,214 have no centre but do have a
 *       booking, and those the booking arm rescues. Under this rule no back-office user sees any of the
 *       11,245. The plan argues that is right (no booking and no centre is no relationship) but it is an
 *       operational cost — a caller who signed up online will not be found by a search, and staff will
 *       make a duplicate — and it is why {@link #ENFORCED_ROOTS} must stay empty on {@code prod}. Writing
 *       the arm did not decide this; it only made the consequence visible.</li>
 * </ul>
 *
 * <h3>And the member arm has preconditions of its own</h3>
 *
 * <p>Found by review, 2026-11-25, and listed here because the two above are about the OTHER arm — a prod
 * gate discharged by a back-office argument alone would ship these.
 *
 * <p><b>It reaches {@code Person} at BOTH injection points</b>, so every statement that NAVIGATES to a
 * person is in scope, not only those rooted at {@code Person}. The two behave differently, and the
 * difference is the dangerous part: a select-list navigation compiles to a LEFT OUTER join
 * ({@code DotSqlCompiler}: {@code dot.isOuterJoin() || o.clause == SqlClause.SELECT}), so a person outside
 * the scope does not drop the row — it returns NULL columns. Meanwhile {@code fk.id} is pruned to the
 * foreign-key column with no join at all, so it is never scoped. A caller can therefore see a non-null
 * {@code accountPerson.id} beside a null {@code accountPerson.frontendAccount}, which the front office's
 * members page reads to decide whether a link is an AUTHORISATION. Blank is better than wrong; this
 * produces wrong, on the screen that tells a member who can see their bookings.
 *
 * <p><b>Three front-office reads return nothing at all under this rule</b>, by construction rather than by
 * accident: {@code CLAIMABLE_MEMBERS_QUERY} and {@code CLAIM_OWNERS_QUERY}
 * ({@code frontoffice/src/features/members/utils/claimable-members.ts}) and the account-owner enrichment in
 * {@code use-managers-data.ts}. All three deliberately look for people on OTHER accounts, which is exactly
 * what the rule excludes. The claim feature stops working.
 *
 * <p>That is not the rule being too tight. {@code CLAIMABLE_MEMBERS_QUERY} takes an arbitrary email and
 * returns a name, an email and an account username, through the generic query endpoint, to any signed-in
 * member — an enumeration oracle, which this rule CLOSES. The work is to move those three reads behind
 * server endpoints (the claim flow already has {@code ApproveInvitationEndpoint} and
 * {@code MemberClaimRules} next door), not to widen the rule back.
 *
 * <p><b>Rehearse with four identities, not one.</b> Naming them here for the same reason the chat roots'
 * gate names its own: 11 November passed with one identity and then emptied every inbox. A member with
 * linked people, a member with none, a guest or cart caller, and an anonymous one — a caller with no
 * account resolves {@code CALLER_ACCOUNT} to null, both arms go false, and they read ZERO persons.
 *
 * <p><b>{@link #ENFORCED_ROOTS} must not reach the prod branch</b> until both are settled. It is on
 * {@code staging} so the front-office arm can be exercised against production-shaped data; the chat
 * rehearsal runbook is the procedure, and the same gate applies again here.
 *
 * <p>Design and the measurements behind it: {@code docs/security/read-authorization-plan.md}, "Person: the
 * rule that needed a new term".
 *
 * @author Claude Code
 */
final class PersonReadScope {

    /**
     * Enforced rather than merely reported. STAGING ONLY — see the class comment.
     *
     * <p>Enforcement is per ENTITY, not per token, so this flips the member arm on and leaves the back
     * office unchanged only because {@link #conditionDql} returns null for it. If a back-office rule is
     * added here, adding it ENFORCES it in the same edit; that is the trap this note exists for.
     */
    private static final Set<String> ENFORCED_ROOTS = Set.of("Person");

    static boolean isEnforced(String entityName) {
        return ENFORCED_ROOTS.contains(entityName);
    }

    /** True while this scope enforces anything — so the token keeps being issued if chat ever stops. */
    static boolean enforcesAnything() {
        return !ENFORCED_ROOTS.isEmpty();
    }

    /**
     * The people on the caller's own account: themselves, and whoever books with them.
     *
     * <p>The second arm is a subquery because {@code accountPerson.frontendAccount} is a path, and a path in
     * a scope condition is refused at both injection points.
     *
     * <p>Package-private because {@code DocumentReadScope} reuses it verbatim: a member's bookings are the
     * bookings OF these people, and the two rules must not be allowed to drift apart into two different
     * answers to "who is on my account".
     */
    static final String MY_ACCOUNT_PEOPLE =
        "frontendAccount=CALLER_ACCOUNT"
        + " or accountPerson in (select id from Person where frontendAccount=CALLER_ACCOUNT)";

    /**
     * The people a back office may read: whoever belongs to a centre it is granted, and whoever has booked
     * an event it is granted.
     *
     * <p><b>The booking arm is not optional, and the shape of it is not either.</b> Measured on staging
     * 2026-11-29, for the five organisations that actually carry a grant: organisation 1 has <b>20</b>
     * people by centre and <b>25,359</b> by booking. An organisation-only rule would therefore show that
     * centre's operator twenty people out of 25,367 — a screen that looks broken rather than narrowed.
     *
     * <p>And the booking arm has to expand organisations INTO events, which is why it reuses
     * {@link ChatReadScope#GRANTED_EVENTS} rather than testing {@code event in CALLER_GRANTED_EVENTS}
     * alone. Most production grants are organisation-wide, and such a grant binds
     * {@code CALLER_GRANTED_EVENTS} EMPTY — confirmed by the 2026-11-25 production run, where the
     * organisation arm carried the whole test. Written the short way, this arm would match nothing at all
     * for exactly the callers it exists for.
     *
     * <p>Expressed as {@code id in (select person from Document where …)} rather than as a correlated
     * {@code exists(… and person=this)}: it is the plan Postgres chooses anyway — a hashed SubPlan over the
     * granted events' documents, not a probe per candidate row — and saying so in the rule makes the cost
     * predictable instead of dependent on the planner's mood.
     *
     * <p><b>Cost, measured rather than feared.</b> 997 ms for organisation 1 on an unfiltered
     * {@code select count(1) from Person} (59,858 candidates, 162,360 documents across 77 events), against
     * 49 ms for the equivalent Document rule, which needs no subquery because {@code Document.event} is a
     * direct foreign key. The javadoc above feared one index probe per candidate row; that is not what
     * happens, but a second is still a second, and this is the worst case the back office can ask for.
     */
    private static final String GRANTED_PEOPLE =
        "organization in CALLER_GRANTED_ORGANIZATIONS"
        + " or id in (select person from Document where " + ChatReadScope.GRANTED_EVENTS + ")";

    /**
     * The rule for one entity under one scope, or null when this scope does not narrow it.
     *
     * <p>Package-private and taking strings for the same reason {@code ChatReadScope.conditionDql} is: the
     * check asserts the rules themselves, with no domain model to parse them against.
     */
    static String conditionDql(String entityName, Object scopeToken) {
        if (!"Person".equals(entityName))
            return null;
        if (ChatReadScope.MEMBER.equals(scopeToken))
            return MY_ACCOUNT_PEOPLE;
        if (ChatReadScope.BACKOFFICE.equals(scopeToken))
            return GRANTED_PEOPLE;
        // UNRESTRICTED reads everything by design, and gets no predicate at all.
        return null;
    }

    private PersonReadScope() {}
}

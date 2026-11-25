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
 *   <li><b>A back office</b> reads people who booked an event in scope, or who belong to a centre in scope:
 *       {@code exists(select Document where event in CALLER_GRANTED_EVENTS and person=this) or
 *       organization in CALLER_GRANTED_ORGANIZATIONS}. <b>Not written here yet</b> — see the phases below.</li>
 * </ul>
 *
 * <p>Both arms of the member rule avoid a PATH, which a scope condition is refused for at both injection
 * points: {@code accountPerson.frontendAccount} becomes a subquery, the same transformation
 * {@code ChatReadScope.GRANTED_EVENTS} makes for {@code event.organization}. The catalogue states the same
 * relationship as {@code accountCanAccessPersonOrders}, whose body is
 * {@code p?.frontendAccount=a or p?.accountPerson?.frontendAccount=a}.
 *
 * <h3>Phase 1 is the member arm, and only on staging</h3>
 *
 * <p>The back-office arm returns null here, which means a back-office caller reads {@code Person} exactly as
 * it did before — unnarrowed. That is deliberate, and it is the same staging the chat roots had: the member
 * arm was proved on 11 November and the back-office arm only on 25 November, two weeks apart. Two things
 * have to happen before the back-office arm is written:
 *
 * <ul>
 *   <li><b>Time it.</b> The back-office arm is a correlated {@code exists}, roughly one index probe per
 *       candidate row — about 60,000 on an unfiltered person list, which is exactly what
 *       {@code select count(1) from Person} is. The member arm below is an indexed column test and has no
 *       such question, which is why it can go first.</li>
 *   <li><b>Answer for the 11,235.</b> Of 59,714 persons on staging (2026-10-19), 11,235 match neither
 *       back-office arm: front-office self-signups who never booked and never had a centre recorded. Under
 *       that arm no back-office user would see any of them. The plan argues that is right — no booking and
 *       no centre is no relationship — but it is an operational cost (a caller who signed up online will
 *       not be found by a search, and staff will make a duplicate) and it should be a decision, not a
 *       side effect.</li>
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
     */
    private static final String MY_ACCOUNT_PEOPLE =
        "frontendAccount=CALLER_ACCOUNT"
        + " or accountPerson in (select id from Person where frontendAccount=CALLER_ACCOUNT)";

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
        // UNRESTRICTED reads everything by design. BACKOFFICE is Phase 2: returning null leaves it reading
        // Person exactly as it does today, and the UNSCOPED census line says so out loud rather than
        // leaving the gap to be inferred from an absence.
        return null;
    }

    private PersonReadScope() {}
}

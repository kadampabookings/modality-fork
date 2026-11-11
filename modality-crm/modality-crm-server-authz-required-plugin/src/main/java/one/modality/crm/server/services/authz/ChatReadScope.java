package one.modality.crm.server.services.authz;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.ClientScopeToken;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.dql.sqlcompiler.ClientReadScope;
import dev.webfx.stack.orm.expression.Expression;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may read a stored conversation — the first data scope this system has.
 *
 * <p>Every chat row was readable by any caller who could reach the generic query endpoint. The front office
 * asks for a thread with {@code where conversation=$1}, and that id is the client's to choose; the back-office
 * inbox, with no event selected, carries only {@code type=support} and so reads every centre's conversations
 * beside the viewer's name, email and city. Neither is scoped by anything the server decides. The read
 * policies cannot help: a policy refuses a root outright, and refusing chat closes the feature.
 *
 * <h3>Why the rules are subqueries</h3>
 *
 * <p>Only {@code Conversation} carries the member, in {@code viewer_person_id} — populated on all 248
 * production rows. A chat message does not: its own {@code person_id} is the AUTHOR, null on 37.6% of rows
 * (every system message, most notes), so a rule testing it would blank every status event for everybody. The
 * member is a hop away for {@code ChatMessage} and two for {@code ChatMessageReaction}, and a condition that
 * navigates is refused at both injection points. Moving the hop inside a subquery leaves the scoped row
 * contributing one local column — see {@code ChatReadScopeCheck} in webfx-stack-fork, which pins the sql.
 *
 * <h3>The centre boundary is the EVENT, not the organisation</h3>
 *
 * <p>{@code conversation.organization_id} is NULL on every production row — the front office sets it only
 * when its offline-email config resolves one, which it never has. A predicate on it would match nothing
 * today and some rows later, silently. {@code event_id} is populated on every row, so the boundary is
 * expressed on the event — reached by BOTH kinds of grant, see {@link #GRANTED_EVENTS}, because most
 * operators hold an organisation-wide grant and no event one.
 *
 * <h3>Observing, and what it costs</h3>
 *
 * <p>A scope has no observing mode of the usual kind: a condition either narrows the rows or does not, and a
 * narrowed read looks to everyone like a data bug rather than a control working. So {@link #OBSERVING} does
 * the only honest version — the token is issued, this provider is consulted, and it reports the rule it
 * WOULD have applied and then returns nothing. The compiled sql is identical to the unscoped one, so no
 * result changes; what is learned is which chat shapes real callers send and under which scope, which is the
 * blast radius of flipping {@link #ENFORCING}.
 *
 * <p><b>It is a report per SHAPE, not per query</b>, because compilation is cached by (statement, token) and
 * this is consulted on a cache miss. Frequency is the read inventory's job; this says what exists.
 *
 * <p><b>What observing costs, measured rather than feared.</b> Issuing a token puts it on the QueryArgument,
 * which is part of its equality, so two scopes no longer share one push subscription. That sounds expensive
 * and is not: callers of the SAME kind still share, so the only loss is between a member and an operator
 * running an identical statement with identical parameters — in this system, the reactions query in
 * {@code shared/}. Per-CALLER fragmentation, the expensive kind, does not arise: no condition is injected
 * while observing, so the compiled statement names no caller term and {@code ServerCallerParameters}
 * attaches nothing.
 *
 * @author Claude Code
 */
final class ChatReadScope implements ClientReadScope.Provider, ClientScopeToken.Resolver {

    /**
     * The roots whose conditions are APPLIED. Everything else is reported and returns nothing.
     *
     * <p>A set rather than a boolean because the five roots are not one decision. They carry different rules
     * over different data, and the plan has said from the start to flip per root — a single switch would make
     * the first flip the riskiest one available, which is how a control gets reverted wholesale over one
     * root's bug. Adding a name here is the reviewed change; the surrounding machinery does not move.
     *
     * <p>Empty, so nothing is narrowed yet. {@code Conversation} is the one to add first: it is the only
     * rule that is a local column test, so what it returns is the easiest to reason about, and it is where
     * the exposure actually is — any signed-in member can read any conversation by changing one id.
     */
    private static final Set<String> ENFORCED_ROOTS = Set.of("Conversation");

    /** Whether this root's condition is applied rather than merely reported. */
    static boolean isEnforced(String entityName) {
        return ENFORCED_ROOTS.contains(entityName);
    }

    /** True while any root is still only reported — kept for the checks and the boot line. */
    static final boolean ENFORCING = false;

    /**
     * True since 2026-11-09: issue the token, report the rule each chat read WOULD get, change nothing.
     *
     * <p>Separate from {@link #ENFORCING} so that the reporting outlives the flip — once enforcing, the same
     * line says which rule is being applied, which is what a census counts. With both false nothing is
     * issued and nothing is consulted, which is the state to return to if observing itself proves costly.
     */
    static final boolean OBSERVING = true;

    /**
     * The two caller kinds. Named for the CALLER, not for chat.
     *
     * <p>They were {@code chat.member} and {@code chat.backoffice} for one day. A scope token selects a set
     * of per-entity rules and is already issued for every client read, chat or not — so the moment anything
     * outside chat is scoped under the same caller kind, a feature-named token reads as a lie. Renamed while
     * the only cost is a census whose counts restart, which a deploy does anyway.
     */
    static final String MEMBER = "member";
    /** A verified back office, reading the centres it is granted. */
    static final String BACKOFFICE = "backoffice";

    /** The member's own conversations, the hop moved inside a subquery so nothing navigates. */
    private static final String MY_CONVERSATIONS =
        "(select id from Conversation where viewerPerson=CALLER_PERSON)";
    /**
     * The events this caller may see, by EITHER kind of grant.
     *
     * <p><b>Granted events alone would have emptied most inboxes.</b> A grant row names an event, an event
     * type, or neither — and {@code CallerReadScope.assemble} is explicit that "an event row does NOT widen
     * to its organisation": an organisation-wide grant lands in {@code organizationIds} and never in
     * {@code eventIds}. On staging that is 33 of 43 grant rows, and <b>18 of 25 back-office users hold no
     * event or event-type grant at all</b>, so {@code CALLER_GRANTED_EVENTS} binds an empty array for them
     * and every chat read would have returned nothing. Measured before flipping, not after.
     *
     * <p>The organisation arm is a SUBQUERY rather than {@code event.organization}, which is a path, and a
     * path in a scope condition is refused at both injection points. The two-armed shape is safe at both:
     * each parenthesises what it injects, which is what stops {@code <caller's filter> and A or B} being read
     * as {@code (<filter> and A) or B} — the defect {@code ClientReadScopeCheck} pins.
     */
    private static final String GRANTED_EVENTS =
        "event in CALLER_GRANTED_EVENTS"
        + " or event in (select id from Event where organization in CALLER_GRANTED_ORGANIZATIONS)";

    /** The conversations of the events this caller is granted, by either kind of grant. */
    private static final String GRANTED_CONVERSATIONS =
        "(select id from Conversation where " + GRANTED_EVENTS + ")";

    private static final ChatReadScope INSTANCE = new ChatReadScope();

    /** Parsed once per (entity, scope): the condition is evaluated for every row of every read. */
    private final Map<String, Expression<?>> parsed = new ConcurrentHashMap<>();

    static void register() {
        ClientReadScope.registerProvider(INSTANCE);
        ClientScopeToken.registerResolver(INSTANCE);
    }

    /**
     * Which scope this caller reads under, or null to compile unscoped.
     *
     * <p><b>{@code isEffectivelyBackoffice}, never {@code isBackoffice}.</b> The second is the client's own
     * claim — "fine for choosing a login's rules or labelling a monitor; never for granting anything", as it
     * says of itself — and granting the wider scope on it would let any caller read every centre by asking.
     * The first is the audience of a token this server verified.
     *
     * <p>Server-origin reads get no scope. They do not pass the endpoint that calls this, so this is belt
     * and braces rather than the boundary — the boundary is where it is asked.
     */
    @Override
    public Object tokenForCaller() {
        if (!(OBSERVING || !ENFORCED_ROOTS.isEmpty()) || !ThreadLocalStateHolder.isClientOrigin())
            return null;
        return ThreadLocalStateHolder.isEffectivelyBackoffice() ? BACKOFFICE : MEMBER;
    }

    @Override
    public Expression<?> readScopeCondition(Object domainClass, Object scopeToken) {
        String name = String.valueOf(domainClass);
        String dql = conditionDql(name, scopeToken);
        if (dql == null)
            return null; // an entity this does not scope compiles exactly as it did before
        Console.log(logLine(name, scopeToken));
        // PARSED EVEN WHILE OBSERVING, and this is the point of observing at all. Returning null before the
        // parse — which is what this did for its first two days — means the rule TEXT is never exercised
        // until the day a root is enforced, so the first production parse of a rule would happen under live
        // traffic, and a rule naming a field the real domain model spells differently would surface as
        // failing reads at the flip. Parsing here makes every observing deploy a check of the rule, which is
        // what the census could not be: the census proves which callers read what, not that the rule compiles.
        Expression<?> condition;
        try {
            condition = parsed.computeIfAbsent(name + '\u0000' + scopeToken, key ->
                DataSourceModelService.getDefaultDataSourceModel().getDomainModel().parseExpression(dql, domainClass));
        } catch (RuntimeException e) {
            // Enforced and unparseable: fail the read. Returning null here would silently UNSCOPE it, which
            // is the one direction a broken scope must never take.
            if (isEnforced(name))
                throw e;
            // Observing and unparseable: harmless, and said loudly. One line per statement shape, since an
            // unscoped compile is cached and this is not consulted again for that shape.
            Console.log("🛡 CHAT SCOPE RULE WILL NOT COMPILE for " + name + " under " + scopeToken
                        + " — it would fail reads if enforced: " + e);
            return null;
        }
        return isEnforced(name) ? condition : null;
    }

    /**
     * The reported line, built here so the census can be asserted against it.
     *
     * <p>One wording for both modes so a single filter counts either, with the mode as a prefix saying
     * which. Names the ENTITY and the SCOPE and nothing else — a chat statement's literals are a
     * conversation id and, through it, who was talking to support.
     *
     * <p>Package-private because {@code scripts/check-prod-logs-read-aspect.sh} parses this shape, and a
     * message that drifts from its filter is a census that quietly reports zero. That has happened here
     * before, more than once; the check asserts this against the same regex the script uses.
     */
    static String logLine(String entityName, Object scopeToken) {
        return "\uD83D\uDEE1 " + (isEnforced(entityName) ? "SCOPING" : "WOULD SCOPE") + " READ of " + entityName
             + " under " + scopeToken;
    }

    /**
     * The rule for one entity under one scope, or null when this entity is unscoped.
     *
     * <p>Package-private and taking strings so the check can assert the rules themselves, without a domain
     * model to parse them against and without {@link #ENFORCING} having to be true to see them.
     */
    static String conditionDql(String entityName, Object scopeToken) {
        boolean backoffice = BACKOFFICE.equals(scopeToken);
        if (!backoffice && !MEMBER.equals(scopeToken))
            return null; // not a scope this provider knows
        String conversations = backoffice ? GRANTED_CONVERSATIONS : MY_CONVERSATIONS;
        switch (entityName) {
            case "Conversation":
                return backoffice ? GRANTED_EVENTS : "viewerPerson=CALLER_PERSON";
            case "ChatMessage":
                return "conversation in " + conversations;
            case "ChatMessageReaction":
                // Two hops, so two nested subqueries. Reactions are deliberately readable across people —
                // seeing who reacted is the point — so the boundary is the conversation, not the reactor.
                return "message in (select id from ChatMessage where conversation in " + conversations + ")";
            case "ConversationParticipant":
                // Both apps read only their OWN row (where conversation=$1 and person=$2), so this is the
                // same rule either way. It would need revisiting the moment anything wants a membership list.
                return "person=CALLER_PERSON";
            case "ChatPresence":
                // The front office reads agents' presence to show who is online, and nothing else needs a
                // viewer's last-seen: a per-person last-seen readable by any member is a tracker.
                return backoffice ? GRANTED_EVENTS : "agent";
            default:
                return null;
        }
    }

    private ChatReadScope() {}
}

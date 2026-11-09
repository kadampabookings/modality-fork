package one.modality.crm.server.services.authz;

import dev.webfx.stack.db.query.ClientScopeToken;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.dql.sqlcompiler.ClientReadScope;
import dev.webfx.stack.orm.expression.Expression;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Map;
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
 * today and some rows later, silently. {@code event_id} is populated on every row, and
 * {@code CALLER_GRANTED_EVENTS} is already the caller's flattened scope, so the boundary is expressed there.
 * {@code event.organization} would be a path, which is the refusal above.
 *
 * <h3>Observing means registering nothing</h3>
 *
 * <p>A scope has no observing mode: a condition either narrows the rows or does not, and a narrowed read
 * looks to everyone like a data bug. So {@link #ENFORCING} gates BOTH halves — with it false this provider
 * returns no condition and no token, which leaves every query compiling exactly as it does now and every
 * push subscription shared exactly as it is now. A token naming a scope with no rules would fragment those
 * subscriptions and buy nothing, which is why the two are one flag and not two.
 *
 * @author Claude Code
 */
final class ChatReadScope implements ClientReadScope.Provider, ClientScopeToken.Resolver {

    /**
     * False until the comparison pass of the read plan has run. Flipping it is a change somebody reviews,
     * for the reason {@code NoPrincipalReadPolicy} gives: a refused read changes results silently and gets
     * reported as a data bug rather than as a control working.
     */
    static final boolean ENFORCING = false;

    /** The member reading their own conversations. */
    static final String MEMBER = "chat.member";
    /** A verified back office reading the centres it is granted. */
    static final String BACKOFFICE = "chat.backoffice";

    /** The member's own conversations, the hop moved inside a subquery so nothing navigates. */
    private static final String MY_CONVERSATIONS =
        "(select id from Conversation where viewerPerson=CALLER_PERSON)";
    /** The conversations of the events this caller is granted. */
    private static final String GRANTED_CONVERSATIONS =
        "(select id from Conversation where event in CALLER_GRANTED_EVENTS)";

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
        if (!ENFORCING || !ThreadLocalStateHolder.isClientOrigin())
            return null;
        return ThreadLocalStateHolder.isEffectivelyBackoffice() ? BACKOFFICE : MEMBER;
    }

    @Override
    public Expression<?> readScopeCondition(Object domainClass, Object scopeToken) {
        if (!ENFORCING)
            return null;
        String name = String.valueOf(domainClass);
        String dql = conditionDql(name, scopeToken);
        if (dql == null)
            return null; // an entity this does not scope compiles exactly as it did before
        return parsed.computeIfAbsent(name + '\u0000' + scopeToken, key ->
            DataSourceModelService.getDefaultDataSourceModel().getDomainModel().parseExpression(dql, domainClass));
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
                return backoffice ? "event in CALLER_GRANTED_EVENTS" : "viewerPerson=CALLER_PERSON";
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
                return backoffice ? "event in CALLER_GRANTED_EVENTS" : "agent";
            default:
                return null;
        }
    }

    private ChatReadScope() {}
}

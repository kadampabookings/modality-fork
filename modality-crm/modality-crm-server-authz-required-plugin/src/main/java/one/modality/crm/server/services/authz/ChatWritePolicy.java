package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Map;
import java.util.Set;

/**
 * Support chat is written by the member it belongs to, or by the back office, and each writes as itself.
 *
 * <p><b>The hole (found 2026-11-09, from the production write census).</b> The chat tables (V0019, V0056) are
 * written by both React apps through the generic submit, and no rule asked who was writing: none of them is in
 * the per-entity rule, the deny list or another row rule. So any signed-in member could post a message under
 * another person's name, into anybody's conversation (ids are sequential), close or reassign a conversation, add
 * themselves as a participant of someone else's, react as someone else, or show themselves as an online agent.
 * A support conversation is where a member explains why they need help, so it can hold health and accessibility
 * details — special-category data.
 *
 * <p><b>The line, measured on staging before it was drawn (2026-11-09).</b> Of 2,262 messages, every one with an
 * author was written by the conversation's member (752), its assignee (587), another participant (1), or one of 4
 * people who were all back-office agents (71, from conversations reassigned since); 851 have no author (system
 * lines and team notes). Every participant is either the member (role viewer) or an agent; no conversation has
 * no member. So:
 * <ul>
 *   <li><b>A member</b> (any caller without a verified back-office token) writes only in their own conversation —
 *       the one whose {@code viewerPerson} is them — and only the fields the front office writes: a new
 *       conversation naming themselves, its device stamp and watched session, their participant row and its read
 *       cursor, messages (as themselves, or unattributed for the widget's notes), their own reactions, and their
 *       own presence, never as an agent. Nothing else: no assignee, no status change, no edit or delete.</li>
 *   <li><b>The back office</b> (a verified back-office token) may do what the support inbox does, but every row
 *       it attributes to a person is attributed to the caller: a message, a participant, a reaction, a presence.
 *       Which centre's conversations a member of staff may work is NOT decided here — see below.</li>
 *   <li><b>No person to act as</b> — anonymous, signed out, or the support view — writes no chat at all.</li>
 * </ul>
 *
 * <p>The verified audience, never the client's claim: see {@link BackofficeOnlyWritePolicy} for why only TRUE from
 * {@link StateAccessor#getVerifiedBackoffice} counts as the back office.
 *
 * <p><b>Ownership is read from the database, never from the payload.</b> An update or delete is judged on the row
 * it targets, looked up by its id; a target the parser could not read is refused, since that is exactly the
 * statement that would reach someone else's row. An insert into a conversation the same batch creates is resolved
 * through the batch and must point at a Conversation insert that itself names the caller.
 *
 * <p><b>What this does not close.</b> A member of staff can still work any centre's conversations: the inbox has no
 * organisation scope on the server yet. Reading chat is the read plan's, and is not touched here. A member can still
 * name any booking in their own conversation's {@code document} — staff then see that booking's link in the thread.
 *
 * <p><b>Observed first.</b> Row rules are enforced the moment they deploy, so one written wrong refuses real work
 * at once; this ships logging "WOULD REFUSE a chat write" and is switched on after a census. Its REFUSED line will
 * need a CloudWatch filter of its own.
 *
 * <p>Cost: a member's update, reaction or delete costs one or two reads by primary key. The busiest is the presence
 * heartbeat, every 30 s per open livestream page.
 *
 * @author Claude Code
 */
final class ChatWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** False while it observes: decisions are logged ("WOULD REFUSE a chat write") but nothing is refused. */
    static final boolean ENFORCING = false;

    static final Set<String> CHAT_ENTITIES = Set.of(
        "Conversation", "ConversationParticipant", "ChatMessage", "ChatMessageReaction", "ChatPresence");

    /** Told to the caller. */
    static final String REFUSED = "A support chat is written only by its own member, or by the back office as itself";

    /** The fields a member may set when opening a conversation (use-support-conversation.ts). */
    static final Set<String> MEMBER_CONVERSATION_INSERT_FIELDS = Set.of(
        "type", "event", "scheduledItem", "issue", "document", "viewerPerson", "status", "viewerDevice", "viewerLang");
    /** And afterwards: the device stamp, and the session being watched when the first message is sent. */
    static final Set<String> MEMBER_CONVERSATION_UPDATE_FIELDS = Set.of("viewerDevice", "scheduledItem");
    /** The two states a member's new conversation starts in: waiting for an agent, or a message left offline. */
    static final Set<String> MEMBER_CONVERSATION_STATUSES = Set.of("queued", "message");
    static final Set<String> MEMBER_MESSAGE_FIELDS = Set.of("conversation", "person", "kind", "content", "replyTo");
    /** Text (the default), images, and the widget's unattributed notes. Never a system line. */
    static final Set<String> MEMBER_MESSAGE_KINDS = Set.of("text", "image", "note");

    /** The person field of each chat entity that says who a row is by. */
    static final Map<String, String> PERSON_FIELD = Map.of(
        "ChatMessage", "person",
        "ConversationParticipant", "person",
        "ChatMessageReaction", "person",
        "ChatPresence", "person",
        "Conversation", "viewerPerson");

    /** The reads ownership needs. An interface so the check can answer them without a database. */
    interface ChatLookup {
        /** The conversation's member ({@code viewerPerson}) id, or a future of null when none or no such row. */
        Future<Object> conversationMember(Object conversationId);

        /** The {@code person} of a participant, reaction or presence row, or a future of null. */
        Future<Object> rowPerson(String entityName, Object id);

        /** The conversation a message belongs to, or a future of null. */
        Future<Object> messageConversation(Object messageId);
    }

    static final ChatLookup DATABASE_LOOKUP = new ChatLookup() {
        @Override
        public Future<Object> conversationMember(Object conversationId) {
            return foreignKey("Conversation", "viewerPerson", conversationId);
        }

        @Override
        public Future<Object> rowPerson(String entityName, Object id) {
            return foreignKey(entityName, "person", id);
        }

        @Override
        public Future<Object> messageConversation(Object messageId) {
            return foreignKey("ChatMessage", "conversation", messageId);
        }

        private Future<Object> foreignKey(String entityName, String field, Object id) {
            return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel())
                .<Entity>executeQuery("select " + field + " from " + entityName + " where id=$1", id)
                .map(rows -> rows.isEmpty() ? null : Entities.getPrimaryKey(rows.get(0).getForeignEntityId(field)));
        }
    };

    @Override
    public Future<String> refusalReason(WriteRequest write) {
        if (write != null && !CHAT_ENTITIES.contains(write.entityName()))
            return Future.succeededFuture(null);
        // Read on the caller's thread: the guard asks every row rule synchronously, and the state is gone after the
        // first async hop.
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        Boolean verifiedBackoffice = StateAccessor.getVerifiedBackoffice(state);
        return judge(write, GrantTableWritePolicy.callerPersonId(userId), Boolean.TRUE.equals(verifiedBackoffice),
            DATABASE_LOOKUP)
            .recover(failure -> Future.succeededFuture("could not check it: " + failure.getMessage()))
            .map(reason -> {
                if (reason == null)
                    return null;
                // The census line. Account identities and entity names, not message content.
                Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " a chat write: "
                            + (write == null ? "?" : write.verb() + " " + write.entityName()) + " by " + userId
                            + (Boolean.TRUE.equals(verifiedBackoffice) ? " (back office)" : "") + " — " + reason);
                return ENFORCING ? REFUSED : null;
            });
    }

    /**
     * The decision: a future of why this write may not run, or of null when it may. Apart from the thread, the
     * switch and the database, so the check can judge any caller against any stored rows.
     */
    static Future<String> judge(WriteRequest write, Object callerPersonId, boolean backoffice, ChatLookup lookup) {
        if (write == null)
            return Future.succeededFuture("the write could not be read");
        String entity = write.entityName();
        if (!CHAT_ENTITIES.contains(entity))
            return Future.succeededFuture(null);
        if (callerPersonId == null)
            return Future.succeededFuture("the caller has no person to write as");
        return backoffice ? judgeStaff(write, callerPersonId) : judgeMember(write, callerPersonId, lookup);
    }

    /** Staff work the inbox, but whatever they attribute to a person is attributed to themselves. */
    private static Future<String> judgeStaff(WriteRequest write, Object caller) {
        String personField = PERSON_FIELD.get(write.entityName());
        if (!writes(write, personField))
            return Future.succeededFuture(null);
        // A conversation's member is whoever opened it, and moving it to somebody else would hand them the whole
        // thread. The inbox never does; it never opens one either, but a new one is not anybody's thread yet.
        if ("Conversation".equals(write.entityName()))
            return Future.succeededFuture(write.verb() == WriteVerb.INSERT ? null : "moves a conversation to another member");
        Object person = value(write, personField);
        if (write.verb() == WriteVerb.INSERT && (person == null || sameId(person, caller)))
            return Future.succeededFuture(null);
        return Future.succeededFuture("attributes a " + write.entityName() + " to somebody else");
    }

    private static Future<String> judgeMember(WriteRequest write, Object caller, ChatLookup lookup) {
        String entity = write.entityName();
        WriteVerb verb = write.verb();
        switch (entity) {
            case "Conversation":
                if (verb == WriteVerb.INSERT) {
                    if (!onlyFields(write, MEMBER_CONVERSATION_INSERT_FIELDS))
                        return refuse("opens a conversation with fields only staff set");
                    if (!sameId(value(write, "viewerPerson"), caller))
                        return refuse("opens a conversation for somebody else");
                    Object status = value(write, "status");
                    if (writes(write, "status") && !MEMBER_CONVERSATION_STATUSES.contains(status))
                        return refuse("opens a conversation in a state only staff set");
                    return ok();
                }
                if (verb == WriteVerb.UPDATE && onlyFields(write, MEMBER_CONVERSATION_UPDATE_FIELDS))
                    return mustBeOwnConversation(write.targetId(), caller, lookup, "changes somebody else's conversation");
                return refuse(verb == WriteVerb.UPDATE ? "changes a conversation field only staff set" : "deletes a conversation");

            case "ConversationParticipant":
                if (verb == WriteVerb.INSERT) {
                    if (!sameId(value(write, "person"), caller) || !"viewer".equals(value(write, "role")))
                        return refuse("joins a conversation as somebody else, or as staff");
                    return conversationIsOwn(write, value(write, "conversation"), caller, lookup,
                        "joins somebody else's conversation");
                }
                if (verb == WriteVerb.UPDATE && onlyFields(write, Set.of("lastReadAt")))
                    return mustOwnRow(write, caller, lookup, "moves somebody else's read cursor");
                return refuse(verb == WriteVerb.UPDATE ? "changes a participant" : "leaves a conversation");

            case "ChatMessage":
                if (verb != WriteVerb.INSERT)
                    return refuse(verb == WriteVerb.UPDATE ? "edits a message" : "deletes a message");
                if (!onlyFields(write, MEMBER_MESSAGE_FIELDS))
                    return refuse("posts a message with fields only staff set");
                Object person = value(write, "person");
                if (writes(write, "person") && person != null && !sameId(person, caller))
                    return refuse("posts as somebody else");
                if (writes(write, "kind") && !MEMBER_MESSAGE_KINDS.contains(value(write, "kind")))
                    return refuse("posts a kind of message only staff post");
                return conversationIsOwn(write, value(write, "conversation"), caller, lookup,
                    "posts into somebody else's conversation");

            case "ChatMessageReaction":
                if (verb == WriteVerb.INSERT) {
                    if (!sameId(value(write, "person"), caller))
                        return refuse("reacts as somebody else");
                    Object message = value(write, "message");
                    if (!isId(message))
                        return refuse("reacts to a message it does not name");
                    return lookup.messageConversation(message).compose(conversation ->
                        conversation == null ? refuse("reacts to a message that does not exist")
                            : mustBeOwnConversation(conversation, caller, lookup, "reacts in somebody else's conversation"));
                }
                if (verb == WriteVerb.DELETE)
                    return mustOwnRow(write, caller, lookup, "removes somebody else's reaction");
                return refuse("changes a reaction");

            case "ChatPresence":
                if (verb == WriteVerb.INSERT) {
                    if (!sameId(value(write, "person"), caller))
                        return refuse("shows somebody else as online");
                    Object agent = value(write, "agent");
                    if (writes(write, "agent") && !Boolean.FALSE.equals(agent))
                        return refuse("shows itself as a support agent");
                    return ok();
                }
                if (verb == WriteVerb.UPDATE && onlyFields(write, Set.of("lastSeenAt")))
                    return mustOwnRow(write, caller, lookup, "keeps somebody else online");
                return refuse(verb == WriteVerb.UPDATE ? "changes a presence" : "deletes a presence");

            default:
                return ok();
        }
    }

    /**
     * The conversation a new row goes into is the caller's own: either a conversation this batch creates, which
     * must itself name the caller, or an existing one whose member is the caller.
     */
    private static Future<String> conversationIsOwn(WriteRequest write, Object conversation, Object caller,
                                                    ChatLookup lookup, String refusal) {
        if (conversation instanceof GeneratedKeyReference reference) {
            WriteRequest created = write.batchWriteAt(reference.getStatementBatchIndex());
            boolean own = created != null && "Conversation".equals(created.entityName())
                          && created.verb() == WriteVerb.INSERT && sameId(value(created, "viewerPerson"), caller);
            return own ? ok() : refuse(refusal);
        }
        return mustBeOwnConversation(conversation, caller, lookup, refusal);
    }

    private static Future<String> mustBeOwnConversation(Object conversationId, Object caller, ChatLookup lookup,
                                                        String refusal) {
        if (!isId(conversationId))
            return refuse("names no conversation it can be judged on");
        return lookup.conversationMember(conversationId).map(member -> sameId(member, caller) ? null : refusal);
    }

    /** An update or delete of a participant, reaction or presence row: the stored row must be the caller's. */
    private static Future<String> mustOwnRow(WriteRequest write, Object caller, ChatLookup lookup, String refusal) {
        if (!isId(write.targetId()))
            return refuse("names no row it can be judged on");
        return lookup.rowPerson(write.entityName(), write.targetId()).map(person -> sameId(person, caller) ? null : refusal);
    }

    private static boolean writes(WriteRequest write, String field) {
        if (field == null || write.writtenFields() == null)
            return false;
        for (String written : write.writtenFields())
            if (field.equals(written))
                return true;
        return false;
    }

    private static boolean onlyFields(WriteRequest write, Set<String> allowed) {
        if (write.writtenFields() == null)
            return false;
        for (String written : write.writtenFields())
            if (!allowed.contains(written))
                return false;
        return true;
    }

    /** The value written to a field: null when written as null or not written, which every caller tells apart. */
    private static Object value(WriteRequest write, String field) {
        return write.writtenValues() == null ? null : write.writtenValues().get(field);
    }

    /** A plain stored id: not null, not unresolved, not a reference into the batch. */
    private static boolean isId(Object value) {
        return value != null && value != ProtectedEntityWriteRegistry.UNRESOLVED_VALUE
               && !(value instanceof GeneratedKeyReference);
    }

    /** Two ids naming the same row, whatever number type each arrived as. False when either is not a plain id. */
    static boolean sameId(Object a, Object b) {
        if (!isId(a) || !isId(b))
            return false;
        return a instanceof Number na && b instanceof Number nb ? na.longValue() == nb.longValue()
            : String.valueOf(a).equals(String.valueOf(b));
    }

    private static Future<String> ok() {
        return Future.succeededFuture(null);
    }

    private static Future<String> refuse(String reason) {
        return Future.succeededFuture(reason);
    }
}

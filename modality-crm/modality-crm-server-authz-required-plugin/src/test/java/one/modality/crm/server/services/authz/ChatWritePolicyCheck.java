package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Check for {@link ChatWritePolicy}: every write the two apps' support chat sends passes, as its own caller, and the
 * same writes aimed at somebody else are refused.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, following ProtectedEntityWritesCheck.
 * The legitimate shapes are copied from the React sources they come from (use-support-conversation.ts,
 * use-refresh-confirmation.ts, use-message-reactions.ts, use-presence-heartbeat.ts, use-conversation-actions.ts),
 * so a change there that this rule would refuse shows up here first.
 */
public class ChatWritePolicyCheck {

    static int pass = 0, fail = 0;

    static final int ME = 100, OTHER = 200, AGENT = 300;
    static final int MY_CONVERSATION = 10, THEIR_CONVERSATION = 20;
    static final int MY_MESSAGE = 1, THEIR_MESSAGE = 2;

    /** A small database: two conversations, one message in each, and rows owned by ME or OTHER by id parity. */
    static final ChatWritePolicy.ChatLookup LOOKUP = new ChatWritePolicy.ChatLookup() {
        @Override
        public Future<Object> conversationMember(Object id) {
            int c = ((Number) id).intValue();
            return Future.succeededFuture(c == MY_CONVERSATION ? (Object) ME : c == THEIR_CONVERSATION ? (Object) OTHER : null);
        }

        @Override
        public Future<Object> rowPerson(String entityName, Object id) {
            return Future.succeededFuture(((Number) id).intValue() % 2 == 1 ? (Object) ME : (Object) OTHER);
        }

        @Override
        public Future<Object> messageConversation(Object id) {
            int m = ((Number) id).intValue();
            return Future.succeededFuture(m == MY_MESSAGE ? (Object) MY_CONVERSATION : m == THEIR_MESSAGE ? (Object) THEIR_CONVERSATION : null);
        }
    };

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    static WriteRequest insert(String entity, Map<String, Object> values) {
        return new WriteRequest(entity, WriteVerb.INSERT, values.keySet().toArray(String[]::new), values, null, false, null);
    }

    static WriteRequest update(String entity, Object targetId, Map<String, Object> values) {
        return new WriteRequest(entity, WriteVerb.UPDATE, values.keySet().toArray(String[]::new), values, targetId, false, null);
    }

    static WriteRequest delete(String entity, Object targetId) {
        return new WriteRequest(entity, WriteVerb.DELETE, new String[0], new HashMap<>(), targetId, false, null);
    }

    static String judge(WriteRequest write, Object caller, boolean backoffice) {
        return ChatWritePolicy.judge(write, caller, backoffice, LOOKUP).result();
    }

    static void passes(String what, WriteRequest write, Object caller, boolean backoffice) {
        String reason = judge(write, caller, backoffice);
        check(what + (reason == null ? "" : " — refused: " + reason), reason == null);
    }

    static void refused(String what, WriteRequest write, Object caller, boolean backoffice) {
        check(what, judge(write, caller, backoffice) != null);
    }

    static String readJob() {
        String path = "modality-crm/modality-crm-server-authz-required-plugin/src/main/java/"
                      + "one/modality/crm/server/services/authz/ProtectedEntityWritesJob.java";
        for (String root : new String[] { "", "modality-fork/" }) {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(root + path));
            } catch (java.io.IOException ignored) {
            }
        }
        return null;
    }

    public static void main(String[] args) {
        System.out.println("the switch:");
        check("ships OBSERVING", !ChatWritePolicy.ENFORCING);
        // A rule nobody registers refuses nothing and logs nothing, which a census reads as "all clean".
        String job = readJob();
        check("is registered among the row rules", job == null || job.contains("new ChatWritePolicy()"));
        if (job == null)
            System.out.println("  (registration not checked: run from the aggregate root or modality-fork)");

        System.out.println("a member opening a conversation, as the widget does (one batch):");
        WriteRequest open = insert("Conversation", values("type", "support", "event", 1989, "scheduledItem", 5,
            "viewerPerson", ME, "status", "queued", "viewerDevice", "d", "viewerLang", "en"));
        WriteRequest[] batch = { open, null };
        WriteRequest join = insert("ConversationParticipant",
            values("conversation", new GeneratedKeyReference(0), "person", ME, "role", "viewer")).withBatchWrites(batch);
        batch[1] = join;
        passes("the conversation, naming the caller", open, ME, false);
        passes("and the caller's participant row, pointing at it", join, ME, false);
        passes("offline: a 'message' conversation",
            insert("Conversation", values("type", "support", "event", 1989, "viewerPerson", ME, "status", "message")), ME, false);
        refused("a conversation for somebody else",
            insert("Conversation", values("type", "support", "event", 1989, "viewerPerson", OTHER, "status", "queued")), ME, false);
        refused("a conversation already assigned",
            insert("Conversation", values("type", "support", "event", 1989, "viewerPerson", ME, "assignee", AGENT)), ME, false);
        refused("a conversation opened as open",
            insert("Conversation", values("type", "support", "event", 1989, "viewerPerson", ME, "status", "open")), ME, false);
        refused("a conversation naming no member",
            insert("Conversation", values("type", "support", "event", 1989, "status", "queued")), ME, false);
        WriteRequest[] otherBatch = { insert("Conversation", values("viewerPerson", OTHER, "status", "queued")), null };
        refused("a participant row pointing at a batch conversation for somebody else",
            insert("ConversationParticipant", values("conversation", new GeneratedKeyReference(0), "person", ME, "role", "viewer"))
                .withBatchWrites(otherBatch), ME, false);
        WriteRequest[] mailBatch = { insert("Mail", values("out", true)), null };
        refused("a participant row pointing at a batch insert that is not a conversation",
            insert("ConversationParticipant", values("conversation", new GeneratedKeyReference(0), "person", ME, "role", "viewer"))
                .withBatchWrites(mailBatch), ME, false);

        System.out.println("a member in their own conversation:");
        passes("re-stamps the device", update("Conversation", MY_CONVERSATION, values("viewerDevice", "d")), ME, false);
        passes("stamps the watched session", update("Conversation", MY_CONVERSATION, values("scheduledItem", 7)), ME, false);
        passes("posts text as themselves",
            insert("ChatMessage", values("conversation", MY_CONVERSATION, "person", ME, "content", "hi")), ME, false);
        passes("posts an image as themselves",
            insert("ChatMessage", values("conversation", MY_CONVERSATION, "person", ME, "kind", "image", "content", "x")), ME, false);
        passes("leaves the widget's unattributed note",
            insert("ChatMessage", values("conversation", MY_CONVERSATION, "kind", "note", "content", "x")), ME, false);
        passes("moves their read cursor", update("ConversationParticipant", 1, values("lastReadAt", "t")), ME, false);
        passes("reacts to a message", insert("ChatMessageReaction", values("message", MY_MESSAGE, "person", ME, "emoji", "x")), ME, false);
        passes("removes their reaction", delete("ChatMessageReaction", 3), ME, false);

        System.out.println("a member reaching somebody else's conversation:");
        refused("posts into it", insert("ChatMessage", values("conversation", THEIR_CONVERSATION, "person", ME, "content", "x")), ME, false);
        refused("leaves a note in it", insert("ChatMessage", values("conversation", THEIR_CONVERSATION, "kind", "note", "content", "x")), ME, false);
        refused("posts into a conversation that does not exist",
            insert("ChatMessage", values("conversation", 999, "person", ME, "content", "x")), ME, false);
        refused("joins it", insert("ConversationParticipant", values("conversation", THEIR_CONVERSATION, "person", ME, "role", "viewer")), ME, false);
        refused("re-stamps it", update("Conversation", THEIR_CONVERSATION, values("viewerDevice", "d")), ME, false);
        refused("moves another's read cursor", update("ConversationParticipant", 2, values("lastReadAt", "t")), ME, false);
        refused("reacts in it", insert("ChatMessageReaction", values("message", THEIR_MESSAGE, "person", ME, "emoji", "x")), ME, false);
        refused("removes another's reaction", delete("ChatMessageReaction", 4), ME, false);

        System.out.println("a member writing as somebody else, or as staff:");
        refused("posts as another person", insert("ChatMessage", values("conversation", MY_CONVERSATION, "person", OTHER, "content", "x")), ME, false);
        refused("posts a system line", insert("ChatMessage", values("conversation", MY_CONVERSATION, "kind", "system", "content", "x")), ME, false);
        refused("edits a message", update("ChatMessage", MY_MESSAGE, values("content", "x", "editedAt", "t")), ME, false);
        refused("deletes a message", delete("ChatMessage", MY_MESSAGE), ME, false);
        refused("assigns their own conversation", update("Conversation", MY_CONVERSATION, values("assignee", ME, "status", "open")), ME, false);
        refused("closes their own conversation", update("Conversation", MY_CONVERSATION, values("status", "closed", "closedAt", "t")), ME, false);
        refused("deletes their own conversation", delete("Conversation", MY_CONVERSATION), ME, false);
        refused("joins as an agent", insert("ConversationParticipant", values("conversation", MY_CONVERSATION, "person", ME, "role", "agent")), ME, false);
        refused("joins as another person", insert("ConversationParticipant", values("conversation", MY_CONVERSATION, "person", OTHER, "role", "viewer")), ME, false);
        refused("reacts as another person", insert("ChatMessageReaction", values("message", MY_MESSAGE, "person", OTHER, "emoji", "x")), ME, false);

        System.out.println("presence:");
        passes("a member's heartbeat row", insert("ChatPresence", values("person", ME, "event", 1989, "agent", false, "lastSeenAt", "t")), ME, false);
        passes("and its update", update("ChatPresence", 1, values("lastSeenAt", "t")), ME, false);
        refused("a member showing as an agent", insert("ChatPresence", values("person", ME, "event", 1989, "agent", true, "lastSeenAt", "t")), ME, false);
        refused("a member showing somebody else online", insert("ChatPresence", values("person", OTHER, "event", 1989, "agent", false)), ME, false);
        refused("a member keeping somebody else online", update("ChatPresence", 2, values("lastSeenAt", "t")), ME, false);
        passes("an agent's heartbeat row", insert("ChatPresence", values("person", AGENT, "event", 1989, "agent", true, "lastSeenAt", "t")), AGENT, true);

        System.out.println("the back office, as the inbox does:");
        passes("takes a conversation", update("Conversation", THEIR_CONVERSATION, values("assignee", AGENT, "status", "open")), AGENT, true);
        passes("joins it as an agent", insert("ConversationParticipant", values("conversation", THEIR_CONVERSATION, "person", AGENT, "role", "agent")), AGENT, true);
        passes("posts the system line", insert("ChatMessage", values("conversation", THEIR_CONVERSATION, "kind", "system", "content", "x")), AGENT, true);
        passes("replies as itself", insert("ChatMessage", values("conversation", THEIR_CONVERSATION, "person", AGENT, "content", "x")), AGENT, true);
        passes("edits a message", update("ChatMessage", THEIR_MESSAGE, values("content", "x", "editedAt", "t")), AGENT, true);
        passes("closes it", update("Conversation", THEIR_CONVERSATION, values("status", "closed", "closedAt", "t")), AGENT, true);
        passes("flags it", update("Conversation", THEIR_CONVERSATION, values("needsFix", true)), AGENT, true);
        refused("but not as another person", insert("ChatMessage", values("conversation", THEIR_CONVERSATION, "person", OTHER, "content", "x")), AGENT, true);
        refused("nor joins somebody else in", insert("ConversationParticipant", values("conversation", THEIR_CONVERSATION, "person", OTHER, "role", "agent")), AGENT, true);
        refused("nor rewrites a message's author", update("ChatMessage", THEIR_MESSAGE, values("person", AGENT)), AGENT, true);
        refused("nor moves a conversation to another member", update("Conversation", THEIR_CONVERSATION, values("viewerPerson", AGENT)), AGENT, true);

        System.out.println("no person to act as:");
        refused("anonymous posts nothing", insert("ChatMessage", values("conversation", MY_CONVERSATION, "content", "x")), null, false);
        refused("nor does a back-office token without a person", insert("ChatMessage", values("conversation", MY_CONVERSATION, "content", "x")), null, true);

        System.out.println("what the parser could not read:");
        refused("an update whose target is unknown", update("Conversation", null, values("viewerDevice", "d")), ME, false);
        refused("a conversation written as an expression",
            insert("ChatMessage", values("conversation", ProtectedEntityWriteRegistry.UNRESOLVED_VALUE, "person", ME, "content", "x")), ME, false);
        refused("a member named as an expression",
            insert("Conversation", values("viewerPerson", ProtectedEntityWriteRegistry.UNRESOLVED_VALUE, "status", "queued")), ME, false);
        check("ids compare across number types", ChatWritePolicy.sameId(100, 100L));
        refused("an unreadable write", null, ME, false);
        check("other tables are not this rule's", judge(insert("Event", values("name", "x")), null, false) == null);

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

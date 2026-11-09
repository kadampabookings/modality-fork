package one.modality.crm.server.person;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.BusService;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays a member's live support-chat signal — what they are typing, whether they watch fullscreen — to the
 * support agents, and to nobody else.
 *
 * <p><b>Why an endpoint.</b> The front office used to publish these itself, on {@code front-office/messaging}: the
 * one broadcast address every browser listens on, signed in or not. So every member's draft — including what they
 * typed and deleted — reached any connected browser, in every conversation, and any browser could forge one. Now
 * the member's browser calls this; the server checks the caller is the conversation's member and publishes the
 * signal on {@link #SUPPORT_AGENTS_ADDRESS}, which the bridge delivers to verified back-office sessions only and
 * refuses to accept from any client (BridgeAddressPolicy, registered by ProtectedEntityWritesJob). Agents keep the
 * draft preview; the wire shape is unchanged, only the address moved.
 *
 * <p><b>Argument</b>: {@code [conversationId, field, value]}. {@code field} is {@code viewerDraft} (a string, or
 * null to clear) or {@code viewerFullscreen} (true, or null to clear). Anything else is refused.
 *
 * <p><b>Cost.</b> A typing member calls this up to every 400 ms, so the membership check is cached per conversation
 * for {@link #MEMBER_CACHE_MILLIS}; a conversation's member never changes (ChatWritePolicy refuses moving it).
 *
 * <p><b>Personal data.</b> A draft is support-chat free text, so it may hold health or accessibility details. It is
 * relayed, never stored and never logged.
 *
 * @author Claude Code
 */
public final class ChatTypingSignalEndpoint extends AsyncFunctionBusCallEndpoint<Object, Boolean> {

    /** The bus address — mirrored in the React client's BUS_ADDRESSES.CHAT_TYPING_SIGNAL. */
    public static final String CHAT_TYPING_SIGNAL_ADDRESS = "modality/service/chat/typingSignal";

    /**
     * Where the server publishes the signals for the agents — mirrored in the React client's
     * SUPPORT_AGENTS_ADDRESS, and registered with BridgeAddressPolicy by ProtectedEntityWritesJob.
     */
    public static final String SUPPORT_AGENTS_ADDRESS = "support/agents";

    static final Set<String> FIELDS = Set.of("viewerDraft", "viewerFullscreen");

    /** Longer than any message a member would type; the composer's own limit is lower. */
    static final int MAX_DRAFT_LENGTH = 4000;

    static final long MEMBER_CACHE_MILLIS = 5 * 60_000;
    private static final int MEMBER_CACHE_MAX = 10_000;

    private record Member(Object personId, long expiresAt) { }

    private static final Map<Object, Member> MEMBERS = new ConcurrentHashMap<>();

    public ChatTypingSignalEndpoint() {
        super(CHAT_TYPING_SIGNAL_ADDRESS, argument -> {
            Object[] args = argument instanceof Object[] array ? array : null;
            Object conversationId = args != null && args.length > 0 ? args[0] : null;
            Object field = args != null && args.length > 1 ? args[1] : null;
            Object value = args != null && args.length > 2 ? args[2] : null;
            // Read on the caller's thread, before anything async: who is asking.
            return MemberSessionGuard.whenCallerIsVerifiedMember((personId, accountId) -> {
                if (!isValidSignal(conversationId, field, value))
                    return MemberSessionGuard.refused();
                return conversationMember(conversationId).compose(member -> {
                    if (!sameId(member, personId))
                        return MemberSessionGuard.refused();
                    BusService.bus().publish(SUPPORT_AGENTS_ADDRESS, entityChange(conversationId, (String) field, value));
                    return Future.succeededFuture(true);
                });
            });
        });
    }

    /** The argument's shape: a numeric conversation, a known field, and a value that field may carry. */
    static boolean isValidSignal(Object conversationId, Object field, Object value) {
        if (!(conversationId instanceof Number) || !(field instanceof String name) || !FIELDS.contains(name))
            return false;
        if (value == null)
            return true;
        if ("viewerDraft".equals(name))
            return value instanceof String draft && draft.length() <= MAX_DRAFT_LENGTH;
        return Boolean.TRUE.equals(value);
    }

    /** The EntityResultImpl shape the React listeners decode (entity-result-codec.ts). */
    static AstObject entityChange(Object conversationId, String field, Object value) {
        AstObject values = AST.createObject().set(field, value);
        AstObject entity = AST.createObject().set("class", "Conversation").set("pk", conversationId).set("val", values);
        AstArray entities = AST.createArray().push(entity);
        return AST.createObject().set("$codec", "EntityResultImpl").set("entities", entities);
    }

    private static Future<Object> conversationMember(Object conversationId) {
        long now = System.currentTimeMillis();
        Member cached = MEMBERS.get(conversationId);
        if (cached != null && cached.expiresAt() > now)
            return Future.succeededFuture(cached.personId());
        return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel())
            .<Entity>executeQuery("select viewerPerson from Conversation where id=$1", conversationId)
            .map(rows -> {
                Object member = rows.isEmpty() ? null : Entities.getPrimaryKey(rows.get(0).getForeignEntityId("viewerPerson"));
                if (MEMBERS.size() >= MEMBER_CACHE_MAX)
                    MEMBERS.values().removeIf(m -> m.expiresAt() <= now);
                if (member != null && MEMBERS.size() < MEMBER_CACHE_MAX)
                    MEMBERS.put(conversationId, new Member(member, now + MEMBER_CACHE_MILLIS));
                return member;
            });
    }

    private static boolean sameId(Object a, Object b) {
        if (a == null || b == null)
            return false;
        return a instanceof Number na && b instanceof Number nb ? na.longValue() == nb.longValue() : Objects.equals(a, b);
    }
}

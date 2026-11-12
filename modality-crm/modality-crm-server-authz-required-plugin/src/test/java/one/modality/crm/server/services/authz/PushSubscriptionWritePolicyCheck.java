package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Check for {@link PushSubscriptionWritePolicy}: every write use-push-subscription.ts sends passes for its own caller
 * (a member, a member subscribing to a family member's booking, a guest), and the same writes naming somebody else's
 * address, person or booking, or redirecting a device, are refused. Also {@link CallerLabel}: a guest's address never
 * reaches a log line.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, following ChatWritePolicyCheck.
 */
public class PushSubscriptionWritePolicyCheck {

    static int pass = 0, fail = 0;

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

    // Member ME (person 100, account 1000): proved addresses me@x (person) and login@x (account); a family member's
    // fam@x is typed, not proved. Booking 501 is mine, 502 a family member's (in my account), 600 someone else's.
    // Guest's cart address guest@x (typed, not proved); booking 700 was made under it.
    static final int ME = 100, MY_ACCOUNT = 1000, OTHER = 200;
    static final PushSubscriptionWritePolicy.Caller MEMBER = new PushSubscriptionWritePolicy.Caller(ME, MY_ACCOUNT, null);
    static final PushSubscriptionWritePolicy.Caller GUEST = new PushSubscriptionWritePolicy.Caller(null, null, "guest@x");
    static final PushSubscriptionWritePolicy.Caller NOBODY = new PushSubscriptionWritePolicy.Caller(null, null, null);

    static final PushSubscriptionWritePolicy.OwnershipLookup LOOKUP = new PushSubscriptionWritePolicy.OwnershipLookup() {
        @Override
        public Future<Set<String>> ownEmails(Object personId, Object accountId) {
            return Future.succeededFuture(Set.of("me@x", "login@x"));
        }

        @Override
        public Future<Boolean> documentInAccount(Object documentId, Object accountId) {
            int d = Integer.parseInt(String.valueOf(documentId));
            return Future.succeededFuture(d == 501 || d == 502);
        }

        @Override
        public Future<String> documentEmail(Object documentId) {
            return Future.succeededFuture(switch (Integer.parseInt(String.valueOf(documentId))) {
                case 501 -> "me@x";
                case 502 -> "booked-with@x";
                case 600 -> "victim@x";
                case 700 -> "guest@x";
                default -> null;
            });
        }
    };

    static String judge(WriteRequest write, PushSubscriptionWritePolicy.Caller caller) {
        return PushSubscriptionWritePolicy.judge(write, false, caller, LOOKUP).result();
    }

    static void passes(String what, String reason) {
        check(what + (reason == null ? "" : " — refused: " + reason), reason == null);
    }

    static void refused(String what, String reason) {
        check(what + (reason == null ? " — PASSED" : ""), reason != null);
    }

    static WriteRequest recipient(Object... pairs) {
        Map<String, Object> v = values("subscription", new GeneratedKeyReference(0), "context", "booking");
        v.putAll(values(pairs));
        return insert("PushSubscriptionRecipient", v);
    }

    public static void main(String[] args) {
        System.out.println("the switch:");
        check("ships OBSERVING", !PushSubscriptionWritePolicy.ENFORCING);
        String job = ChatWritePolicyCheck.readJob();
        check("is registered among the row rules", job == null || job.contains("new PushSubscriptionWritePolicy()"));

        System.out.println("a device, as use-push-subscription.ts writes it:");
        passes("a new subscription", judge(insert("PushSubscription", values("endpoint", "https://push/x", "p256dhKey", "k",
            "authKey", "a", "lang", "en", "userAgent", "ua", "lastSeenAt", "t", "vapidPublicKey", "v")), NOBODY));
        passes("its keys and facts refreshed", judge(update("PushSubscription", 9, values("p256dhKey", "k", "authKey", "a",
            "lang", "fr", "timezone", "Europe/London", "displayMode", "standalone", "appTimestamp", "1", "userAgent", "ua",
            "lastSeenAt", "t", "vapidPublicKey", "v")), MEMBER));
        refused("its endpoint rewritten (redirecting someone's notifications)",
            judge(update("PushSubscription", 9, values("endpoint", "https://attacker/x", "p256dhKey", "k")), NOBODY));
        passes("a device removing itself", judge(delete("PushSubscription", 9), MEMBER));

        System.out.println("a member subscribing:");
        passes("to their own booking, with their own address", judge(recipient("person", ME, "email", "me@x", "document", 501), MEMBER));
        passes("to a family member's booking in their account", judge(recipient("person", ME, "email", "me@x", "document", 502), MEMBER));
        passes("with their account's login", judge(recipient("person", ME, "email", "login@x", "document", 502), MEMBER));
        passes("addresses compared without case", judge(recipient("person", "100", "email", " Me@X ", "document", 501), MEMBER));
        passes("to audio recordings (a channel), as themselves",
            judge(recipient("context", "audio-recordings", "person", ME, "email", "me@x"), MEMBER));
        passes("to an event's broadcasts with no identity", judge(recipient("context", "event", "event", 7), NOBODY));

        System.out.println("somebody else's notifications:");
        refused("another person", judge(recipient("person", OTHER, "email", "me@x"), MEMBER));
        refused("another member's address (the leak: delivery matches by email)", judge(recipient("person", ME, "email", "victim@x"), MEMBER));
        refused("a family member's typed address (review finding 1: added with any address)", judge(recipient("person", ME, "email", "fam@x"), MEMBER));
        refused("the address a booking was typed with (review finding 1)", judge(recipient("person", ME, "email", "booked-with@x", "document", 502), MEMBER));
        refused("another account's booking", judge(recipient("person", ME, "email", "me@x", "document", 600), MEMBER));
        refused("another account's booking, with its address", judge(recipient("person", ME, "email", "victim@x", "document", 600), MEMBER));
        refused("signed out, naming an address", judge(recipient("email", "victim@x"), NOBODY));
        refused("signed out, naming a person", judge(recipient("person", OTHER), NOBODY));
        refused("an address written as an expression", judge(recipient("person", ME, "email", ProtectedEntityWriteRegistry.UNRESOLVED_VALUE), MEMBER));
        refused("a booking pointing into the batch", judge(recipient("person", ME, "document", new GeneratedKeyReference(1)), MEMBER));
        refused("a recipient changed", judge(update("PushSubscriptionRecipient", 3, values("email", "victim@x")), MEMBER));
        refused("a recipient removed", judge(delete("PushSubscriptionRecipient", 3), MEMBER));
        refused("a recipient with a field the front office never sets",
            judge(recipient("person", ME, "email", "me@x", "unsubscribedAt", null), MEMBER));

        System.out.println("a guest (signed out; the cart's address was typed, so it proves nothing):");
        passes("their booking, no address", judge(recipient("document", 700), GUEST));
        refused("their cart's address (review finding 2: a guest booking can be made under any address)",
            judge(recipient("email", "guest@x", "document", 700), GUEST));
        refused("another address", judge(recipient("email", "victim@x"), GUEST));
        refused("a booking made under another address", judge(recipient("document", 600), GUEST));
        refused("a person", judge(recipient("person", OTHER, "email", "guest@x"), GUEST));

        System.out.println("the back office, and other tables:");
        check("the back office subscribes anyone",
            PushSubscriptionWritePolicy.judge(recipient("person", OTHER, "email", "victim@x"), true, MEMBER, LOOKUP).result() == null);
        check("other tables are not this rule's", judge(insert("Event", values("name", "x")), NOBODY) == null);

        System.out.println("who the caller is, and how a log line names them:");
        PushSubscriptionWritePolicy.Caller guest = PushSubscriptionWritePolicy.callerOf(new ModalityGuestPrincipal("Guest@X"));
        check("a guest is their proved address, lower-cased", "guest@x".equals(guest.guestEmail()) && guest.personId() == null);
        PushSubscriptionWritePolicy.Caller member = PushSubscriptionWritePolicy.callerOf(new ModalityUserPrincipal(ME, MY_ACCOUNT));
        check("a member is their person and account", ChatWritePolicy.sameId(member.personId(), ME) && ChatWritePolicy.sameId(member.accountId(), MY_ACCOUNT));
        check("the support view is nobody", PushSubscriptionWritePolicy.callerOf(new ModalityUserPrincipal(ME, MY_ACCOUNT, 300)).isNobody());
        check("a guest's address never reaches a log line", "guest".equals(CallerLabel.of(new ModalityGuestPrincipal("guest@x"))));
        check("a member is still named by their ids", CallerLabel.of(new ModalityUserPrincipal(ME, MY_ACCOUNT)).contains("100"));

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

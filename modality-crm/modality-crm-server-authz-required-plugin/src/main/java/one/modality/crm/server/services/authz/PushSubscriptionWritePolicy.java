package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A device subscribes to push notifications for its own person, its own proved address and its own bookings, and a
 * subscription's endpoint is never rewritten.
 *
 * <p><b>The hole (2026-11-12).</b> The front office writes {@code PushSubscription} (one row per device: the push
 * service endpoint and its keys) and {@code PushSubscriptionRecipient} (what that device wants to hear about: a
 * context, and a person, email, booking, event or organisation) through the generic submit, and nothing asked whose
 * rows they were. The server picks the devices for a booking's notification by the recipient row's EMAIL or PERSON
 * (WebPushMailTransmitter.loadLiveSubscriptions, and live_push_endpoints() in V0055), so anyone — signed out
 * included — could register their own browser and add a recipient naming somebody else's address, and receive that
 * member's booking notifications from then on. Rewriting another device's endpoint by id would have redirected its
 * notifications the same way.
 *
 * <p><b>The line, measured on staging (2026-11-12).</b> All 24 recipient rows name a person; 22 name that person's
 * own address (their email, which is also their account's login); the other 2, created the same day in June, match
 * neither the person, the account's members nor the booking — believed tests. So, outside the back office:
 * <ul>
 *   <li><b>A recipient row</b> may be inserted only; its {@code person}, when set, is the caller's own; its
 *       {@code email}, when set, is an address the caller has PROVED — their own person's email or their account's
 *       login, nothing else; its {@code document}, when set, is a booking of the caller's account. A guest names no
 *       person and no address (a booking made under their cart's address at most), and a caller with nobody to be
 *       names nothing at all.</li>
 *   <li><b>A subscription</b> may be inserted, and updated — but never its {@code endpoint}, which is where the
 *       notifications go.</li>
 * </ul>
 *
 * <p><b>Why only a PROVED address</b> (security review, 2026-11-12, which found the first version open). Delivery
 * matches by address across every account, so an address a client can merely type is somebody else's inbox: an
 * account member's email is typed by the account (AddMemberEndpoint asks for no proof, by design), a booking's
 * {@code person_email} is typed by whoever booked, and a guest's address comes from the cart link of a booking where
 * it was typed - so a member could add a "member" with a victim's address, or book as a guest under it, and subscribe
 * to it. None of those counts. The cost: a guest cannot be notified by address, and a booker cannot subscribe with
 * an address other than their own; delivery matching guests by {@code document} would restore the first safely.
 *
 * <p><b>And never redirected from outside.</b> Besides this rule, the endpoint itself is no longer readable by
 * clients (ClientReadSecrets, equality only): /rest/push/rotate-subscription moves a subscription given just its old
 * endpoint and asks no sign-in, so a readable endpoint was a redirect anyone could perform.
 *
 * <p><b>What it does not close.</b> Subscription ids are sequential, so a client can, by id, overwrite another
 * device's keys, attach a recipient of its own to it, or delete it: that silences or spams the other device, it does
 * not deliver its notifications elsewhere. An event or
 * organisation recipient with no identity hears that event's or organisation's broadcasts, which are not personal.
 *
 * <p><b>Observed first</b> ("WOULD REFUSE a push subscription write"); its REFUSED line will need a CloudWatch filter.
 *
 * @author Claude Code
 */
final class PushSubscriptionWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** False while it observes: decisions are logged but nothing is refused. */
    static final boolean ENFORCING = false;

    static final String SUBSCRIPTION = "PushSubscription", RECIPIENT = "PushSubscriptionRecipient";

    /** Told to the caller. */
    static final String REFUSED = "Notifications can only be subscribed to for yourself";

    /** What a device may refresh on its own subscription (use-push-subscription.ts) - everything but the endpoint. */
    static final Set<String> SUBSCRIPTION_UPDATE_FIELDS = Set.of(
        "p256dhKey", "authKey", "lang", "timezone", "displayMode", "appTimestamp", "userAgent", "lastSeenAt", "vapidPublicKey");

    /** The fields of a recipient row the front office writes ({@code userAgent} from older bundles). */
    static final Set<String> RECIPIENT_FIELDS = Set.of(
        "subscription", "context", "person", "email", "document", "event", "organization", "userAgent");

    /** The caller, as the rule needs it: a member's person and account, or a guest's proven address, or nobody. */
    record Caller(Object personId, Object accountId, String guestEmail) {
        boolean isNobody() {
            return personId == null && guestEmail == null;
        }
    }

    /** The reads ownership needs. An interface so the check can answer them without a database. */
    interface OwnershipLookup {
        /** Lower-cased addresses the member has proved: their own person's email and their account's login. */
        Future<Set<String>> ownEmails(Object personId, Object accountId);

        /** Whether this booking belongs to this account (its person in it, or linked to a member of it). */
        Future<Boolean> documentInAccount(Object documentId, Object accountId);

        /** The booking's email as it was booked ({@code person_email}), lower-cased, or null. */
        Future<String> documentEmail(Object documentId);
    }

    static final OwnershipLookup DATABASE_LOOKUP = new OwnershipLookup() {
        @Override
        public Future<Set<String>> ownEmails(Object personId, Object accountId) {
            EntityStore store = store();
            // Only an OWNER's email is proved: a non-owner member's email is typed and editable by the account
            // (UpdatePersonDetailsEndpoint guards owner rows only), so if a principal ever names one, it does not count.
            return store.<Entity>executeQuery("select email from Person where id=$1 and owner", ProtectedEntityWritesJob.primaryKeyOf(personId))
                .compose(people -> store.<Entity>executeQuery("select username from FrontendAccount where id=$1",
                        ProtectedEntityWritesJob.primaryKeyOf(accountId))
                    .map(accounts -> {
                        Set<String> emails = new HashSet<>();
                        for (Entity p : people)
                            add(emails, p.getStringFieldValue("email"));
                        for (Entity a : accounts)
                            add(emails, a.getStringFieldValue("username"));
                        return emails;
                    }));
        }

        @Override
        public Future<Boolean> documentInAccount(Object documentId, Object accountId) {
            Object id = ProtectedEntityWritesJob.primaryKeyOf(documentId), account = ProtectedEntityWritesJob.primaryKeyOf(accountId);
            // Two statements, not one OR: a dotted path outside SELECT is an inner join (see MediaConsumptionWritePolicy).
            return exists("select id from Document where id=$1 and person.(frontendAccount=$2 and accountPerson=null)", id, account)
                .compose(direct -> direct ? Future.succeededFuture(true)
                    : exists("select id from Document where id=$1 and person.accountPerson.frontendAccount=$2", id, account));
        }

        @Override
        public Future<String> documentEmail(Object documentId) {
            return store().<Entity>executeQuery("select person_email from Document where id=$1",
                    ProtectedEntityWritesJob.primaryKeyOf(documentId))
                .map(rows -> rows.isEmpty() ? null : lower(rows.get(0).getStringFieldValue("person_email")));
        }

        private Future<Boolean> exists(String dql, Object... parameters) {
            return store().executeQuery(dql, parameters).map(rows -> !rows.isEmpty());
        }

        private EntityStore store() {
            return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        }
    };

    private static void add(Set<String> emails, String email) {
        String e = lower(email);
        if (e != null)
            emails.add(e);
    }

    static String lower(Object email) {
        if (!(email instanceof String s) || s.isBlank())
            return null;
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** The caller as this rule sees it; the support view acts for nobody (see GrantTableWritePolicy.callerPersonId). */
    static Caller callerOf(Object userId) {
        if (userId instanceof ModalityGuestPrincipal guest)
            return new Caller(null, null, lower(guest.getEmail()));
        return new Caller(GrantTableWritePolicy.callerPersonId(userId), MediaConsumptionWritePolicy.callerAccountId(userId), null);
    }

    @Override
    public Future<String> refusalReason(WriteRequest write) {
        if (write != null && !SUBSCRIPTION.equals(write.entityName()) && !RECIPIENT.equals(write.entityName()))
            return Future.succeededFuture(null);
        // Read on the caller's thread: the guard asks every row rule synchronously, and the state is gone after the
        // first async hop.
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        boolean backoffice = Boolean.TRUE.equals(StateAccessor.getVerifiedBackoffice(state));
        return judge(write, backoffice, callerOf(userId), DATABASE_LOOKUP)
            .recover(failure -> Future.succeededFuture("could not check it: " + failure.getMessage()))
            .map(reason -> {
                if (reason == null)
                    return null;
                // Entity, verb, reason and the caller's account identity - never the address or the endpoint.
                Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " a push subscription write: "
                            + (write == null ? "?" : write.verb() + " " + write.entityName()) + " by "
                            + CallerLabel.of(userId) + " — " + reason);
                return ENFORCING ? REFUSED : null;
            });
    }

    /** The decision, apart from the thread, the switch and the database. */
    static Future<String> judge(WriteRequest write, boolean backoffice, Caller caller, OwnershipLookup lookup) {
        if (write == null || backoffice)
            return ok(); // a null write is refused already, by BackofficeOnlyWritePolicy
        if (SUBSCRIPTION.equals(write.entityName()))
            return judgeSubscription(write);
        if (RECIPIENT.equals(write.entityName()))
            return judgeRecipient(write, caller, lookup);
        return ok();
    }

    private static Future<String> judgeSubscription(WriteRequest write) {
        if (write.verb() != WriteVerb.UPDATE)
            return ok(); // a new device, or one removing itself - see "What it does not close"
        if (!onlyFields(write, SUBSCRIPTION_UPDATE_FIELDS))
            return refuse("rewrites where a device's notifications go");
        return ok();
    }

    private static Future<String> judgeRecipient(WriteRequest write, Caller caller, OwnershipLookup lookup) {
        if (write.verb() != WriteVerb.INSERT)
            return refuse(write.verb() == WriteVerb.DELETE ? "removes a recipient" : "changes a recipient"); // the server unsubscribes
        if (!onlyFields(write, RECIPIENT_FIELDS))
            return refuse("subscribes with fields the front office does not set");
        Object person = value(write, "person"), document = value(write, "document");
        String email = lower(value(write, "email"));
        boolean namesEmail = value(write, "email") != null;
        if (person == null && !namesEmail && document == null)
            return ok(); // a channel with no identity: an event's or organisation's broadcasts
        if (caller.isNobody())
            return refuse("subscribes someone with nobody signed in");
        if (person != null && !ChatWritePolicy.sameId(person, caller.personId()))
            return refuse("subscribes another person");
        if (document != null && !isId(document))
            return refuse("names a booking it cannot be judged on");
        if (namesEmail && email == null)
            return refuse("names an address it cannot be judged on");
        if (caller.guestEmail() != null) {
            // A guest's address comes from a cart link, i.e. it was typed: it proves nothing (see the class comment).
            if (namesEmail)
                return refuse("subscribes an address as a guest");
            if (document == null)
                return ok();
            return lookup.documentEmail(document).map(booked ->
                caller.guestEmail().equals(booked) ? null : "subscribes to a booking not made under the guest's address");
        }
        Future<String> bookingOk = document == null ? ok()
            : lookup.documentInAccount(document, caller.accountId()).map(own ->
                Boolean.TRUE.equals(own) ? null : "subscribes to another account's booking");
        return bookingOk.compose(bookingRefusal -> {
            if (bookingRefusal != null || !namesEmail)
                return Future.succeededFuture(bookingRefusal);
            return lookup.ownEmails(caller.personId(), caller.accountId()).map(own ->
                own.contains(email) ? null : "subscribes an address the caller has not proved");
        });
    }

    private static boolean onlyFields(WriteRequest write, Set<String> allowed) {
        if (write.writtenFields() == null || write.writtenFields().length == 0)
            return false;
        for (String field : write.writtenFields())
            if (!allowed.contains(field))
                return false;
        return true;
    }

    private static Object value(WriteRequest write, String field) {
        Map<String, Object> values = write.writtenValues();
        return values == null ? null : values.get(field);
    }

    private static boolean isId(Object value) {
        return value != null && value != ProtectedEntityWriteRegistry.UNRESOLVED_VALUE
               && !(value instanceof GeneratedKeyReference);
    }

    private static Future<String> ok() {
        return Future.succeededFuture(null);
    }

    private static Future<String> refuse(String reason) {
        return Future.succeededFuture(reason);
    }
}

package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Set;

/**
 * Back office by default: a client without a verified back-office token writes only the tables the front office
 * writes. Every other table is the back office's.
 *
 * <p><b>Why a list of what is allowed, not of what is closed (2026-11-12).</b> Until now a table was open to any
 * client until someone noticed it: support chat (2026-11-09) and viewing records (2026-11-10) were found by the
 * write census, and ResourceConfiguration (2026-11-12) by chance — any signed-in member could change a room's type,
 * which fires the cascade that reprices every booking on it. Each was closed by adding a rule, and every table added
 * later starts open again. Turned round, a forgotten table is closed and a new one starts closed; the front office's
 * list is short and comes from its code.
 *
 * <p><b>The list, from the code (kbs3-react {@code frontoffice/src} and the {@code shared/src} hooks it uses, every
 * {@code insert/update/delete} through a change set and every DQL write statement, 2026-11-12):</b> support chat
 * (five tables), the mail it composes (Mail, Recipient), viewing records, error reports, livestream support
 * reports, the volunteer application form, push subscriptions (two) and Label, which the front office's
 * translation page writes. Bookings and people are not on it: the front office reaches them through server
 * endpoints, the booking tables are back office only ({@link BackofficeOnlyWritePolicy}) and Person is denied
 * outright ({@link ClientWriteSecrets}).
 *
 * <p><b>Being on the list is not being safe.</b> This rule says only who may write a table at all. Whose rows a
 * member may write is each table's own rule: {@link ChatWritePolicy}, {@link MediaConsumptionWritePolicy},
 * {@link MailWritePolicy}, {@link LabelWritePolicy}, {@link VolunteerWritePolicy}. SupportReport and the push tables
 * have no such rule yet. And a listed table reaches past the list through
 * the database: deleting a VolunteeringApplication cascades to the back office's volunteering_assignment and
 * volunteering_communication rows, and a Mail insert's triggers update its booking (security review, 2026-11-12).
 *
 * <p>The verified audience, never the client's claim: see {@link BackofficeOnlyWritePolicy}. The legacy GWT back
 * office never obtains a verified token, so it falls on the front office's side of this line — accepted
 * (2026-11-12): it is being left behind. Server code never passes through this guard.
 *
 * <p><b>Observed first.</b> Row rules are enforced the moment they deploy, and a front-office write missing from the
 * list would break for every member at once; this ships logging "WOULD REFUSE a write to a back-office table" and is
 * switched on after a census. Its REFUSED line will need a CloudWatch filter of its own.
 *
 * @author Claude Code
 */
final class FrontOfficeTablesWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** False while it observes: decisions are logged but nothing is refused. */
    static final boolean ENFORCING = false;

    /** The tables a client without a verified back-office token may write. Entity names, as the write request carries them. */
    static final Set<String> FRONT_OFFICE_ENTITIES = Set.of(
        // Support chat - ChatWritePolicy decides whose rows
        "Conversation", "ConversationParticipant", "ChatMessage", "ChatMessageReaction", "ChatPresence",
        // Mail the front office composes (chat transcripts, volunteer replies, member emails) - MailWritePolicy
        "Mail", "Recipient",
        // Viewing records - MediaConsumptionWritePolicy
        "MediaConsumption",
        // Error reports (use-error-reporter.ts, signed out too) and livestream support reports
        "Error", "SupportReport",
        // The public volunteer application form. The answers to the coordinators' emailed links go through
        // AnswerVolunteerArrivalEndpoint / AnswerVolunteerDatesEndpoint (2026-11-12), so the proposal and timeline
        // tables are the back office's.
        "VolunteeringApplication",
        // Web push subscriptions
        "PushSubscription", "PushSubscriptionRecipient",
        // The front office's translation page (translate-event)
        "Label");

    /** Told to the caller. */
    static final String REFUSED = "This change can only be made from the back office";

    @Override
    public Future<String> refusalReason(ProtectedEntityWriteRegistry.WriteRequest write) {
        // Read on the caller's thread: the guard asks every row rule synchronously, before any async hop.
        return Future.succeededFuture(judge(write, ThreadLocalStateHolder.getThreadLocalState(), ENFORCING));
    }

    /** The rule as it runs, given the caller's state and the switch, so the check can see the post-flip answer. */
    static String judge(ProtectedEntityWriteRegistry.WriteRequest write, Object state, boolean enforcing) {
        if (write == null) // refused already, by BackofficeOnlyWritePolicy: one refusal is enough
            return null;
        Boolean verifiedBackoffice = StateAccessor.getVerifiedBackoffice(state);
        if (!refuses(write.entityName(), verifiedBackoffice))
            return null;
        // The census line: the principal and the audience, so a real member of staff left out is visible.
        Console.log("🛡 " + (enforcing ? "REFUSED" : "WOULD REFUSE") + " a write to a back-office table: " + write.verb()
                    + " " + write.entityName() + " by " + StateAccessor.getUserId(state)
                    + " (" + (verifiedBackoffice == null ? "no verified token" : "a front-office token") + ")");
        return enforcing ? REFUSED : null;
    }

    /** Whether this table is closed to this caller - apart from the switch, the log and the thread. */
    static boolean refuses(String entityName, Boolean verifiedBackoffice) {
        if (Boolean.TRUE.equals(verifiedBackoffice))
            return false;
        if (FRONT_OFFICE_ENTITIES.contains(entityName))
            return false;
        // The booking tables have their own back-office-only rule, already enforcing; judging them twice would
        // only log every refusal twice.
        return !BackofficeOnlyWritePolicy.BOOKING_ENTITIES.contains(entityName);
    }
}

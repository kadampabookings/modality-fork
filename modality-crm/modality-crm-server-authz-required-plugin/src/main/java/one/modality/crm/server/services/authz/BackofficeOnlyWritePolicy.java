package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.Set;

/**
 * A client write to a booking's own tables — {@code Document}, {@code DocumentLine}, {@code Attendance} — must come
 * from the back office, as proven by a verified back-office token.
 *
 * <p><b>The hole (found 2026-10-19).</b> Nothing on the generic write path asked who was writing a booking. A
 * modified client could {@code update Document set cancelled=true where id=N} on anybody's booking — ids are
 * sequential — or reprice its lines, or rewrite its attendance, and the database triggers would faithfully
 * cascade and recompute it all, since they do not ask either. The only rules standing there were "name the rows
 * you change" and one denied column (the letter trigger).
 *
 * <p><b>Why "the back office" is the right line, and is cheap.</b> The front office NEVER writes these tables
 * through the generic API: a booking, a modification, a cancellation and a room-mate change all go through
 * {@code DocumentService.submitDocumentChanges}, which decides ownership itself, and the production write census
 * shows no such shape from it. The React back office does write them — registration, rooms, resource configuration,
 * education programmes — and it carries a verified back-office token. So the line costs no legitimate client a
 * thing, and needs no ownership logic to draw.
 *
 * <p>The verified audience, never the client's claim: {@link StateAccessor#getVerifiedBackoffice} is set from a
 * token whose signature held, and cleared from every incoming message first (see the document service's
 * VerifiedBackoffice). Only TRUE passes. FALSE is a front-office token; null is no token at all — anonymous, a
 * lapsed session, or the legacy JavaFX back office, which never obtains one and is being retired.
 *
 * <p><b>What it does not close:</b> a member of staff in one organization can still write another's bookings.
 * That is item C of the write-authorization plan — ownership and organization scope — and it can be built on the
 * write rule's authorizationContexts. This closes members and anonymous callers, which is the larger door.
 *
 * <p><b>Observing first.</b> Row rules are enforced the moment they deploy, unlike the per-entity rule, so one
 * written wrong refuses real work at once. Until {@link #ENFORCING} is flipped this only logs what it would refuse,
 * so the census can show that the back office always arrives with its token.
 *
 * @author Claude Code
 */
final class BackofficeOnlyWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** False while observing. Flip after a clean census — and add a log filter for {@link #REFUSED} the same day. */
    static final boolean ENFORCING = false;

    /** The booking's own tables. Entity names, as the write request carries them. */
    static final Set<String> BOOKING_ENTITIES = Set.of("Document", "DocumentLine", "Attendance");

    /** Told to the caller. */
    static final String REFUSED = "A booking is changed through the booking service, not by a client write";

    @Override
    public Future<String> refusalReason(ProtectedEntityWriteRegistry.WriteRequest write) {
        // Read on the caller's thread: the guard asks every row rule synchronously, before any async hop.
        return Future.succeededFuture(judge(write, ThreadLocalStateHolder.getThreadLocalState(), ENFORCING));
    }

    /**
     * The rule as it runs, given the caller's state and the switch — package-private so the check can hand it a
     * state that CLAIMS the back office without a verified token, and see the post-flip answer while the switch
     * is off.
     */
    static String judge(ProtectedEntityWriteRegistry.WriteRequest write, Object state, boolean enforcing) {
        if (write == null)
            return ClientSubmitGuard.UNCHECKABLE_REFUSED;
        Boolean verifiedBackoffice = StateAccessor.getVerifiedBackoffice(state);
        String refusal = refusalFor(write.entityName(), verifiedBackoffice, true);
        if (refusal == null)
            return null;
        // The principal and the audience are logged because this line exists to be acted on: it is the census.
        // Account identities, not personal data about a data subject.
        Console.log("🛡 " + (enforcing ? "REFUSED" : "WOULD REFUSE") + " " + write.verb() + " on " + write.entityName()
                    + " by " + StateAccessor.getUserId(state)
                    + " (" + (verifiedBackoffice == null ? "no verified token" : "a front-office token")
                    + " — booking tables are written by the back office only)");
        return enforcing ? refusal : null;
    }

    /**
     * The decision itself, apart from the thread and the switch: the refusal this write earns, or null when it may
     * run. {@code enforcing} is passed in rather than read, so the post-flip behaviour is checkable while the switch
     * is off.
     */
    static String refusalFor(String entityName, Boolean verifiedBackoffice, boolean enforcing) {
        if (!BOOKING_ENTITIES.contains(entityName))
            return null;
        if (Boolean.TRUE.equals(verifiedBackoffice))
            return null;
        return enforcing ? REFUSED : null;
    }
}

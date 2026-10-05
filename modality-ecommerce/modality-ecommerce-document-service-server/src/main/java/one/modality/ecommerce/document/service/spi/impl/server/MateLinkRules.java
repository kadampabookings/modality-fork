package one.modality.ecommerce.document.service.spi.impl.server;

import java.util.Objects;

/**
 * The rules a {@code LinkMateToOwnerDocumentLineEvent} must pass before the server writes it
 * (docs/room-mate-booking-plan.md — aggregate repo).
 *
 * <p>Until now the server replayed a link exactly as sent: whichever owner line the client named was
 * written to {@code share_mate_owner_document_line_id}, and the {@code on_share_linked_copy_info}
 * trigger then copied that line's room onto the mate. Any client could link any booking line to any
 * other — across events, onto a line that is not a room booking, or onto the mate's own booking.
 *
 * <p>Pure on purpose — no WebFX or database types — so every rule is checkable by
 * {@code MateLinkRulesCheck} without a running server. {@link ServerDocumentServiceProvider} loads the
 * facts; this class only judges them. A refusal is a message; {@code null} means the link may be written.
 *
 * <p><b>Known limit, for plan step 5:</b> the facts are read from the database, so a mate line INSERTED
 * in the same submit does not exist yet and is refused. That is right for the back office, which only
 * links existing bookings; a front-office flow that books and links in one submit needs these rules
 * fed from the submission itself.
 */
final class MateLinkRules {

    /** Prefix on every refusal, so clients and logs can recognise a refused link. */
    static final String ERROR_PREFIX = "[MateLinkError] ";

    /**
     * What the server knows about one booking line, as loaded from the database.
     *
     * @param shareMate  the line's own share_mate flag OR its item's — after a link the trigger copies
     *                   the booker's item onto the mate, so only the line's flag survives a re-link
     * @param shareOwner the line books a room that others can share (share_owner)
     * @param documentId the booking the line belongs to
     * @param eventId    that booking's event
     * @param ownedBySubmitter whether the submitting account may act on this line's booking, by
     *                   {@code accountCanAccessPersonOrders} — the predicate the rest of the system uses,
     *                   which reaches a household member through {@code person.accountPerson} as well as
     *                   through {@code person.frontendAccount}. A booking made FOR a member of your
     *                   account has no frontendAccount of its own, and that is the commonest shape of the
     *                   very flow this rule exists for. Loaded by the caller, judged here. Only the
     *                   front-office rule reads it.
     */
    record LineFacts(boolean shareMate, boolean shareOwner, Object documentId, Object eventId, boolean ownedBySubmitter) { }

    private MateLinkRules() { }

    /**
     * Judges one link.
     *
     * @param backofficeSession the session says it is the back-office app — CLIENT-ASSERTED (the session
     *                          syncer copies it from the client's state), so it states intent and is
     *                          never the security check on its own
     * @param backofficeAccount the submitting account's FrontendAccount.backoffice flag, read from the
     *                          database; null when the submitter has no account (guest or public)
     * @param mate              the line being linked, or null when it does not exist
     * @param ownerLineNamed    whether the event names an owner booking line
     * @param owner             that owner line, or null when it does not exist or none was named
     * @param ownerPersonNamed  whether the event names an owner person — the "booker has not booked yet" form
     * @param submitterAccountId the account submitting, or null for a guest — a guest owns nothing, so the
     *                          front-office rule refuses before it looks at anything else
     * @return a refusal message, or null when the link may be written
     */
    static String check(boolean backofficeSession, Boolean backofficeAccount,
                        LineFacts mate, boolean ownerLineNamed, LineFacts owner, boolean ownerPersonNamed,
                        Object submitterAccountId) {
        // The account flag is set server-side and guarded by the write-authorization seam
        // (ManageBackofficeAccess); the session flag is whatever the client sent. Requiring both means a
        // verified back-office account, working in the back-office app.
        boolean backoffice = backofficeSession && Boolean.TRUE.equals(backofficeAccount);
        // The front office gets its OWN rule rather than a borrowed one (room-mate plan Part C): a booker
        // may link a mate into a room, but only into a room their own account booked.
        if (!backoffice)
            return checkBookerLink(mate, ownerLineNamed, owner, ownerPersonNamed, submitterAccountId);
        if (!ownerLineNamed && !ownerPersonNamed)
            return ERROR_PREFIX + "A link must name the room booker";
        if (mate == null)
            return ERROR_PREFIX + "The roommate's booking line does not exist";
        if (!mate.shareMate())
            return ERROR_PREFIX + "Only a sharing booking can be linked to a room booker";
        if (ownerLineNamed) {
            if (owner == null)
                return ERROR_PREFIX + "The room booker's booking line does not exist";
            if (!owner.shareOwner())
                return ERROR_PREFIX + "The chosen line is not a room booking that can be shared";
            if (!sameId(mate.eventId(), owner.eventId()))
                return ERROR_PREFIX + "The room booker's booking belongs to another event";
            if (sameId(mate.documentId(), owner.documentId()))
                return ERROR_PREFIX + "A booking cannot be linked to itself";
        }
        return null;
    }

    /**
     * Whether a submit may say that a line IS a sharing place.
     *
     * <p>The flag is the price. {@code EditShareMateInfoDocumentLineEvent} writes
     * {@code document_line.share_mate}, and that flag alone makes
     * {@code compute_document_line_pricing_quantity} price the line at <b>0</b> (V0039, V0117) and keeps
     * the defer-allocate trigger off it (V0118). So the question "may this line be a sharing place" is
     * the question "may this line cost nothing", and it cannot be answered by the client that benefits.
     *
     * <p><b>A line may only be BORN a sharing place.</b> Created as one, it faces
     * {@code refuseSharingPlaceWithoutFreeBed}: the item must be one the event offers as a sharing
     * option and a bed must be free for it, or a token or the booker's own room must stand in for the
     * count. Acquiring the flag afterwards faced nothing — that guard returns before any check when a
     * submit adds no line — so a booker could pay for a whole room and then, in a second submit carrying
     * this event alone, reprice their own line to nothing while the pool absorbed the bed.
     *
     * <p><b>Why the availability check is not the fix here.</b> Since virtual sharing options (Part B) a
     * sharing line names the ROOM's own item, so that check would find the attacker's twin genuinely
     * "offered as a sharing option" and then ask only whether the event has a free shared bed anywhere —
     * which it usually does. Routing acquisition through it would have let the attack through on most
     * events. What separates the two is not the item or the bed but the EDIT: creating a sharing line
     * takes a bed, converting a paid line keeps the room's money and takes a bed as well.
     *
     * <p>An already-flagged line passes. No current flow needs that: both front-office emitters take the
     * line id from the {@code AddDocumentLineEvent} in the same submit and send nothing when there is
     * none ({@code bookAccommodationOption}), so today every legitimate share-mate event names a line
     * being created. The branch is there so that a flow which later re-sends the event on an unchanged
     * line meets a no-op rather than a refusal, and it cannot be abused: saying a line is what it already
     * is changes neither its price nor its allocation. What is refused is the flag being ACQUIRED.
     *
     * @param backofficeSession the client-asserted session flag
     * @param backofficeAccount the account's own flag from the database; null when the submitter has none
     * @param createdByThisSubmit the submit carries an AddDocumentLineEvent for this line, so the line is
     *                          being born here and the availability guard has judged it
     * @param alreadySharing    the line's {@code share_mate} as the database holds it, or null when no
     *                          such line exists
     * @return a refusal message, or null when the flag may be written
     */
    static String checkShareMateFlag(boolean backofficeSession, Boolean backofficeAccount,
                                     boolean createdByThisSubmit, Boolean alreadySharing) {
        // Both flags, as check() above combines them and for the same reason: a verified back-office
        // account, working in the back-office app. The back office is authoritative for placement here as
        // it is for the allocation check itself.
        if (backofficeSession && Boolean.TRUE.equals(backofficeAccount))
            return null;
        if (createdByThisSubmit || Boolean.TRUE.equals(alreadySharing))
            return null;
        // One message for "no such line" and for "that line is not a sharing place", as checkBookerLink
        // gives one for all its failures: line ids are sequential and shown in the UI, so a caller able to
        // tell the two apart could walk the id space and learn which bookings hold a sharing line.
        return ERROR_PREFIX + "This booking line cannot become a sharing place";
    }

    /**
     * A booker linking their own roommate, from the front office (room-mate plan Part C).
     *
     * <p>The ownership check is the whole rule, not defence in depth. The booker's submit names the owner
     * LINE ID where an invited mate presents an unguessable token, and Part C switches the bed count off
     * for this path deliberately — so without ownership a forged id would be GUARANTEED to succeed rather
     * than merely likely, attaching a mate to a stranger's room. Line ids are sequential and shown in the
     * UI, so guessing one is not a thought experiment.
     *
     * <p><b>BOTH lines must be the submitter's, and the mate side matters as much as the room side.</b>
     * An earlier draft of this checked only the room, which let an account that owned any room link a
     * STRANGER's sharing line into it — and because the link trigger deletes the mate's attendances and
     * replaces them with the room's, that destroys the stranger's stay and moves them into the attacker's
     * room. Worse, before this rule existed the back-office branch refused every front-office link
     * outright, so judging them here opened the event to the front office for the first time; a rule that
     * only half-checks is then strictly worse than the refusal it replaced.
     *
     * <p><b>One refusal message for every failure here</b>, unlike the back-office branch above. A caller
     * who can tell "no such line" from "not your line" from "not a shareable room" can walk the id space
     * and learn which rooms exist and which are shared — the success of a link already discloses that, so
     * the failures must not. The back office is told which rule it broke because it is already trusted
     * with the data the message reveals.
     *
     * <p>A guest has no account to own anything, so a guest cannot link; the durable channels carry that
     * case, as they do for minting (see "Still open" in the plan).
     */
    private static String checkBookerLink(LineFacts mate, boolean ownerLineNamed, LineFacts owner,
                                          boolean ownerPersonNamed, Object submitterAccountId) {
        String refusal = ERROR_PREFIX + "You can only add a roommate to a room you booked";
        // Naming a PERSON rather than a line is the back office's "booker has not booked yet" form: it
        // resolves to no room, so there is nothing to own and nothing for this rule to check.
        if (!ownerLineNamed || ownerPersonNamed)
            return refusal;
        if (submitterAccountId == null)
            return refusal; // a guest owns nothing
        if (mate == null || !mate.shareMate() || !mate.ownedBySubmitter())
            return refusal;
        if (owner == null || !owner.shareOwner() || !owner.ownedBySubmitter())
            return refusal;
        if (!sameId(mate.eventId(), owner.eventId()))
            return refusal;
        if (sameId(mate.documentId(), owner.documentId()))
            return refusal;
        return null;
    }

    /**
     * Primary keys compare by value: the database layer may hand back an Integer on one side and a Long
     * on the other. A null never matches anything, not even another null — an unknown event is not "the
     * same event".
     */
    static boolean sameId(Object a, Object b) {
        if (a == null || b == null)
            return false;
        if (a instanceof Number na && b instanceof Number nb)
            return na.longValue() == nb.longValue();
        return Objects.equals(a.toString(), b.toString());
    }
}

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

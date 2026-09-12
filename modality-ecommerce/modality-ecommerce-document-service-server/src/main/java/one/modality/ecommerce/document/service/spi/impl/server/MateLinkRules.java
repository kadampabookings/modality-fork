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
     */
    record LineFacts(boolean shareMate, boolean shareOwner, Object documentId, Object eventId) { }

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
     * @return a refusal message, or null when the link may be written
     */
    static String check(boolean backofficeSession, Boolean backofficeAccount,
                        LineFacts mate, boolean ownerLineNamed, LineFacts owner, boolean ownerPersonNamed) {
        // Security first. The account flag is set server-side and guarded by the write-authorization seam
        // (ManageBackofficeAccess); the session flag is whatever the client sent. Requiring both means a
        // verified back-office account, working in the back-office app. The front office gets its own rule
        // when it gains a link path (plan steps 4-5) — not a borrowed one.
        if (!backofficeSession || !Boolean.TRUE.equals(backofficeAccount))
            return ERROR_PREFIX + "Linking a roommate to a room booker is a back-office action";
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

package one.modality.crm.server.person;

import dev.webfx.stack.orm.entity.Entities;
import one.modality.base.shared.entities.Document;
import one.modality.base.shared.entities.Event;
import one.modality.base.shared.entities.Letter;

/**
 * Whether a letter may be sent by hand for a booking: the rule the back office's letter picker
 * offers by, enforced again where the send happens.
 *
 * <p><b>Why the server checks it too.</b> {@link DocumentLetterRules} checks that the caller may send
 * letters for the BOOKING; without this, the LETTER would be whatever id the call names — another
 * organization's letter, an inactive one, a suppression, or one written for online attendees sent to
 * an in-person booker. The database composes whatever letter it is handed, so the choice has to be
 * fenced here, not only in the picker.
 *
 * <p><b>The rule</b> is KBS2's {@code Mail.letter} selector (system.script, FIELD 385), with the
 * venue resolved as the database resolves it ({@code event_letter_venue_id()}, V0136):
 * <ul>
 *   <li>active, and not a suppression letter (V0043);</li>
 *   <li>not a staff alert (V0160): those go to the organization's own sending account, not the booker,
 *       and are sent by the system trigger only — never picked by hand;</li>
 *   <li>in the booking's scope — the event's own letter; or an organization letter of the event's
 *       organization whose event type and site, when set, are the event's; or a global event-type
 *       letter (no organization, V0052) of the event's type;</li>
 *   <li>applicable to the booking's attendance mode ({@code applicableToInPerson} /
 *       {@code applicableToOnline}); an unset flag counts as applicable, as in the React picker.</li>
 * </ul>
 *
 * <p>Pure, so {@code LetterApplicabilityCheck} pins it without a database.
 *
 * @author Claude Code
 */
final class LetterApplicability {

    /** The letter fields {@link #isSendable} reads — load them all, or it answers on missing values. */
    static final String LETTER_FIELDS =
        "active,suppressesSending,applicableToInPerson,applicableToOnline,event,organization,eventType,site,type.staffAlert";

    /** The booking fields {@link #isSendable} reads: its mode and its event's scope context. */
    static final String DOCUMENT_FIELDS =
        "inPerson,event.organization,event.type,event.venue,event.repeatedEvent.venue";

    private LetterApplicability() {
    }

    /**
     * @param letter   the letter, loaded with {@link #LETTER_FIELDS}
     * @param document the booking, loaded with {@link #DOCUMENT_FIELDS}
     * @return true when the letter may be sent for this booking
     */
    static boolean isSendable(Letter letter, Document document) {
        Event event = document.getEvent();
        if (letter == null || event == null)
            return false;
        Event repeated = event.getRepeatedEvent();
        Object venue = event.getVenueId() != null ? event.getVenueId() : repeated == null ? null : repeated.getVenueId();
        if (letter.getType() == null || !Boolean.FALSE.equals(letter.getType().getBooleanFieldValue("staffAlert")))
            return false; // a staff alert, or a type not loaded: never sent by hand
        return isSendable(
            letter.getBooleanFieldValue("active"), letter.isSuppressesSending(),
            letter.getBooleanFieldValue("applicableToInPerson"), letter.getBooleanFieldValue("applicableToOnline"),
            key(letter.getEventId()), key(letter.getOrganizationId()), key(letter.getEventTypeId()), key(letter.getSiteId()),
            key(event.getId()), key(event.getOrganizationId()), key(event.getTypeId()), key(venue),
            !Boolean.FALSE.equals(document.isInPerson()));
    }

    /**
     * The rule on plain values: every id is a primary key ({@link #key}) or null.
     */
    static boolean isSendable(Boolean active, Boolean suppressesSending,
                              Boolean applicableToInPerson, Boolean applicableToOnline,
                              Object letterEvent, Object letterOrganization, Object letterEventType, Object letterSite,
                              Object event, Object eventOrganization, Object eventType, Object eventVenue,
                              boolean inPerson) {
        if (!Boolean.TRUE.equals(active) || Boolean.TRUE.equals(suppressesSending))
            return false;
        boolean servesMode = inPerson ? !Boolean.FALSE.equals(applicableToInPerson) : !Boolean.FALSE.equals(applicableToOnline);
        if (!servesMode)
            return false;
        if (letterEvent != null)
            return same(letterEvent, event);
        if (letterOrganization != null)
            return same(letterOrganization, eventOrganization)
                   && (letterEventType == null || same(letterEventType, eventType))
                   && (letterSite == null || same(letterSite, eventVenue));
        // Global event-type letter (V0052): no organization, matched on the type alone. A letter with
        // neither event, organization nor type has no scope, and is never sendable.
        return letterEventType != null && same(letterEventType, eventType);
    }

    /** Two ids naming the same row; a null never matches, not even another null. */
    private static boolean same(Object a, Object b) {
        return a != null && b != null && a.equals(b);
    }

    /**
     * A primary key in one comparable form: an EntityId's key, with numbers widened to long, so an
     * Integer read from one query equals a Long read from another.
     */
    static Object key(Object id) {
        Object pk = id == null ? null : Entities.getPrimaryKey(id);
        return pk instanceof Number n ? (Object) n.longValue() : pk;
    }
}

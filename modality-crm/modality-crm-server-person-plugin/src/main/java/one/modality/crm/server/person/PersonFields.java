package one.modality.crm.server.person;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fields of a person a member may set, and nothing else.
 *
 * <p><b>This list IS the permission.</b> The screens used to send a map of whatever they had and the
 * change set turned it into {@code update Person set …} — so what a client could write was decided by the
 * client. The plan's Person notes ask for the field allowlist to be the method signature rather than a
 * runtime filter; this is that signature, expressed once so both the update and the insert are held to
 * the same list.
 *
 * <h3>What is NOT here, and why</h3>
 *
 * <ul>
 *   <li>{@code frontendAccount}, {@code accountPerson}, {@code accountPersonRevokedDate}, {@code owner} —
 *       these are ACCOUNT STRUCTURE, not personal details. Writing them is linking, unlinking and
 *       account takeover; they have their own operations, with their own proofs.</li>
 *   <li>{@code abcNames}, {@code neverBooked} and the deprecation dates — derived or staff-set, and
 *       nothing a member's screen offers.</li>
 *   <li>{@code genderChangedDate} — a marker staff read when allocating dormitories. A member changing
 *       their own record must not be able to clear it.</li>
 * </ul>
 *
 * <p>{@code email} IS here, and is the one that needs saying twice: it reaches
 * {@code frontend_account.username} through a trigger when the person is an account owner, so setting it
 * on the wrong row is a sign-in change. The statement that writes it says {@code owner = false}, which is
 * the same rule {@code OwnerLoginWritePolicy} applies to client writes — an owner's address changes only
 * through the flow that verifies it.
 *
 * @author Claude Code
 */
final class PersonFields {

    private PersonFields() {
    }

    /** How a value reaches its column: plain, or with the cast the driver cannot infer. */
    enum Kind {
        TEXT(""), BOOLEAN(""), NUMBER(""), INTEGER(""), DATE("::date");

        final String cast;

        Kind(String cast) {
            this.cast = cast;
        }
    }

    /**
     * @param maxLength the column's own width for a varchar, or 0 where length is not a constraint.
     *   Checked here so an over-long value is refused by name rather than surfacing as Postgres text
     *   in a browser — the same reasoning as {@code InvitationRules}' alias check.
     */
    record Field(String column, Kind kind, int maxLength) {
        Field(String column, Kind kind) {
            this(column, kind, 0);
        }
    }

    /** Keyed by the name a client sends, which is the entity's field name, not the column's. */
    static final Map<String, Field> EDITABLE = editable();

    private static Map<String, Field> editable() {
        Map<String, Field> m = new LinkedHashMap<>();
        m.put("firstName", new Field("first_name", Kind.TEXT, 45));
        m.put("lastName", new Field("last_name", Kind.TEXT, 45));
        m.put("layName", new Field("lay_name", Kind.TEXT, 91));
        m.put("male", new Field("male", Kind.BOOLEAN));
        m.put("ordained", new Field("ordained", Kind.BOOLEAN));
        m.put("birthdate", new Field("birthdate", Kind.DATE));
        m.put("phone", new Field("phone", Kind.TEXT, 45));
        m.put("street", new Field("street", Kind.TEXT, 128));
        m.put("postCode", new Field("post_code", Kind.TEXT, 16));
        m.put("cityName", new Field("city_name", Kind.TEXT, 64));
        m.put("cityLatitude", new Field("city_latitude", Kind.NUMBER));
        m.put("cityLongitude", new Field("city_longitude", Kind.NUMBER));
        m.put("country", new Field("country_id", Kind.INTEGER));
        m.put("organization", new Field("organization_id", Kind.INTEGER));
        // Whether this person is still one of the account's members, and whether they are kept between
        // bookings. Both are a member's to decide about their own account's people.
        m.put("removed", new Field("removed", Kind.BOOLEAN));
        m.put("occasional", new Field("occasional", Kind.BOOLEAN));
        // The booking flow's freshness markers. A booker CLEARS a deprecation marker by supplying a
        // fresh value, and stamps detailsConfirmedDate when they confirm — see useMemberDetailsReview.
        // Left out, the review step would be refused outright and a blocking marker could never be
        // cleared, which stops the booking rather than the edit.
        m.put("detailsConfirmedDate", new Field("details_confirmed_date", Kind.DATE));
        m.put("addressDeprecatedDate", new Field("address_deprecated_date", Kind.DATE));
        m.put("organizationDeprecatedDate", new Field("organization_deprecated_date", Kind.DATE));
        return m;
    }

    /** Only writable where the row is not an account owner — see the class note. */
    static final String EMAIL = "email";

    /** Every name a client may send, {@link #EMAIL} included. */
    static boolean isEditable(String name) {
        return EDITABLE.containsKey(name) || EMAIL.equals(name);
    }

    static Field fieldFor(String name) {
        return EMAIL.equals(name) ? new Field("email", Kind.TEXT, 127) : EDITABLE.get(name);
    }
}

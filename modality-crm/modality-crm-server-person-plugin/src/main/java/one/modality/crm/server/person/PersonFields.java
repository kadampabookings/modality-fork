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

    /**
     * The markers whose value the SERVER decides — the client only says null or not-null.
     *
     * <p>{@code detailsConfirmedDate} buys a year without being asked to review again, and a client that
     * could choose the date could write {@code 2999-01-01} and never be asked again. The deprecation
     * markers are the mirror of it: a future date reads as "not deprecated yet" to any rule that
     * compares, so setting one is as good as clearing it.
     *
     * <p>None of them is a value a member types — the booking flow sends today, or null to clear. So the
     * field stays editable and only the VALUE stops being the client's: a non-null becomes
     * {@code current_date}, a null stays null. That also settles the clock question the obvious fix
     * raises, since "is this today?" asked in Java has to pick a timezone and would refuse a member
     * whose own date is legitimately a day ahead of the server's.
     *
     * @see #valueExpression
     */
    private static final java.util.Set<String> SERVER_STAMPED = java.util.Set.of(
        "detailsConfirmedDate", "addressDeprecatedDate", "organizationDeprecatedDate",
        // Staff-only, and the same reasoning: the back office says clear-it or mark-it, never when.
        "genderChangedDate");

    /**
     * How one field's value reaches its column, given its placeholder number.
     *
     * <p>Here rather than in the statement builders because there are three of them — the update, the
     * insert, and the mirror a check reads — and a rule about values that lived in one of them would be
     * a rule the other two did not have.
     */
    static String valueExpression(String name, int placeholder) {
        String parameter = "$" + placeholder + fieldFor(name).kind().cast;
        return SERVER_STAMPED.contains(name)
            ? "case when " + parameter + " is null then null else current_date end"
            : parameter;
    }

    /** Only writable where the row is not an account owner — see the class note. */
    static final String EMAIL = "email";

    /**
     * What a member of STAFF may set on top of that, and nobody else.
     *
     * <p>Kept as a separate map rather than folded in, so the difference between "a member editing their
     * own record" and "the back office editing anybody's" is a thing you can read rather than infer. Each
     * of these is here because a back-office screen writes it today:
     *
     * <ul>
     *   <li>{@code nationality} and {@code passport} — the customer detail drawer. Not on any member
     *       screen, and not data a member is asked for.</li>
     *   <li>{@code resident} and the {@code resident*}/{@code sponsored} fields — the residents
     *       screens, which is what they are for.</li>
     *   <li>{@code genderChangedDate} — the marker staff read when allocating dormitories. A member must
     *       not be able to clear it, which is why it is excluded above; staff dismissing it IS the
     *       supported flow (the V0035 trigger stamps it, and a null write sticks).</li>
     * </ul>
     *
     * <p>Everything a member may set, staff may set too, so the staff list is a superset. The reverse is
     * never true: a name only in this map is refused on the member endpoints exactly as before.
     */
    private static final Map<String, Field> STAFF_ONLY = staffOnly();

    private static Map<String, Field> staffOnly() {
        Map<String, Field> m = new LinkedHashMap<>();
        m.put("nationality", new Field("nationality", Kind.TEXT, 64));
        m.put("passport", new Field("passport", Kind.TEXT, 64));
        m.put("resident", new Field("resident", Kind.BOOLEAN));
        // The residents screens' own fields: what a resident is booked in for, whether their stay is
        // sponsored, and the room and rent. All of them are a centre's business about its own residents.
        m.put("residentBooksBreakfast", new Field("resident_books_breakfast", Kind.BOOLEAN));
        m.put("residentBooksLunch", new Field("resident_books_lunch", Kind.BOOLEAN));
        m.put("residentBooksDinner", new Field("resident_books_dinner", Kind.BOOLEAN));
        m.put("sponsored", new Field("sponsored", Kind.BOOLEAN));
        m.put("residentRoom", new Field("resident_room_id", Kind.INTEGER));
        m.put("residentRoomMonthlyRent", new Field("resident_room_monthly_rent", Kind.INTEGER));
        // Stamped like the other markers: staff say "clear it" (null) or "mark it" (any value, written
        // as current_date). See SERVER_STAMPED — the value is not the caller's here either.
        m.put("genderChangedDate", new Field("gender_changed_date", Kind.DATE));
        return m;
    }

    /** Every name a client may send, {@link #EMAIL} included. */
    static boolean isEditable(String name) {
        return EDITABLE.containsKey(name) || EMAIL.equals(name);
    }

    /**
     * Whether this name resolves to a column staff may write SOMEWHERE — the member list plus
     * {@link #STAFF_ONLY}.
     *
     * <p><b>Not an authorization answer, and no longer used as one.</b> Each back-office route carries
     * its own list ({@code StaffPersonRules.CUSTOMER_FIELDS}, {@code USER_FIELDS},
     * {@code RESIDENT_FIELDS}), because a shared superset let {@code /customers} write the residency
     * columns that {@code /residents} is scoped for. This remains as the question a check asks: is
     * this field known to staff at all, and still refused to members?
     */
    static boolean isStaffEditable(String name) {
        return isEditable(name) || STAFF_ONLY.containsKey(name);
    }

    static Field fieldFor(String name) {
        if (EMAIL.equals(name))
            return new Field("email", Kind.TEXT, 127);
        Field field = EDITABLE.get(name);
        return field != null ? field : STAFF_ONLY.get(name);
    }
}

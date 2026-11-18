package one.modality.crm.server.person;

import java.util.ArrayList;
import java.util.List;

/**
 * Check for the two things that decide what a member may change about a person: which fields, and whose
 * row.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, like the other checks here.
 *
 * <p>Both are properties of generated SQL, which is exactly why they are asserted rather than trusted.
 * The statement is built per call from the caller's field list, so an edit to the builder that dropped
 * the ownership clause would produce a statement that still runs, still reports success, and updates
 * anybody's row. Nothing else in the system would notice.
 */
public class PersonDetailsCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static List<String> names(String... n) {
        return List.of(n);
    }

    public static void main(String[] args) {
        System.out.println("PersonDetailsCheck");

        // --- whose row: the test travels with every write ---
        String profile = PersonDetailsRules.updateStatementFor(names("firstName", "lastName", "phone"));
        check("an update names the row", profile.contains(" where id = $4"));
        check("an update is limited to the caller's own person or account",
            profile.contains("id = $5 or frontend_account_id = $6"));
        check("an update never touches a removed row", profile.contains("removed = false"));

        // --- the email rule, which is a sign-in rule wearing a profile field's clothes ---
        String withEmail = PersonDetailsRules.updateStatementFor(names("firstName", "email"));
        check("writing email refuses an account owner", withEmail.contains("and owner = false"));
        check("not writing email leaves other fields unrestricted by owner", !profile.contains("owner = false"));

        // --- which fields ---
        for (String forbidden : new String[] { "frontendAccount", "accountPerson", "owner",
                                               "accountPersonRevokedDate", "genderChangedDate", "abcNames" })
            check(forbidden + " is not editable", !PersonFields.isEditable(forbidden));
        for (String allowed : new String[] { "firstName", "country", "removed", "occasional", "email" })
            check(allowed + " is editable", PersonFields.isEditable(allowed));

        // --- a name this build does not know is REFUSED, not dropped ---
        List<String> got = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        check("an unknown field is reported rather than ignored",
            "owner".equals(PersonDetailsRules.collect(new Object[] { "firstName", "A", "owner", true }, got, values)));
        got.clear(); values.clear();
        check("the same field twice is reported", 
            "firstName".equals(PersonDetailsRules.collect(new Object[] { "firstName", "A", "firstName", "B" }, got, values)));
        got.clear(); values.clear();
        check("a known field list is accepted whole",
            PersonDetailsRules.collect(new Object[] { "firstName", "A", "phone", "1" }, got, values) == null
                && got.size() == 2 && values.size() == 2);

        // --- the minor flag that replaced the date of birth: a member's to set on their own account's people ---
        check("minor is editable", PersonFields.isEditable("minor"));
        check("minor is a plain boolean column, the client's own value",
            PersonFields.fieldFor("minor").kind() == PersonFields.Kind.BOOLEAN
            && "minor".equals(PersonFields.fieldFor("minor").column())
            && "$1".equals(PersonFields.valueExpression("minor", 1))
            && Boolean.TRUE.equals(PersonFields.boundValue("minor", true)));
        // Same ownership rule as every other field: the flag is no reason to reach somebody else's row.
        String minorEdit = PersonDetailsRules.updateStatementFor(names("minor"));
        check("writing minor is held to the caller's own person or account",
            minorEdit.contains("minor = $1") && minorEdit.contains("id = $3 or frontend_account_id = $4")
            && minorEdit.contains("removed = false"));
        check("and it is not an owner-guarded field like email",
            !minorEdit.contains("owner = false"));
        got.clear(); values.clear();
        check("a booker adding a member may send it",
            PersonDetailsRules.collect(new Object[] { "firstName", "A", "minor", true }, got, values) == null
                && got.equals(List.of("firstName", "minor")));
        got.clear(); values.clear();
        check("and so may an account owner at sign-up",
            AccountOwnerRules.rejectionFor(new Object[] { "firstName", "A", "minor", false }, got, values) == null);
        got.clear(); values.clear();
        // V0171: accounts are for adults. The form blocks a No itself; the server refuses a client that sends true.
        check("sign-up refuses a person who says they are under 18",
            AccountOwnerRules.MINOR_KEY.equals(AccountOwnerRules.rejectionFor(
                new Object[] { "firstName", "A", "minor", true }, got, values)));
        check("with a sentence the sign-up screen can show",
            AccountOwnerRules.sentenceFor(AccountOwnerRules.MINOR_KEY).startsWith("Accounts are for adults"));
        got.clear(); values.clear();
        // An older cached front office does not send it: still accepted, and the owner trigger writes false.
        check("a sign-up without minor is still accepted",
            AccountOwnerRules.rejectionFor(new Object[] { "firstName", "A" }, got, values) == null);
        got.clear(); values.clear();
        // Three-state since V0171: the column allows null, meaning "unknown", and only the database writes it
        // (the migration's fill). Screens send true or false; a client null would erase an answer, so it stays
        // refused here rather than reaching the column.
        check("a null minor is refused by name on the member paths",
            "minor".equals(PersonDetailsRules.firstNotABoolean(names("minor"), java.util.Collections.singletonList(null))));
        check("and so is a string standing in for one",
            "minor".equals(PersonDetailsRules.firstNotABoolean(names("minor"), List.of("true"))));
        check("a real boolean passes", PersonDetailsRules.firstNotABoolean(names("minor"), List.of(false)) == null);
        check("male may still be null — its column allows it, and null means not given",
            PersonDetailsRules.firstNotABoolean(names("male"), java.util.Collections.singletonList(null)) == null);
        check("sign-up refuses a null minor with the same key (malformed, not a child)",
            PersonDetailsRules.NOT_A_BOOLEAN_KEY.equals(AccountOwnerRules.rejectionFor(
                new Object[] { "firstName", "A", "minor", null }, got, values)));
        got.clear(); values.clear();
        String detailsSource = AccountOwnerCheck.readSource("modality-fork/modality-crm/modality-crm-server-person-plugin/"
            + "src/main/java/one/modality/crm/server/person/PersonDetailsRules.java");
        check("the update and the add-member paths both ask it",
            detailsSource.split(java.util.regex.Pattern.quote("firstNotABoolean(names, values) != null"), -1).length == 3);

        // --- a RETIRED field is the exception: accepted and dropped, never written, never refused ---
        // Date of birth is no longer collected, but stale front-office bundles still send it back on
        // every profile save. Refused, those saves would fail; written, the value we stopped keeping
        // would be kept.
        check("birthdate is no longer editable", !PersonFields.isEditable("birthdate"));
        check("birthdate resolves to no column, so no statement can write it",
            PersonFields.fieldFor("birthdate") == null && !PersonFields.isStaffEditable("birthdate"));
        got.clear(); values.clear();
        check("birthdate sent beside other fields is dropped, and the rest kept",
            PersonDetailsRules.collect(new Object[] { "firstName", "A", "birthdate", "1990-01-31", "phone", "1" },
                got, values) == null
                && got.equals(List.of("firstName", "phone")) && values.equals(List.of("A", "1")));
        got.clear(); values.clear();
        Object[] onlyBirthdate = { "birthdate", "1990-01-31" };
        check("birthdate sent alone is accepted, with nothing to write",
            PersonDetailsRules.collect(onlyBirthdate, got, values) == null && got.isEmpty()
                && PersonFields.onlyRetired(onlyBirthdate, got));
        check("and an empty request is still not mistaken for one",
            !PersonFields.onlyRetired(new Object[0], List.of()) && !PersonFields.onlyRetired(null, List.of()));
        got.clear(); values.clear();
        check("a malformed birthdate is dropped too, not refused as a date",
            PersonDetailsRules.collect(new Object[] { "birthdate", "31/01/1990" }, got, values) == null
                && PersonDetailsRules.firstNotADate(got, values) == null);
        got.clear(); values.clear();
        check("sign-up drops it the same way, through the same collector",
            AccountOwnerRules.rejectionFor(new Object[] { "firstName", "A", "birthdate", "1990-01-31" },
                got, values) == null && got.equals(List.of("firstName")));

        // --- a date reaches a date column ---
        // No member date is client-valued any more (every remaining one is server-stamped), so the cast
        // is asserted on the kind that a future ordinary date field would take.
        check("a date field carries its cast", "::date".equals(PersonFields.Kind.DATE.cast));
        check("a text field carries no cast",
            PersonDetailsRules.updateStatementFor(names("phone")).contains("phone = $1 "));

        // --- the statement answers for itself ---
        check("an update returns its row, so a miss is not reported as a save",
            profile.endsWith(" returning id"));

        // --- removing is not a profile edit on the row a sign-in resolves to ---
        String removing = PersonDetailsRules.updateStatementFor(names("removed"));
        check("removing refuses an account owner", removing.contains("and owner = false"));

        // --- the booking flow's markers, without which the review step is refused outright ---
        for (String marker : new String[] { "detailsConfirmedDate", "addressDeprecatedDate", "organizationDeprecatedDate" }) {
            check(marker + " is editable", PersonFields.isEditable(marker));
            // The field is the client's to name; the VALUE is not. A client free to choose the date could
            // write detailsConfirmedDate=2999-01-01 and never be asked to review again, or future-date a
            // deprecation, which reads as "not deprecated yet" to anything that compares.
            String stamped = PersonDetailsRules.updateStatementFor(names(marker));
            // The value bound is a BOOLEAN — "was one sent?" — which is the only thing the expression
            // asks. It used to bind the date and ignore it, and the `::date` cast then made the driver
            // refuse a caller that sent the wire's string form. A parameter whose value is never read
            // must not have a type that can be wrong, so `$1::date` must not appear for a marker.
            check(marker + " is stamped by the server, not by the client",
                stamped.contains("then current_date") && !stamped.contains("$1::date"));
            check(marker + " can still be cleared",
                stamped.contains("case when $1 then current_date else null end"));
            check(marker + " binds a boolean, whatever the caller sent",
                Boolean.TRUE.equals(PersonFields.boundValue(marker, "2026-09-22"))
                && Boolean.TRUE.equals(PersonFields.boundValue(marker, java.time.LocalDate.now()))
                && Boolean.FALSE.equals(PersonFields.boundValue(marker, null)));
        }
        // --- the half that actually broke: the BINDING, not the SQL ---
        // The production failure was correct SQL with the wrong value bound. Every assertion above
        // reads the generated statement, and every one of them stayed green while the binding was
        // wrong. So these read the builders' own source: revert any of them to binding raw values and
        // the suite fails here rather than in production.
        String base = "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
                      + "one/modality/crm/server/person/";
        String details = AccountOwnerCheck.readSource(base + "PersonDetailsRules.java");
        String staff = AccountOwnerCheck.readSource(base + "StaffPersonRules.java");
        String owner = AccountOwnerCheck.readSource(base + "AccountOwnerRules.java");
        check("the three rules sources were found",
            !details.isEmpty() && !staff.isEmpty() && !owner.isEmpty());
        // A request of nothing but retired fields is a save, on the member update and both staff
        // updates; without the call it would come back as "no fields" to a stale form.
        String onlyRetiredCall = "PersonFields.onlyRetired(namesAndValues, names)";
        check("the update paths answer a retired-only request as a save",
            details.contains(onlyRetiredCall) && staff.split(java.util.regex.Pattern.quote(onlyRetiredCall), -1).length == 3);
        check("the update and the insert bind through boundValue",
            details.contains("PersonFields.boundValue(names.get(i), values.get(i))"));
        check("the staff paths bind through boundValues",
            staff.contains("PersonFields.boundValues(names, values)"));
        check("the owner insert binds through boundValues, positionally",
            owner.contains("parameters.addAll(PersonFields.boundValues(names, values))"));
        check("no builder binds a raw value any more",
            !details.contains("parameters.add(values.get(i));")
            && !staff.contains("new ArrayList<>(values)")
            && !owner.contains("values.get(names.indexOf(name))"));

        // A cleared <input type="date"> sends "", and the two branches of boundValue must read it the
        // same way. Read as "a value was sent", it re-stamped the marker the member had just answered.
        check("a blank clears a marker rather than re-stamping it",
            Boolean.FALSE.equals(PersonFields.boundValue("detailsConfirmedDate", ""))
            && Boolean.FALSE.equals(PersonFields.boundValue("detailsConfirmedDate", "   ")));

        // Judged where every other client-value rule is judged — before the database is asked, and
        // answered with a key the client can translate, not the driver's nameless coercion error.
        // A marker's value is never written, but it is still judged: a client sending garbage for one is
        // refused by name rather than having it quietly read as "a value was sent".
        check("a malformed date is refused by name, before any database call",
            "detailsConfirmedDate".equals(firstNotADate("detailsConfirmedDate", "31/01/1990")));
        check("a number is not a date",
            "detailsConfirmedDate".equals(firstNotADate("detailsConfirmedDate", 19900131)));
        check("nor is a year Postgres cannot store",
            "detailsConfirmedDate".equals(firstNotADate("detailsConfirmedDate", "+999999999-01-01")));
        // A Temporal date travels as `$LD:` and arrives typed; one that went through toString()
        // somewhere arrives as text. Either is a date.
        check("an ISO string is",
            firstNotADate("detailsConfirmedDate", "2026-09-22") == null);
        check("and so is a LocalDate",
            firstNotADate("detailsConfirmedDate", java.time.LocalDate.of(2026, 9, 22)) == null);
        check("and a blank is a cleared date, not a malformed one",
            firstNotADate("detailsConfirmedDate", "  ") == null);

        // Asserted on the shared builder, not only through the update: addMember composes its own insert
        // inline, and a member added with a confirmation date of its own choosing would start the year's
        // holiday at creation. All three statements get the rule because all three ask this one question.
        check("the insert asks the same builder the update does",
            PersonFields.valueExpression("detailsConfirmedDate", 4).contains("current_date"));
        check("an ordinary field is still the client's own value",
            "$4".equals(PersonFields.valueExpression("firstName", 4)));

        // --- an unpaired field is refused, which is what the pair encoding is for ---
        List<String> p2 = new ArrayList<>();
        List<Object> v2 = new ArrayList<>();
        check("an odd-length pair list is refused",
            PersonDetailsRules.collect(new Object[] { "firstName", "A", "phone" }, p2, v2) != null);

        // --- a value the column cannot hold is named, not passed to Postgres ---
        List<String> p3 = new ArrayList<>(List.of("firstName"));
        List<Object> v3 = new ArrayList<>(List.of("x".repeat(46)));
        check("an over-long value is caught by name", "firstName".equals(PersonDetailsRules.firstTooLong(p3, v3)));
        v3.set(0, "x".repeat(45));
        check("a value that just fits is allowed", PersonDetailsRules.firstTooLong(p3, v3) == null);

        // --- re-sending an owner's own address is not a change ---
        check("the same address is not a change", PersonDetailsRules.sameText("A@b.com", "a@B.com "));
        check("null and empty are the same absence", PersonDetailsRules.sameText(null, ""));
        check("a different address is a change", !PersonDetailsRules.sameText("a@b.com", "c@d.com"));

        // --- a foreign key must arrive as an id, not as the entity the change-set layer would unwrap ---
        List<String> p4 = new ArrayList<>(List.of("country"));
        List<Object> v4 = new ArrayList<>();
        v4.add(java.util.Map.of("id", 5));
        check("an entity sent where an id belongs is refused", "country".equals(PersonDetailsRules.firstNotAnId(p4, v4)));
        v4.set(0, 5);
        check("a plain id is accepted", PersonDetailsRules.firstNotAnId(p4, v4) == null);
        v4.set(0, null);
        check("clearing a foreign key is allowed", PersonDetailsRules.firstNotAnId(p4, v4) == null);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    /** One name/value pair past the date rule, for readability above. */
    static String firstNotADate(String name, Object value) {
        return PersonDetailsRules.firstNotADate(java.util.List.of(name),
            java.util.Collections.singletonList(value));
    }
}

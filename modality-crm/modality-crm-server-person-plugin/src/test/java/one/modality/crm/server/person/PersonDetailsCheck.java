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
        for (String allowed : new String[] { "firstName", "birthdate", "country", "removed", "occasional", "email" })
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

        // --- a date reaches a date column ---
        check("birthdate carries its cast",
            PersonDetailsRules.updateStatementFor(names("birthdate")).contains("birthdate = $1::date"));
        check("a text field carries no cast",
            PersonDetailsRules.updateStatementFor(names("phone")).contains("phone = $1 "));

        // --- the statement answers for itself ---
        check("an update returns its row, so a miss is not reported as a save",
            profile.endsWith(" returning id"));

        // --- removing is not a profile edit on the row a sign-in resolves to ---
        String removing = PersonDetailsRules.updateStatementFor(names("removed"));
        check("removing refuses an account owner", removing.contains("and owner = false"));

        // --- the booking flow's markers, without which the review step is refused outright ---
        for (String marker : new String[] { "detailsConfirmedDate", "addressDeprecatedDate", "organizationDeprecatedDate" })
            check(marker + " is editable", PersonFields.isEditable(marker));

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

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

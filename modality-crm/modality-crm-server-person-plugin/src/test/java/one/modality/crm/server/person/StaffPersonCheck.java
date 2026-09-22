package one.modality.crm.server.person;

import java.util.ArrayList;
import java.util.List;

/**
 * Check for the back office's person writes: which fields, whose grant, and which rows.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, like the other checks here.
 *
 * <p>Two of these are gates rather than tests. The staff field list is a SUPERSET of the member one, so
 * the thing worth asserting is not that staff can set a field but that a member still cannot — a merge
 * of the two maps would be invisible except here. And each endpoint's route is what decides who may use
 * it; folding two endpoints onto one route would let whoever holds either grant do the other's work,
 * which nothing else in the build would notice.
 */
public class StaffPersonCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static List<String> names(String... n) {
        return List.of(n);
    }

    /** What a route's collector refuses these pairs for, or null. */
    static String rejection(java.util.Set<String> allowed, Object... namesAndValues) {
        return StaffPersonRules.collect(namesAndValues, new ArrayList<>(), new ArrayList<>(), allowed::contains);
    }

    public static void main(String[] args) {
        System.out.println("StaffPersonCheck");

        // --- the statement reaches anybody's row, which is the difference from the member one ---
        String profile = StaffPersonRules.updateStatementFor(names("firstName", "lastName"));
        check("a staff update names its row and nothing else",
            profile.contains(" where id = $3") && profile.endsWith(" returning id"));
        check("it carries NO ownership clause — staff edit other people's rows by definition",
            !profile.contains("frontend_account_id") && !profile.contains(" or id = "));
        // A member's statement requires removed = false so a member's screen cannot resurrect a row.
        // Staff soft-delete duplicates and undo it, so the same clause would break the customers screen.
        check("and no removed = false, because staff un-delete as well as delete",
            !profile.contains("removed = false"));
        check("it answers for itself, so a miss is not reported as a save",
            profile.endsWith(" returning id"));

        // --- except an owner's login, which is nobody's to change from a screen ---
        String withEmail = StaffPersonRules.updateStatementFor(names("firstName", "email"));
        check("writing an email refuses an account owner",
            withEmail.contains(" and owner = false"));
        // Asymmetric at first: email guarded, `removed` not — so any of these grants could soft-delete
        // an account owner, and with no `removed = false` in this statement, no member screen could
        // undo it. PersonDetailsRules.whereClause guards both, for both of the same reasons.
        check("soft-deleting refuses an account owner too",
            StaffPersonRules.updateStatementFor(names("removed")).contains(" and owner = false"));
        check("and a statement not writing email does not carry that clause",
            !profile.contains("owner = false"));

        // --- the staff list is a superset, and the member list must NOT have grown ---
        for (String staffOnly : new String[] { "nationality", "passport", "resident", "genderChangedDate" }) {
            check(staffOnly + " is staff-editable", PersonFields.isStaffEditable(staffOnly));
            check(staffOnly + " is still NOT member-editable", !PersonFields.isEditable(staffOnly));
        }
        for (String shared : new String[] { "firstName", "phone", "removed", "organization" })
            check(shared + " is editable by both", PersonFields.isEditable(shared) && PersonFields.isStaffEditable(shared));

        // --- and the structural fields are refused for staff too ---
        // These are account structure, not details: linking, unlinking and account takeover. The merge
        // dialogs still write them through a change set, where PersonAccountMovePolicy judges them.
        for (String structural : new String[] { "frontendAccount", "accountPerson", "owner", "accountPersonRevokedDate" })
            for (java.util.Set<String> list : List.of(StaffPersonRules.CUSTOMER_FIELDS,
                                                     StaffPersonRules.USER_FIELDS,
                                                     StaffPersonRules.RESIDENT_FIELDS))
                check("no route may set " + structural,
                    structural.equals(rejection(list, structural, 1)));

        // --- the value of a marker is the server's here too ---
        String stamped = StaffPersonRules.updateStatementFor(names("genderChangedDate"));
        check("dismissing a gender-change marker clears it, and cannot post-date it",
            stamped.contains("case when $1 then current_date else null end"));

        // --- residency: the one target that HAS an organization ---
        check("adding a resident SETS the centre the grant was checked for",
            StaffPersonRules.ADD_RESIDENT_SQL.contains("organization_id = $1")
            && StaffPersonRules.ADD_RESIDENT_SQL.contains("where id = $2"));
        // Without this, a resident manager could name their own centre and a stranger's id and edit
        // somebody at another — ending their residency, or changing their room and rent.
        String residentEdit = StaffPersonRules.residentStatementFor(names("resident", "sponsored"));
        check("editing a resident TESTS that they are at that centre, AND that they are one",
            residentEdit.contains(" where id = $3 and organization_id = $4 and resident = true"));
        // THE ONE THIS CHECK MISSED. The resident path shared isStaffEditable, so a /residents grant
        // could write `email` — on a row the client's own search guarantees is an account OWNER, which
        // the V0062 trigger copies into frontend_account.username. Add anyone to your centre, rewrite
        // their login, recover the password at your own address. It could also send `organization` to
        // move somebody to a centre it holds no grant for, or `removed` to delete them.
        String residentRules = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/StaffPersonRules.java");
        check("the resident path collects against its OWN allowlist",
            residentRules.contains("collect(namesAndValues, names, values, RESIDENT_FIELDS::contains)"));
        for (String beyondResidency : new String[] { "email", "organization", "removed", "firstName",
                                                     "birthdate", "detailsConfirmedDate", "nationality" })
            check("a /residents grant may not write " + beyondResidency,
                !StaffPersonRules.RESIDENT_FIELDS.contains(beyondResidency));
        // THE SECOND HALF OF THE SAME LESSON. /residents was scoped to a centre and given its own list,
        // while the other two kept a staff-wide superset that still contained the residency columns —
        // so a /customers holder could send `organization` and `resident` together and create a
        // sponsored, rent-free resident at a centre they hold no grant for. One list per route.
        for (String residency : StaffPersonRules.RESIDENT_FIELDS) {
            check("/customers may not write " + residency + " — that is the scoped endpoint's job",
                !StaffPersonRules.CUSTOMER_FIELDS.contains(residency));
            check("/users may not write " + residency,
                !StaffPersonRules.USER_FIELDS.contains(residency));
        }
        check("and no route carries a staff-wide superset any more",
            !residentRules.contains("PersonFields::isStaffEditable"));
        // The users form sends seven fields; the endpoint accepts those and no more.
        check("/users is the form's own short list",
            StaffPersonRules.USER_FIELDS.size() == 7
            && !StaffPersonRules.USER_FIELDS.contains("organization")
            && !StaffPersonRules.USER_FIELDS.contains("removed")
            && !StaffPersonRules.USER_FIELDS.contains("passport"));
        check("and the residency fields themselves are all there, and all staff-editable",
            StaffPersonRules.RESIDENT_FIELDS.size() == 7
            && StaffPersonRules.RESIDENT_FIELDS.stream().allMatch(PersonFields::isStaffEditable));

        // Claiming a person for your centre is not unconditional: without the tail, a grant holder at
        // one centre could take another centre's resident, or overwrite a member's self-chosen centre.
        check("adding refuses a deleted row, and one who is already somebody else's resident",
            StaffPersonRules.ADD_RESIDENT_SQL.contains("removed = false")
            && StaffPersonRules.ADD_RESIDENT_SQL.contains("(resident = false or organization_id = $1)"));
        check("ending a residency is that same edit, not a statement of its own",
            StaffPersonRules.residentStatementFor(names("resident")).contains("resident = $1")
            && StaffPersonRules.residentStatementFor(names("resident")).contains("organization_id = $3"));
        check("and it still passes the residency test, since the row IS resident when it is ended",
            StaffPersonRules.residentStatementFor(names("resident")).contains("and resident = true"));
        check("both answer for themselves",
            StaffPersonRules.ADD_RESIDENT_SQL.endsWith(" returning id")
            && residentEdit.endsWith(" returning id"));
        // The residents screens' own columns, which nothing else writes.
        for (String residentField : new String[] { "residentBooksBreakfast", "residentBooksLunch",
                                                   "residentBooksDinner", "sponsored", "residentRoom",
                                                   "residentRoomMonthlyRent" }) {
            check(residentField + " is staff-editable", PersonFields.isStaffEditable(residentField));
            check(residentField + " is not member-editable", !PersonFields.isEditable(residentField));
        }

        // --- one route per endpoint, and they are not the same route ---
        check("the customers endpoint checks /customers",
            "/customers".equals(UpdateCustomerEndpoint.CUSTOMERS_ROUTE));
        check("the users endpoint checks /users",
            "/users".equals(UpdateUserEndpoint.USERS_ROUTE));
        // /users LOOKS like /customers and is not: its screen lists from
        // AuthorizationOrganizationUserAccess where organization=$1, so the people it reaches are the
        // handful holding a grant row at the selected centre. Unscoped, the endpoint turned that
        // handful into every person record in the database.
        String userEdit = StaffPersonRules.updateStatementFor(names("firstName"), true);
        check("editing a user requires the TARGET to hold a grant row at that centre",
            userEdit.contains("exists (select 1 from authorization_organization_user_access ua")
            && userEdit.contains("ua.user_id = $2 and ua.organization_id = $3"));
        check("and the customers statement carries no such fence, because its screen has none",
            !StaffPersonRules.updateStatementFor(names("firstName")).contains("authorization_organization_user_access"));
        check("both residents endpoints check /residents",
            "/residents".equals(SetResidentEndpoint.RESIDENTS_ROUTE));
        check("no two of them share a route, which would merge their grants",
            !UpdateCustomerEndpoint.CUSTOMERS_ROUTE.equals(UpdateUserEndpoint.USERS_ROUTE)
            && !UpdateCustomerEndpoint.CUSTOMERS_ROUTE.equals(SetResidentEndpoint.RESIDENTS_ROUTE)
            && !UpdateUserEndpoint.USERS_ROUTE.equals(SetResidentEndpoint.RESIDENTS_ROUTE));

        // --- and only the residents one is scoped, because only its target has an organization ---
        String residents = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/SetResidentEndpoint.java");
        check("adding a resident uses the SCOPED guard",
            residents.contains("whenCallerMayReach(RESIDENTS_ROUTE"));
        String residentEdits = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/UpdateResidentEndpoint.java");
        check("editing a resident uses it too",
            residentEdits.contains("whenCallerMayReach(SetResidentEndpoint.RESIDENTS_ROUTE"));
        String customers = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/UpdateCustomerEndpoint.java");
        // Only ONE of the four, and only because its screen genuinely has no organization condition.
        check("only /customers uses the unscoped guard, knowingly",
            customers.contains("whenCallerMayReachAnywhere(CUSTOMERS_ROUTE"));
        String users = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/UpdateUserEndpoint.java");
        check("/users uses the SCOPED guard, because its screen is scoped",
            users.contains("whenCallerMayReach(USERS_ROUTE, organizationId"));

        // --- the ordinary value checks the member endpoints make ---
        check("an unpaired field is refused",
            rejection(StaffPersonRules.CUSTOMER_FIELDS, "firstName") != null);
        check("the same field twice is refused",
            rejection(StaffPersonRules.CUSTOMER_FIELDS, "firstName", "A", "firstName", "B") != null);
        List<String> n = new ArrayList<>(List.of("nationality"));
        List<Object> v = new ArrayList<>(List.of("x".repeat(65)));
        check("an over-long staff-only value is caught by name too",
            "nationality".equals(PersonDetailsRules.firstTooLong(n, v)));
        // The four resident booleans are NOT NULL, and neither the length check (varchars) nor the id
        // check (integers) looks at a boolean — so a null reached the driver as a raw Postgres 23502.
        check("a null in a NOT NULL boolean is refused by name, not by Postgres",
            StaffPersonRules.NOT_A_BOOLEAN_KEY.equals(StaffPersonRules.firstBadValue(
                new ArrayList<>(List.of("sponsored")), new ArrayList<>(java.util.Collections.singletonList(null)))));
        check("and a real boolean passes",
            StaffPersonRules.firstBadValue(new ArrayList<>(List.of("sponsored")),
                new ArrayList<>(List.of(Boolean.TRUE))) == null);

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}

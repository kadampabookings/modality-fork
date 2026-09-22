package one.modality.crm.server.person;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.util.ArrayList;
import java.util.List;

/**
 * The back office editing a person — anybody's person, which is the whole difference.
 *
 * <p>{@link PersonDetailsRules} answers "is this row in the caller's account". That question has no
 * useful answer for staff: a member of staff editing a customer is editing somebody else's row by
 * definition, and the customers screen exists to reach all of them. So the check moves from ownership
 * to a grant, and the statement loses its ownership clause — see {@link #updateStatementFor}.
 *
 * <p><b>The grant is checked on the server, which it was not before.</b> These screens guarded
 * themselves with the client's router (`requireRoute('/customers')`), and the write that followed was a
 * change set any caller could have sent without running the screen at all. A member, a guest, an
 * anonymous caller — all could write any person row. That is what this closes.
 *
 * <h3>What is NOT checked, said plainly</h3>
 *
 * <p>Which ROW a member of staff may edit is not checked, because a person has no organization that
 * means "who may edit them" — {@code person.organization_id} is a centre the member chose for
 * themselves. See {@code RouteGrantMembership.mayReachRouteAnywhere}: the back office's own guard scopes
 * to the SIDEBAR's organization rather than to the row, and then lets the screen read and write every
 * person there is. A narrower test here would refuse work the screen legitimately does and stop nothing.
 * Tightening it needs a notion of person tenancy the schema does not have.
 *
 * <p>What IS gained is therefore not "staff may only touch their own people" but "only staff may touch
 * people at all" — plus a real session, no support view, and a field list the client no longer chooses.
 *
 * @author Claude Code
 */
final class StaffPersonRules {

    private StaffPersonRules() {
    }

    /** A field this caller may not set, or one nobody may. Same key the member endpoints use. */
    static final String FIELD_NOT_EDITABLE_KEY = PersonDetailsRules.FIELD_NOT_EDITABLE_KEY;
    /** No row matched — a person id that does not exist, or an owner whose email was being changed. */
    static final String NOT_UPDATED_KEY = "StaffPersonNotUpdatedError";
    /** A null or a string where a NOT NULL boolean column is, refused by name rather than by Postgres. */
    static final String NOT_A_BOOLEAN_KEY = "PersonNotABooleanError";

    /**
     * Updates the fields named in {@code namesAndValues} on {@code rawPersonId}.
     *
     * @param namesAndValues alternating field name and value, as the endpoint received them
     * @param callerUserId   the caller's principal, READ ON THE CALLER'S THREAD — {@code person} has
     *                       audit triggers that stamp {@code changed_by_person_id} from it, and running
     *                       without it would record every back-office edit as nobody's
     */
    static Future<Boolean> updateCustomer(Object rawPersonId, Object[] namesAndValues, Object callerUserId) {
        return updatePerson(rawPersonId, namesAndValues, CUSTOMER_FIELDS, null, callerUserId);
    }

    /**
     * The users screen editing somebody's personal details — scoped to the centre they are a user OF.
     *
     * <p>This looked like the customers case and is not, which a review had to point out. A person has
     * no owning organization, so {@code updateCustomer} cannot be scoped; but the users screen lists
     * from {@code AuthorizationOrganizationUserAccess where organization = $1}, so the people it
     * reaches ARE the ones holding a grant row at the selected centre — typically a handful. Guarded
     * without a scope, the endpoint accepted any person id and asked only "do you hold /users
     * somewhere", which turned a grant over a handful of colleagues into one over every member record
     * in the database.
     *
     * <p>So the centre travels with the call, the caller's grant is checked against it, and the
     * statement requires the TARGET to hold an access row there — the same two halves as a residency,
     * for the same reason.
     */
    static Future<Boolean> updateUser(Object rawPersonId, Object rawOrganizationId,
                                      Object[] namesAndValues, Object callerUserId) {
        Object organizationId = Numbers.toLong(rawOrganizationId);
        if (organizationId == null)
            return refusal(NOT_UPDATED_KEY);
        return updatePerson(rawPersonId, namesAndValues, USER_FIELDS, organizationId, callerUserId);
    }

    /**
     * Updates the fields named in {@code namesAndValues} on {@code rawPersonId}.
     *
     * @param allowed        this route's field list — never a shared staff superset, see the lists
     * @param userOfOrgId    when non-null, the target must hold an access row at this organization
     * @param callerUserId   the caller's principal, READ ON THE CALLER'S THREAD — {@code person} has
     *                       audit triggers that stamp {@code changed_by_person_id} from it, and running
     *                       without it would record every back-office edit as nobody's
     */
    private static Future<Boolean> updatePerson(Object rawPersonId, Object[] namesAndValues,
                                                java.util.Set<String> allowed, Object userOfOrgId,
                                                Object callerUserId) {
        Object personId = Numbers.toLong(rawPersonId);
        if (personId == null)
            return refusal(NOT_UPDATED_KEY);
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        String rejected = collect(namesAndValues, names, values, allowed::contains);
        if (rejected != null)
            return refusal(FIELD_NOT_EDITABLE_KEY);
        if (names.isEmpty())
            return refusal(PersonDetailsRules.NO_FIELDS_KEY);
        String badValue = firstBadValue(names, values);
        if (badValue != null)
            return refusal(badValue);
        List<Object> parameters = PersonFields.boundValues(names, values);
        parameters.add(personId);
        if (userOfOrgId != null)
            parameters.add(userOfOrgId);
        SubmitArgument update = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(updateStatementFor(names, userOfOrgId != null))
            .setParameters(parameters.toArray())
            .setReturnGeneratedKeys(true)
            .build();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { update })))
            .compose(results -> {
                Object[] keys = results == null || results.getArray().length == 0 ? null
                    : results.getArray()[0].getGeneratedKeys();
                // The statement answers for itself, for the reason PersonDetailsRules gives: a separate
                // "did that work" read would not carry the owner clause, so an update that matched
                // nothing would still be reported as saved and the edits silently lost.
                return keys == null || keys.length == 0 ? refusal(NOT_UPDATED_KEY)
                    : Future.succeededFuture(true);
            });
    }

    /**
     * Adds somebody to a centre's residents: the one write that SETS the organization.
     *
     * <p>The person may come from anywhere — that is what adding means — so the centre is written
     * rather than tested. Which centre is not the client's to choose freely: the caller's
     * {@code /residents} grant was checked against this same id before this runs, so naming one they
     * do not hold is refused before any row is touched.
     */
    static Future<Boolean> addResident(Object rawPersonId, Object rawOrganizationId, Object callerUserId) {
        Object personId = Numbers.toLong(rawPersonId);
        Object organizationId = Numbers.toLong(rawOrganizationId);
        if (personId == null || organizationId == null)
            return refusal(NOT_UPDATED_KEY);
        return runResidentWrite(ADD_RESIDENT_SQL, new Object[] { organizationId, personId }, callerUserId);
    }

    /**
     * Edits a resident of a centre — their meals, sponsorship, room and rent, or their residency itself.
     *
     * <p><b>The statement tests the centre as well as the id</b>, and that is the half the client must
     * not be trusted with. The grant check proves the caller may act at the centre they NAMED; without
     * {@code organization_id = $n} in the WHERE, a resident manager of one centre could name their own
     * and a stranger's id and edit somebody at another — the id is small and sequential.
     *
     * <p>Taking somebody off the residents list is this same operation with {@code resident = false},
     * not a separate one: it is an edit to a resident of that centre like any other.
     *
     * <p><b>Its own field list, not the staff superset</b>, and the difference is not cosmetic. Sharing
     * {@code isStaffEditable} here meant a {@code /residents} grant could write {@code email} — which,
     * on a row the client's own search guarantees is an account OWNER, the V0062 trigger copies into
     * {@code frontend_account.username}. Add any person to your centre, rewrite their login, recover
     * the password at your own address. It could also send {@code organization} to move a resident to
     * a centre the caller has no grant for, or {@code removed} to delete them, or
     * {@code detailsConfirmedDate} to suppress a member's review for a year. None of those is a
     * residency.
     */
    static Future<Boolean> updateResident(Object rawPersonId, Object rawOrganizationId,
                                          Object[] namesAndValues, Object callerUserId) {
        Object personId = Numbers.toLong(rawPersonId);
        Object organizationId = Numbers.toLong(rawOrganizationId);
        if (personId == null || organizationId == null)
            return refusal(NOT_UPDATED_KEY);
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        String rejected = collect(namesAndValues, names, values, RESIDENT_FIELDS::contains);
        if (rejected != null)
            return refusal(FIELD_NOT_EDITABLE_KEY);
        if (names.isEmpty())
            return refusal(PersonDetailsRules.NO_FIELDS_KEY);
        String badValue = firstBadValue(names, values);
        if (badValue != null)
            return refusal(badValue);
        List<Object> parameters = PersonFields.boundValues(names, values);
        parameters.add(personId);
        parameters.add(organizationId);
        return runResidentWrite(residentStatementFor(names), parameters.toArray(), callerUserId);
    }

    private static Future<Boolean> runResidentWrite(String sql, Object[] parameters, Object callerUserId) {
        SubmitArgument update = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters)
            .setReturnGeneratedKeys(true)
            .build();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { update })))
            .compose(results -> {
                Object[] keys = results == null || results.getArray().length == 0 ? null
                    : results.getArray()[0].getGeneratedKeys();
                // No row back means the centre did not match — which is a refusal, not a no-op, and
                // must not be reported as a save.
                return keys == null || keys.length == 0 ? refusal(NOT_UPDATED_KEY)
                    : Future.succeededFuture(true);
            });
    }

    /**
     * The centre is set rather than tested — that is what adding means — but NOT unconditionally.
     *
     * <p>Written without the tail, this was an unrestricted "claim this person for my centre": it
     * rewrote {@code organization_id} on any id, so a grant holder at one centre could take another
     * centre's resident (removing them from that centre's list, and bringing them inside this one's
     * edit predicate), or silently replace an ordinary member's self-chosen centre. The client's
     * search filters {@code !resident and !removed}; the server had to say the same thing, because the
     * client's filter is not a check and person ids are small and sequential.
     *
     * <p>{@code resident = false or organization_id = $1} rather than {@code resident = false} alone,
     * so re-adding somebody already yours stays idempotent instead of refusing.
     */
    static final String ADD_RESIDENT_SQL =
        "update person set resident = true, organization_id = $1"
        + " where id = $2 and removed = false and (resident = false or organization_id = $1)"
        + " returning id";

    /**
     * What each grant may write. <b>One list per route, never a shared staff superset.</b>
     *
     * <p>{@code /residents} was given its own first, because a residency belongs to a centre and its
     * endpoints are scoped to one. The other two kept the superset, and a review showed what that was
     * worth: a {@code /customers} holder could send {@code organization} and {@code resident} together
     * and create a sponsored, rent-free resident at a centre they hold no grant for — the exact write
     * the scoped endpoints exist to refuse. A list per route is the only version of this that says
     * what it means.
     *
     * <p>Each one is what its SCREEN sends, and nothing held in reserve. When the customer merge moves
     * onto these endpoints it will need {@code removed}; it can be added then, by somebody who can see
     * why.
     */
    static final java.util.Set<String> CUSTOMER_FIELDS = java.util.Set.of(
        "firstName", "lastName", PersonFields.EMAIL, "phone", "male", "ordained", "layName",
        "street", "postCode", "cityName", "country", "nationality", "passport", "birthdate",
        "organization", "genderChangedDate", "addressDeprecatedDate", "organizationDeprecatedDate");

    /** The users screen's personal-details form, which sends exactly these. */
    static final java.util.Set<String> USER_FIELDS = java.util.Set.of(
        "firstName", "lastName", PersonFields.EMAIL, "phone", "street", "postCode", "cityName");

    /** Residency and nothing else — see {@link #updateResident}. */
    static final java.util.Set<String> RESIDENT_FIELDS = java.util.Set.of(
        "resident", "residentBooksBreakfast", "residentBooksLunch", "residentBooksDinner",
        "sponsored", "residentRoom", "residentRoomMonthlyRent");

    /** The statement a given resident field list produces, for a check to read without a database. */
    static String residentStatementFor(List<String> names) {
        StringBuilder sql = new StringBuilder("update person set ");
        int n = 0;
        for (String name : names) {
            if (n > 0)
                sql.append(", ");
            n++;
            sql.append(PersonFields.fieldFor(name).column()).append(" = ")
               .append(PersonFields.valueExpression(name, n));
        }
        // The centre AND the residency, which together are what make this a resident edit rather than a
        // person edit. Without `resident = true` a /residents holder could write residency columns onto
        // any ordinary member whose chosen centre happened to be theirs — a member is not a resident,
        // and becoming one is addResident's job, with its own preconditions. Ending a residency passes
        // this: the row IS resident at the moment it is ended.
        return sql.append(" where id = $").append(n + 1)
                  .append(" and organization_id = $").append(n + 2)
                  .append(" and resident = true")
                  .append(" returning id").toString();
    }

    /**
     * Reads the pairs, keeping only what {@link PersonFields#isStaffEditable} allows.
     *
     * <p>Its own copy rather than {@code PersonDetailsRules.collect}, because the one thing that differs
     * between them is the allowlist — and sharing it by passing a flag would put the difference between
     * a member and a member of staff inside a boolean argument.
     *
     * @return the first name that is not staff-editable, or null when every one of them is
     */
    /**
     * Reads the pairs, keeping only what {@code allowed} lets through.
     *
     * <p>There is deliberately no overload defaulting to a staff-wide list. One existed, every caller
     * but the residents took it, and that is how {@code /customers} came to be able to write residency
     * columns at any centre. Naming the list is the point.
     */
    static String collect(Object[] namesAndValues, List<String> names, List<Object> values,
                          java.util.function.Predicate<String> allowed) {
        if (namesAndValues == null)
            return null;
        if (namesAndValues.length % 2 != 0)
            return "(an unpaired field)";
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            String name = String.valueOf(namesAndValues[i]);
            if (!allowed.test(name))
                return name;
            if (names.contains(name)) // the same field twice would build an invalid SET clause
                return name;
            names.add(name);
            values.add(namesAndValues[i + 1]);
        }
        return null;
    }

    /**
     * The refusal key for the first value the columns cannot hold, or null when they all can.
     *
     * <p>One place, so the three write paths cannot end up checking different things. The boolean case
     * is the one that had to be added: {@code resident_books_breakfast} and its three siblings are
     * {@code NOT NULL}, and neither the length check (varchars only) nor the id check (integers only)
     * looks at a boolean — so a null went to the driver and came back as a raw Postgres 23502, which
     * is exactly the shape {@code VALUE_TOO_LONG} exists to prevent.
     */
    static String firstBadValue(List<String> names, List<Object> values) {
        if (PersonDetailsRules.firstTooLong(names, values) != null)
            return PersonDetailsRules.VALUE_TOO_LONG_KEY;
        if (PersonDetailsRules.firstNotAnId(names, values) != null)
            return PersonDetailsRules.NOT_AN_ID_KEY;
        if (PersonDetailsRules.firstNotADate(names, values) != null)
            return PersonDetailsRules.NOT_A_DATE_KEY;
        for (int i = 0; i < names.size(); i++) {
            PersonFields.Field field = PersonFields.fieldFor(names.get(i));
            if (field != null && field.kind() == PersonFields.Kind.BOOLEAN
                && !(values.get(i) instanceof Boolean))
                return NOT_A_BOOLEAN_KEY;
        }
        return null;
    }

    /**
     * The statement a given field list produces, for a check to read without a database.
     *
     * <p>No ownership clause, which is the difference from the member statement and is deliberate. Two
     * clauses survive:
     *
     * <ul>
     *   <li>{@code removed = false} is NOT one of them. Staff soft-delete duplicates and un-delete
     *       people, so a removed row is still theirs to edit — where a member's screen must never
     *       resurrect one.</li>
     *   <li>{@code owner = false} when {@code email} or {@code removed} is written, the same rule
     *       {@code OwnerLoginWritePolicy} applies to every client: an account owner's email is their
     *       sign-in address, and changing it belongs to the flow that verifies the new one. The screens
     *       already decline to send it for an owner; this is what makes that true rather than polite.</li>
     * </ul>
     */
    static String updateStatementFor(List<String> names) {
        return updateStatementFor(names, false);
    }

    /** @param userOfOrg whether the target must also hold an access row at the organization parameter */
    static String updateStatementFor(List<String> names, boolean userOfOrg) {
        StringBuilder sql = new StringBuilder("update person set ");
        int n = 0;
        for (String name : names) {
            if (n > 0)
                sql.append(", ");
            n++;
            sql.append(PersonFields.fieldFor(name).column()).append(" = ")
               .append(PersonFields.valueExpression(name, n));
        }
        sql.append(" where id = $").append(n + 1);
        // The users screen's own scope: the target holds a grant row at the centre the caller named,
        // which is exactly the set that screen lists. $n+1 again rather than `person.id` — the row is
        // already pinned by the clause above, so naming the parameter keeps it unambiguous.
        if (userOfOrg)
            sql.append(" and exists (select 1 from authorization_organization_user_access ua")
               .append(" where ua.user_id = $").append(n + 1)
               .append(" and ua.organization_id = $").append(n + 2).append(")");
        // The same two fields PersonDetailsRules.whereClause guards, for the same reason and not a
        // narrower one: an owner's email IS the account's login, and removing the row a sign-in
        // resolves to leaves the account unable to reach itself. It was asymmetric at first — email
        // guarded here, `removed` not — which would have let any of these three grants soft-delete an
        // account owner, with no `removed = false` in this statement to undo it from a member screen.
        if (names.contains(PersonFields.EMAIL) || names.contains("removed"))
            sql.append(" and owner = false");
        return sql.append(" returning id").toString();
    }

    private static <T> Future<T> refusal(String key) {
        return Future.failedFuture("[" + key + "] This operation is not available to you");
    }
}

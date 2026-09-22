package one.modality.crm.server.person;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.util.ArrayList;
import java.util.List;

/**
 * A member editing their own details, or those of somebody in their account.
 *
 * <p>The screens used to build {@code update Person set …} from whatever the form held, over a row they
 * had read. Two things were therefore the client's to decide and are now neither: WHICH FIELDS (see
 * {@link PersonFields}) and WHOSE ROW.
 *
 * <h3>Whose row</h3>
 *
 * <p>The caller's own person, or a person in the caller's account. Both come from the principal, so a
 * caller cannot name a third. Expressed in the WHERE of the statement rather than checked beforehand,
 * for the reason the rest of this package repeats: a row can be moved between a check and a write, and
 * {@code SubmitResult.getRowCount()} counts result sets rather than rows, so it cannot report whether a
 * guarded update matched. Every operation here reads back what it did.
 *
 * <p><b>"In my account" is a weaker claim than it sounds</b>, and worth stating: an account's people
 * include rows linked in from elsewhere. It is the same reach the screens already had — this is not
 * widening anything — but when the claim arm of linking arrives it will be the rule that decides how far
 * an editable row extends, and it should be looked at again then rather than inherited.
 *
 * @author Claude Code
 */
final class PersonDetailsRules {

    private PersonDetailsRules() {
    }

    /** Refusals, named so the screen can say which. */
    static final String NOT_YOUR_PERSON_KEY = "PersonNotYoursError";
    static final String NO_FIELDS_KEY = "PersonNoFieldsError";
    static final String FIELD_NOT_EDITABLE_KEY = "PersonFieldNotEditableError";
    /** A value the column cannot hold — refused by name rather than as raw database text. */
    static final String VALUE_TOO_LONG_KEY = "PersonValueTooLongError";
    /** Changing the sign-in address of an account owner, which belongs to the flow that verifies it. */
    static final String OWNER_EMAIL_KEY = "PersonOwnerEmailError";
    /**
     * A foreign key sent as something other than an id.
     *
     * <p>The change-set layer takes {@code {id: 5}} and extracts the 5, so every screen writing a person
     * is in the habit of sending entities. A bus call has no such layer — the same distinction the React
     * guide draws between a mutation's fields and a query's parameters. Refused rather than unwrapped
     * here, so a caller that kept the habit is told, instead of having its object quietly coerced into
     * whatever Postgres makes of it.
     */
    static final String NOT_AN_ID_KEY = "PersonNotAnIdError";
    static final String NOT_A_DATE_KEY = "PersonNotADateError";

    /**
     * Whose row this is, asked as one question.
     *
     * <p>Returns nothing at all for a person in nobody's reach, which is the same answer as a person who
     * does not exist — deliberately, so this cannot be used to ask whether an id is taken.
     */
    private static final String TARGET_SQL =
        "select owner, email from person" +
        " where id = $1 and removed = false and (id = $2 or frontend_account_id = $3)";

    /** A new person in the caller's own account, never anybody else's. */
    private static final String INSERT_SQL_PREFIX =
        "insert into person (frontend_account_id, owner";

    /**
     * Updates the fields named in {@code namesAndValues} on {@code rawPersonId}.
     *
     * @param namesAndValues alternating field name and value, as the endpoint received them
     */
    static Future<Boolean> updateDetails(Object rawPersonId, Object[] namesAndValues,
                                          Object callerPersonId, Object callerAccountId, Object callerUserId) {
        Object personId = Numbers.toLong(rawPersonId);
        if (personId == null)
            return MemberSessionGuard.refused();
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        String rejected = collect(namesAndValues, names, values);
        if (rejected != null)
            // Named rather than ignored: a screen sending a field this does not know is a screen and a
            // server that disagree, and silently dropping it would save the form and lose the value.
            return refusal(FIELD_NOT_EDITABLE_KEY);
        if (names.isEmpty())
            return refusal(NO_FIELDS_KEY);
        return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(TARGET_SQL)
                .setParameters(personId, callerPersonId, callerAccountId)
                .build()))
            .compose(target -> {
                if (target == null || target.getRowCount() == 0)
                    return refusal(NOT_YOUR_PERSON_KEY);
                boolean isOwnerRow = Boolean.TRUE.equals(target.getValue(0, 0));
                // An owner's address is their sign-in name, through a trigger. CHANGING it belongs to the
                // flow that verifies the new address — but a form that merely re-sends the address it
                // displayed is not changing anything, and refusing that would make the whole dialog fail
                // for somebody editing their phone. The same distinction V0097 draws in the trigger and
                // OwnerLoginWritePolicy draws for client writes: a real change, not a mention.
                int emailAt = names.indexOf(PersonFields.EMAIL);
                if (isOwnerRow && emailAt >= 0) {
                    Object current = target.getValue(0, 1);
                    if (!sameText(current, values.get(emailAt)))
                        return refusal(OWNER_EMAIL_KEY);
                    // Unchanged: drop it rather than writing it, so the statement needs no owner clause
                    // and the trigger has nothing to react to.
                    names.remove(emailAt);
                    values.remove(emailAt);
                    if (names.isEmpty())
                        return Future.succeededFuture(true); // the only field sent was a no-op
                }
                String tooLong = firstTooLong(names, values);
                if (tooLong != null)
                    return refusal(VALUE_TOO_LONG_KEY);
                if (firstNotAnId(names, values) != null)
                    return refusal(NOT_AN_ID_KEY);
                if (firstNotADate(names, values) != null)
                    return refusal(NOT_A_DATE_KEY);
                return runUpdate(personId, names, values, callerPersonId, callerAccountId, callerUserId);
            });
    }

    /**
     * Reads the pairs, keeping only what {@link PersonFields} allows.
     *
     * @return the first name that is not editable, or null when every one of them is
     */
    static String collect(Object[] namesAndValues, List<String> names, List<Object> values) {
        if (namesAndValues == null)
            return null;
        if (namesAndValues.length % 2 != 0)
            // Silently dropping the odd one out is exactly what the pair encoding is supposed to prevent.
            return "(an unpaired field)";
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            String name = String.valueOf(namesAndValues[i]);
            if (!PersonFields.isEditable(name))
                return name;
            if (names.contains(name)) // the same field twice would build an invalid SET clause
                return name;
            names.add(name);
            values.add(namesAndValues[i + 1]);
        }
        return null;
    }

    private static Future<Boolean> runUpdate(Object personId, List<String> names, List<Object> values,
                                              Object callerPersonId, Object callerAccountId, Object callerUserId) {
        StringBuilder sql = new StringBuilder("update person set ");
        List<Object> parameters = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            PersonFields.Field field = PersonFields.fieldFor(names.get(i));
            if (i > 0)
                sql.append(", ");
            parameters.add(PersonFields.boundValue(names.get(i), values.get(i)));
            sql.append(field.column()).append(" = ")
               .append(PersonFields.valueExpression(names.get(i), parameters.size()));
        }
        // The ownership test travels WITH the write. Checked only beforehand, a row moved to another
        // account in between would still be updated by this statement.
        parameters.add(personId);
        int idParameter = parameters.size();
        parameters.add(callerPersonId);
        parameters.add(callerAccountId);
        sql.append(whereClause(idParameter - 1, names));
        SubmitArgument update = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql.toString()) // column names come from PersonFields; values are parameters
            .setParameters(parameters.toArray())
            .setReturnGeneratedKeys(true)
            .build();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { update })))
            .compose(results -> returnedARow(results)
                ? Future.succeededFuture(true)
                : refusal(NOT_YOUR_PERSON_KEY));
    }

    /**
     * Adds a person to the caller's own account.
     *
     * <p>{@code owner} is written false here and is not a field a caller can send: an account's owner row
     * is what a sign-in resolves to, and a member adding one would be adding a second way into their own
     * account. {@code frontendAccount} is the caller's, from the principal — the insert that let a client
     * name any account is exactly what the plan's account-creation note is about.
     *
     * @return the new person's id
     */
    static Future<Object> addMember(Object[] namesAndValues, Object callerAccountId, Object callerUserId) {
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        String rejected = collect(namesAndValues, names, values);
        if (rejected != null)
            return refusal(FIELD_NOT_EDITABLE_KEY);
        // The same two checks the update makes. Skipped here, a 50-character first name reached the
        // column and came back as raw Postgres text no screen could translate — and no form in either
        // feature caps length on the way in.
        if (firstTooLong(names, values) != null)
            return refusal(VALUE_TOO_LONG_KEY);
        if (firstNotADate(names, values) != null)
            return refusal(NOT_A_DATE_KEY);
        if (firstNotAnId(names, values) != null)
            return refusal(NOT_AN_ID_KEY);
        StringBuilder columns = new StringBuilder(INSERT_SQL_PREFIX);
        StringBuilder placeholders = new StringBuilder(") values ($1, false");
        List<Object> parameters = new ArrayList<>();
        parameters.add(callerAccountId);
        for (int i = 0; i < names.size(); i++) {
            PersonFields.Field field = PersonFields.fieldFor(names.get(i));
            parameters.add(PersonFields.boundValue(names.get(i), values.get(i)));
            columns.append(", ").append(field.column());
            placeholders.append(", ").append(PersonFields.valueExpression(names.get(i), parameters.size()));
        }
        String sql = columns + placeholders.toString() + ") returning id";
        SubmitArgument insert = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters.toArray())
            .setReturnGeneratedKeys(true)
            .build();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { insert })))
            .compose(results -> {
                Object[] keys = results == null || results.getArray().length == 0 ? null
                    : results.getArray()[0].getGeneratedKeys();
                if (keys == null || keys.length == 0)
                    return refusal(NOT_YOUR_PERSON_KEY);
                return Future.succeededFuture(keys[0]);
            });
    }

    /**
     * The statement a given field list produces, for a check to read without a database.
     *
     * <p>Shares {@link #whereClause} with the real builder rather than restating it — a check that
     * described the WHERE separately could pass while the statement that runs had lost it.
     */
    static String updateStatementFor(List<String> names) {
        List<String> copy = new ArrayList<>(names);
        StringBuilder sql = new StringBuilder("update person set ");
        int n = 0;
        for (String name : copy) {
            PersonFields.Field field = PersonFields.fieldFor(name);
            if (n > 0)
                sql.append(", ");
            n++;
            sql.append(field.column()).append(" = ").append(PersonFields.valueExpression(name, n));
        }
        sql.append(whereClause(n, copy));
        return sql.toString();
    }

    /**
     * The clause that decides whose row this may touch, and what may not be touched on an owner's.
     *
     * <p>One builder for the statement that runs and the one a check reads, because the whole value of
     * the check is that they cannot drift.
     */
    private static String whereClause(int lastValueParameter, List<String> names) {
        StringBuilder where = new StringBuilder();
        where.append(" where id = $").append(lastValueParameter + 1)
             .append(" and removed = false")
             .append(" and (id = $").append(lastValueParameter + 2)
             .append(" or frontend_account_id = $").append(lastValueParameter + 3).append(")");
        // Neither an owner's sign-in address nor their membership is a profile edit: removing the row a
        // sign-in resolves to would leave the account unable to reach itself, and both statements here
        // require removed = false, so it could not be undone through this service either.
        if (names.contains(PersonFields.EMAIL) || names.contains("removed"))
            where.append(" and owner = false");
        // The statement answers for itself. A separate "is it still mine" read cannot: it would not
        // carry the clauses above, so an update that matched nothing would still be reported as saved
        // and the edits would be silently lost.
        where.append(" returning id");
        return where.toString();
    }

    /** The first value too long for its column, or null. */
    static String firstTooLong(List<String> names, List<Object> values) {
        for (int i = 0; i < names.size(); i++) {
            PersonFields.Field field = PersonFields.fieldFor(names.get(i));
            Object value = values.get(i);
            if (field != null && field.maxLength() > 0 && value != null
                && String.valueOf(value).length() > field.maxLength())
                return names.get(i);
        }
        return null;
    }

    /**
     * The first foreign key that did not arrive as a number, or null.
     *
     * <p>Null passes: clearing a country or an organization is an ordinary edit ("no centre").
     */
    /**
     * The first date field whose value is not one, or null.
     *
     * <p>Beside {@link #firstNotAnId} because it is the same kind of rule, and here rather than at the
     * bind so that it is judged where every other client-value rule is judged — before the database is
     * asked anything, and answered with a named refusal the client can translate. A date reaching the
     * driver unparseable gives "can not be coerced to the expected class", which names no field.
     *
     * <p>Accepts what the wire actually carries: a {@code LocalDate} (a Temporal date travels as
     * `$LD:`) or its ISO text (a value that went through {@code toString()} somewhere). A number is a
     * date to nobody, and the year is bounded because {@code LocalDate} parses years Postgres cannot
     * store — and an out-of-range one would surface as raw Postgres text in a browser.
     */
    static String firstNotADate(List<String> names, List<Object> values) {
        for (int i = 0; i < names.size(); i++) {
            PersonFields.Field field = PersonFields.fieldFor(names.get(i));
            Object value = values.get(i);
            if (field == null || field.kind() != PersonFields.Kind.DATE || value == null)
                continue;
            java.time.LocalDate day = null;
            if (value instanceof java.time.LocalDate already)
                day = already;
            else if (value instanceof CharSequence text) {
                String iso = text.toString().trim();
                if (iso.isEmpty())
                    continue; // blank clears the column; see PersonFields.boundValue
                try {
                    day = java.time.LocalDate.parse(iso);
                } catch (java.time.format.DateTimeParseException e) {
                    return names.get(i);
                }
            }
            if (day == null || day.getYear() < 1 || day.getYear() > 9999)
                return names.get(i);
        }
        return null;
    }

    static String firstNotAnId(List<String> names, List<Object> values) {
        for (int i = 0; i < names.size(); i++) {
            PersonFields.Field field = PersonFields.fieldFor(names.get(i));
            Object value = values.get(i);
            if (field != null && field.kind() == PersonFields.Kind.INTEGER
                && value != null && !(value instanceof Number))
                return names.get(i);
        }
        return null;
    }

    /** Whether two addresses are the same, null and empty treated alike. */
    static boolean sameText(Object a, Object b) {
        String left = a == null ? "" : String.valueOf(a).trim();
        String right = b == null ? "" : String.valueOf(b).trim();
        return left.equalsIgnoreCase(right);
    }

    /** Whether a " returning " statement actually returned a row — the real affected-row signal. */
    private static boolean returnedARow(Batch<dev.webfx.stack.db.submit.SubmitResult> results) {
        if (results == null || results.getArray().length == 0)
            return false;
        Object[] keys = results.getArray()[0].getGeneratedKeys();
        return keys != null && keys.length > 0;
    }

    private static <T> Future<T> refusal(String key) {
        return Future.failedFuture("[%s] These details were not changed".formatted(key));
    }

    private static long countAt(QueryResult result) {
        if (result == null || result.getRowCount() == 0)
            return 0;
        Object value = result.getValue(0, 0);
        return value instanceof Number number ? number.longValue() : 0;
    }
}

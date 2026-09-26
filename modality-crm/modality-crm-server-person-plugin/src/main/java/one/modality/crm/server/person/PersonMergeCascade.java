package one.modality.crm.server.person;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Folding one person into another: every reference follows the survivor, then the duplicate goes.
 *
 * <p>Replaces the loop the customers screen used to send, which built {@code update <entity> set <field>}
 * from a list it carried — so the tables a client could rewrite in one call were whatever that client
 * named, and a browser could name any of them. The list is now {@link PersonReferences}, on this side.
 *
 * <p>One duplicate per call, in one transaction. Several duplicates are several calls, and that is the
 * point rather than a limitation: a merge that half-succeeded across people would leave rows pointing at a
 * person who no longer exists, and there is nothing sensible to do with the second half of it.
 *
 * <p>Raw SQL rather than DQL, because these are set-based rewrites across thirty tables. Allowed here and
 * refused from a browser — {@code ClientSubmitGuard} refuses client-origin raw statements, and this runs as
 * the server (see {@link ServerWrite}). Every statement is assembled from constants in
 * {@link PersonReferences}; the two ids are bound as parameters and nothing a caller sends is ever
 * concatenated into SQL.
 *
 * @author Claude Code
 */
final class PersonMergeCascade {

    private PersonMergeCascade() {
    }

    /** Refusals, each named so the screen says it in the reader's language rather than showing SQL. */
    static final String SAME_PERSON_KEY = "PersonMergeSamePersonError";
    static final String NO_SUCH_PERSON_KEY = "PersonMergeNoSuchPersonError";
    static final String HOLDS_GRANTS_KEY = "PersonMergeHoldsGrantsError";
    static final String HAS_ACCOUNT_KEY = "PersonMergeHasAccountError";

    /**
     * What the two people are, asked as one question: whether they exist, what owns them, and what would
     * refuse the merge.
     *
     * <p>The organizations are what the caller's grant is checked against — both of them. A grant is held
     * per organization, so checking only the person being kept would let a manager of one centre delete
     * another centre's person by naming their own as the survivor.
     */
    private static final String STATE_SQL =
        "select" +
        "  (select count(*) from person where id = $1)," +
        "  (select count(*) from person where id = $2)," +
        "  (select organization_id from person where id = $1)," +
        "  (select organization_id from person where id = $2)," +
        "  (select frontend_account_id from person where id = $2)," +
        "  (select count(*) from authorization_super_admin where super_admin_id = $2)" +
        "  + (select count(*) from authorization_organization_admin where admin_id = $2)" +
        "  + (select count(*) from authorization_organization_user_access where user_id = $2)" +
        "  + (select count(*) from authorization_management where manager_id = $2 or user_id = $2)";

    /**
     * Every column the database says points at a person.
     *
     * <p>Asked on every merge rather than trusted to a list written once: a key added later is invisible
     * to {@link PersonReferences} and, for the two that do not refuse a delete, invisible to the database
     * too. This is what turns "somebody forgot" into a refusal instead of into an erased booking.
     */
    private static final String REFERENCES_SQL =
        "select con.conrelid::regclass::text, a.attname" +
        " from pg_constraint con" +
        " join unnest(con.conkey) with ordinality as k(attnum, ord) on true" +
        " join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k.attnum" +
        " where con.contype = 'f' and con.confrelid = 'person'::regclass";

    /** How the endpoint hands its authorization back in, once both people's organizations are known. */
    @FunctionalInterface
    interface PersonScopeAuthorizer {
        Future<Boolean> authorize(Object organizationId, Object eventId, Supplier<Future<Boolean>> work);
    }

    /**
     * Repoints every reference from {@code duplicateId} onto {@code keptId} and deletes the duplicate.
     *
     * <p>Four steps: read what both people are, check the caller may reach the customers screen in BOTH
     * their organizations, check the catalogue holds no reference this build does not know about, then run
     * the batch.
     */
    static Future<Boolean> mergeDuplicatePerson(Object rawKeptId, Object rawDuplicateId, Object callerUserId, PersonScopeAuthorizer authorizer) {
        Object keptId = Numbers.toLong(rawKeptId);
        Object duplicateId = Numbers.toLong(rawDuplicateId);
        if (keptId == null || duplicateId == null)
            return RouteAccessGuard.refused();
        if (keptId.equals(duplicateId))
            return refusal(SAME_PERSON_KEY);
        return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(STATE_SQL)
                .setParameters(keptId, duplicateId)
                .build()))
            .compose(state -> {
                if (state == null || state.getRowCount() == 0)
                    return RouteAccessGuard.refused();
                Object keptOrganizationId = state.getValue(0, 2);
                Object duplicateOrganizationId = state.getValue(0, 3);
                // AUTHORIZE BEFORE ANSWERING ANYTHING ABOUT THESE PEOPLE. The named refusals below say
                // whether a person exists, whether they hold administrator rights and whether they have a
                // sign-in account — about any id a caller cares to send, and person ids are small and
                // sequential. Reported before the grant check they would be an oracle for mapping the
                // administrators of an organization the caller has no reach into, which matters all the
                // more while production still accepts a principal a caller merely asserts. Behind it,
                // they are told only to somebody already entitled to merge these two.
                //
                // Both organizations, innermost last. A person belonging to no centre — or one that does
                // not exist, whose organization reads null — has nothing to scope a grant against, so
                // mayReachRoute answers false and only a super administrator gets through. Deliberately:
                // a row nothing owns is not a row a centre's manager may delete.
                return authorizer.authorize(keptOrganizationId, null, () ->
                    authorizer.authorize(duplicateOrganizationId, null, () -> {
                        if (countAt(state, 0) == 0 || countAt(state, 1) == 0)
                            return refusal(NO_SUCH_PERSON_KEY);
                        // Privilege first: repointing these would hand the survivor what the duplicate
                        // could do, and dropping them would revoke an administrator silently. Neither is
                        // a merge's decision.
                        if (countAt(state, 5) > 0)
                            return refusal(HOLDS_GRANTS_KEY);
                        // An account's person is not a duplicate to be deleted — the account would be left
                        // with nobody able to sign in. Merging the ACCOUNTS is a different screen.
                        if (state.getValue(0, 4) != null)
                            return refusal(HAS_ACCOUNT_KEY);
                        return checkReferencesThenMerge(keptId, duplicateId, callerUserId);
                    }));
            });
    }

    /** Refuses when the database names a person from somewhere this build has never heard of. */
    private static Future<Boolean> checkReferencesThenMerge(Object keptId, Object duplicateId, Object callerUserId) {
        return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(REFERENCES_SQL)
                .build()))
            .compose(references -> {
                Set<String> unknown = unknownReferences(references);
                if (!unknown.isEmpty()) {
                    // Loud, and refusing: the two SET NULL keys mean a reference nobody handled can be
                    // erased rather than refused, so this must never be a warning somebody scrolls past.
                    Console.log("🛡 Person merge refused — the database references person from columns this"
                                + " build does not know: " + String.join(", ", unknown)
                                + ". Add them to PersonReferences (repoint, refuse, or record as cascading).");
                    return refusal(PersonReferences.UNKNOWN_REFERENCE_KEY);
                }
                return runMerge(keptId, duplicateId, callerUserId);
            });
    }

    /** Catalogue columns minus the ones {@link PersonReferences} accounts for. */
    static Set<String> unknownReferences(QueryResult catalogue) {
        Set<String> unknown = new LinkedHashSet<>();
        if (catalogue == null)
            return Set.of("(the catalogue could not be read)");
        Set<String> accountedFor = PersonReferences.accountedFor();
        for (int row = 0; row < catalogue.getRowCount(); row++) {
            // Object locals, deliberately: QueryResult.getValue is `<T> T`, and passing it straight
            // to String.valueOf infers T as char[] (the most specific overload) and emits a
            // checkcast. See InvitationRules, where the same two lines threw on every call.
            Object rawTable = catalogue.getValue(row, 0);
            Object rawColumn = catalogue.getValue(row, 1);
            String table = PersonReferences.unquote(String.valueOf(rawTable));
            String column = String.valueOf(rawColumn);
            String name = table + "." + column;
            if (!accountedFor.contains(name))
                unknown.add(name);
        }
        return unknown;
    }

    private static Future<Boolean> runMerge(Object keptId, Object duplicateId, Object callerUserId) {
        List<String> statements = mergeStatements();
        List<SubmitArgument> arguments = new ArrayList<>(statements.size());
        for (String statement : statements)
            arguments.add(new SubmitArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(statement) // assembled from constants; the ids below are the only input
                .setParameters(keptId, duplicateId)
                .build());
        return ServerWrite.asServerActingFor(callerUserId, () -> SubmitService.executeSubmitBatch(new Batch<>(arguments.toArray(new SubmitArgument[0]))))
            .map(ignored -> {
                Console.log("🗑 Person " + duplicateId + " merged into " + keptId
                            + " (" + statements.size() + " statements, one transaction)");
                return true;
            });
    }

    /**
     * The whole merge, in the order it has to happen.
     *
     * <p>Package-private so a check can read the statements without a database: what matters about them is
     * that every table the catalogue names is written before the delete, and that is a property of this
     * list rather than of any one statement.
     */
    static List<String> mergeStatements() {
        List<String> statements = new ArrayList<>();
        // 0. Take the duplicate's row for this transaction, BEFORE anything is repointed.
        //
        // Without it the repoints and the delete leave a window: a booking committed after its table was
        // repointed still names the duplicate when the delete runs, and document.person_id is ON DELETE
        // SET NULL — so it would not refuse, it would erase whose booking that was. Inserting a row that
        // references a person takes FOR KEY SHARE on it, which this conflicts with, so a booking arriving
        // mid-merge waits for the transaction and then finds the person gone and refuses honestly.
        statements.add("select id from person where id = $2 for update");
        // 1. The survivor's own references to the duplicate. These cannot be repointed onto the survivor —
        // that would make a person their own account-person or their own carer — and if they are left
        // alone the final delete fails on them, so they are cleared.
        statements.add("update person set account_person_id = null where id = $1 and account_person_id = $2");
        statements.add("update person set carer1_id = null where id = $1 and carer1_id = $2");
        statements.add("update person set carer2_id = null where id = $1 and carer2_id = $2");
        // 2. Rows the survivor already has an equivalent of, which a repoint would collide with.
        PersonReferences.DEDUPLICATED.forEach((table, spec) -> {
            String personColumn = spec[0];
            StringBuilder sameKey = new StringBuilder();
            for (String keyColumn : spec[1].split(",")) {
                String column = keyColumn.trim();
                // "is not distinct from", not "=": these keys include nullable columns, and two NULLs are
                // the same slot as far as the unique index is concerned.
                sameKey.append(" and o.").append(quote(column))
                       .append(" is not distinct from ").append(quote(table)).append('.').append(quote(column));
            }
            statements.add("delete from " + quote(table) + " where " + quote(personColumn) + " = $2"
                           + " and exists (select 1 from " + quote(table) + " o where o." + quote(personColumn) + " = $1"
                           + sameKey + ")");
        });
        // 3. Everything that simply follows the survivor.
        PersonReferences.DEDUPLICATED.forEach((table, spec) ->
            statements.add(repoint(table, spec[0])));
        PersonReferences.REPOINTED.forEach((key, column) ->
            statements.add(repoint(PersonReferences.table(key), column)));
        PersonReferences.UNCONSTRAINED_AUDIT.forEach((table, columns) -> {
            for (String column : columns)
                statements.add(repoint(table, column));
        });
        // 4. Nothing names the duplicate any more.
        statements.add("delete from person where id = $2");
        return statements;
    }

    /**
     * One repoint.
     *
     * <p>{@code person} excludes the survivor's own row: its references to the duplicate were cleared in
     * step 1, and repointing them here would point the row at itself.
     */
    private static String repoint(String table, String column) {
        return "update " + quote(table) + " set " + quote(column) + " = $1 where " + quote(column) + " = $2"
               + ("person".equals(table) ? " and id <> $1" : "");
    }

    /** Quoted, so a table named after a reserved word ({@code session_user}) is still a table name. */
    private static String quote(String identifier) {
        return '"' + identifier + '"';
    }

    private static Future<Boolean> refusal(String key) {
        return Future.failedFuture("[%s] This merge was not carried out".formatted(key));
    }

    private static long countAt(QueryResult result, int columnIndex) {
        Object value = result.getValue(0, columnIndex);
        return value instanceof Number number ? number.longValue() : 0;
    }

}

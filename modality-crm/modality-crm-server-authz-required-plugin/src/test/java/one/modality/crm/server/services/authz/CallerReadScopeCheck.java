package one.modality.crm.server.services.authz;

import one.modality.crm.server.services.authz.CallerReadScope.GrantRow;
import one.modality.crm.server.services.authz.CallerReadScope.Scope;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What a caller's grant rows flatten to — the policy a read scope predicate will be given.
 *
 * <p>No database: the flattening is a pure function over the three ids of each row, which is why it was pulled
 * out of the query path. Everything here is a decision somebody could reasonably make differently, so each one
 * is written down as a case rather than left to be inferred from the code.
 *
 * <p>No test framework either — runs from main() and exits non-zero, like the checks next door.
 *
 * @author Claude Code
 */
public class CallerReadScopeCheck {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // An organisation-wide row is what every row was before V0133, and it stays one column test: the whole
        // organisation is NOT expanded into its events, because document.organization_id is NOT NULL.
        check("an organisation-wide row scopes the organisation",
              scope(rows(grant(42, null, null))), events(), organizations(42));

        // Naming an event NARROWS. If an event row also widened to its organisation it would grant more than
        // the whole-organisation row above, which is the opposite of what the back office's dialog says.
        check("an event row scopes the event and NOT its organisation",
              scope(rows(grant(42, 7, null))), events(7), organizations());

        // Several rows are a disjunction - that is part 1 of the grant model, and it needs no schema.
        check("several rows add up",
              scope(rows(grant(42, 7, null), grant(42, 9, null), grant(51, null, null))),
              events(7, 9), organizations(51));

        // A type row contributes only what the expansion found. The expansion is a query, so it arrives here
        // as its result; the row itself must add nothing, or a type grant would widen to its organisation.
        check("a type row contributes its expanded events, not its organisation",
              CallerReadScope.assemble(rows(grant(42, null, 3)), noAdmin(), new int[]{11, 12}),
              events(11, 12), organizations());

        // An administrator of an organisation reads it whole, exactly as the grants computation treats them.
        check("an organisation administrator's organisation counts",
              CallerReadScope.assemble(rows(), List.of(42), new int[0]), events(), organizations(42));

        // The floor. A signed-in caller with no grants gets empty arrays, which is false for every row -
        // `= any('{}')` compiles and matches nothing, where `in ()` would not compile at all.
        Scope empty = scope(rows());
        check("no grants is empty, not unrestricted", empty, events(), organizations());
        checkTrue("and it reports itself empty", empty.isEmpty());
        checkTrue("and it is not unrestricted", !empty.unrestricted());

        // Duplicates collapse and the order is fixed. Not cosmetic: a push subscription is keyed by its
        // QueryArgument, whose equality compares parameter VALUES, so the same scope arriving in two orders
        // would be two subscriptions rather than one - and the type expansion runs as parallel queries.
        check("duplicates collapse and ids come out sorted",
              CallerReadScope.assemble(rows(grant(42, 9, null), grant(42, 7, null), grant(42, 9, null)),
                                       List.of(51, 42, 51), new int[]{9, 3}),
              events(3, 7, 9), organizations(42, 51));

        // A type row with no organisation is SKIPPED, not widened. It cannot happen today (organization_id is
        // NOT NULL), and the point is what happens if it ever could: the other reading is "every event of that
        // type everywhere", and a grant must not grow reach because a column went missing.
        checkTrue("a type row with no organisation is not expanded",
              CallerReadScope.typeRowsNeedingExpansion(rows(grant(null, null, 3))).isEmpty());
        checkTrue("a type row with an organisation is",
              CallerReadScope.typeRowsNeedingExpansion(rows(grant(42, null, 3))).size() == 1);
        checkTrue("an event row never needs expanding",
              CallerReadScope.typeRowsNeedingExpansion(rows(grant(42, 7, null))).isEmpty());

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " caller read scope checks failed");
    }

    private static GrantRow grant(Integer organizationId, Integer eventId, Integer eventTypeId) {
        return new GrantRow(organizationId, eventId, eventTypeId);
    }

    private static List<GrantRow> rows(GrantRow... grants) {
        return Arrays.asList(grants);
    }

    private static List<Integer> noAdmin() {
        return Collections.emptyList();
    }

    private static Scope scope(List<GrantRow> grants) {
        return CallerReadScope.assemble(grants, noAdmin(), new int[0]);
    }

    private static int[] events(int... ids) {
        return ids;
    }

    private static int[] organizations(int... ids) {
        return ids;
    }

    private static void check(String what, Scope scope, int[] expectedEvents, int[] expectedOrganizations) {
        if (Arrays.equals(scope.eventIds(), expectedEvents)
            && Arrays.equals(scope.organizationIds(), expectedOrganizations)) {
            pass++;
            System.out.println("  ok   " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what);
            System.out.println("         got      events=" + Arrays.toString(scope.eventIds())
                               + " organizations=" + Arrays.toString(scope.organizationIds()));
            System.out.println("         expected events=" + Arrays.toString(expectedEvents)
                               + " organizations=" + Arrays.toString(expectedOrganizations));
        }
    }

    private static void checkTrue(String what, boolean condition) {
        if (condition) {
            pass++;
            System.out.println("  ok   " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what);
        }
    }
}

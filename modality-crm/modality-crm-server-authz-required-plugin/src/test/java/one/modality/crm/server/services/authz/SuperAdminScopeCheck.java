package one.modality.crm.server.services.authz;

import dev.webfx.extras.operation.HasOperationCode;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.authz.core.InMemoryAuthorizationRuleRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership.Scope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Super administrators scoped to organizations (V0167): what their grants say, and which grant-table writes they may
 * make.
 *
 * <p>The grants are composed by the provider's own {@code composeGrants} and judged by the real rule engine, so a case
 * here is what the server and the browser would answer. The grant-table rule runs against a lookup answered from maps,
 * which is what lets every refusal be shown differing from an allowed write by one thing.
 *
 * <p>No database, no test framework — runs from main() and exits non-zero, like the checks next door.
 *
 * @author Claude Code
 */
public class SuperAdminScopeCheck {

    static int pass = 0, fail = 0;

    record Op(String code) implements HasOperationCode {
        public Object getOperationCode() { return code; }
    }

    static final Scope PLATFORM = new Scope(true, Set.of());
    static final Scope OF_42 = new Scope(false, Set.of(42));
    static final Scope OF_42_AND_51 = new Scope(false, Set.of(42, 51));

    public static void main(String[] args) {
        scopeSemantics();
        scopedGrants();
        grantTableWrites();
        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void scopeSemantics() {
        System.out.println("scope:");
        check("platform covers any organization",        PLATFORM.covers(7), true);
        check("platform covers no organization at all",  PLATFORM.covers(null), true);
        check("scoped covers its own",                   OF_42.covers(42), true);
        check("...given as a Long or a String id too",   OF_42.covers(42L) && OF_42.covers(Integer.valueOf(42)), true);
        check("scoped does not cover another",           OF_42.covers(7), false);
        check("scoped does not cover 'no organization'", OF_42.covers(null), false);
        check("coversAll: all within",                   OF_42_AND_51.coversAll(List.of(42, 51)), true);
        check("coversAll: one outside",                  OF_42_AND_51.coversAll(List.of(42, 7)), false);
        check("coversAll: nothing to cover is not covered for a scoped one", OF_42.coversAll(List.of()), false);
        check("not a super administrator",               Scope.NONE.isSuperAdmin(), false);
    }

    static void scopedGrants() {
        System.out.println("grants of a super administrator of organization 42:");
        String explicit = InMemoryAuthorizationRuleRegistry.EXPLICIT_OPERATION_PREFIX + "DataProtection\n";
        String loggedIn = "grant operation:GuestOp\n";
        String roleIn7 = "context:organizationId=7\ngrant operation:EditBooking\n";
        String orgAdminOf9 = "context:organizationId=9\ngrant operation:RouteToAdmin\ngrant route:/admin*\n";
        InMemoryAuthorizationRuleRegistry r = ModalityAuthorizationServerServiceProvider.parseRuleRegistry(
            ModalityAuthorizationServerServiceProvider.composeGrants(explicit, loggedIn, roleIn7, orgAdminOf9, OF_42));
        Map<String, String> org42 = Map.of("organizationId", "42"), org7 = Map.of("organizationId", "7"),
            org9 = Map.of("organizationId", "9"), none = Map.of();
        check("every operation in 42",                         r.doesRulesAuthorize(new Op("CancelBooking"), org42), true);
        check("no wildcard in 7",                              r.doesRulesAuthorize(new Op("CancelBooking"), org7), false);
        check("no wildcard with no organization",              r.doesRulesAuthorize(new Op("CancelBooking"), none), false);
        check("their role in 7 is kept",                       r.doesRulesAuthorize(new Op("EditBooking"), org7), true);
        check("their organization-admin grant in 9 is kept",   r.doesRulesAuthorize(new Op("RouteToAdmin"), org9), true);
        check("what every signed-in person holds is kept",     r.doesRulesAuthorize(new Op("GuestOp"), org7), true);
        check("DataProtection is not covered, even in 42",     r.doesRulesAuthorize(new Op("DataProtection"), org42), false);
        check("held for a whole organization: any op, via 42",
              r.doesRulesAuthorizeInSomeContext(new Op("CancelBooking"), c -> c.keySet().equals(Set.of("organizationId"))), true);

        String platform = ModalityAuthorizationServerServiceProvider.composeGrants(explicit, loggedIn, roleIn7, orgAdminOf9, PLATFORM);
        check("a platform super administrator's grants are as before",
              platform, explicit + "context:any\ngrant operation:*\ngrant route:*\n" + roleIn7);
        String nobody = ModalityAuthorizationServerServiceProvider.composeGrants(explicit, loggedIn, roleIn7, orgAdminOf9, Scope.NONE);
        check("a non-super-administrator's grants are as before", nobody, explicit + loggedIn + roleIn7 + orgAdminOf9);
    }

    static void grantTableWrites() {
        System.out.println("grant-table writes by a super administrator of organization 42:");
        FakeLookup db = new FakeLookup();
        db.rows.put(100, new GrantTableWritePolicy.AccessRow(42, null, null, 3));    // a role in 42
        db.rows.put(101, new GrantTableWritePolicy.AccessRow(7, null, null, 3));     // a role in 7
        db.rows.put(102, new GrantTableWritePolicy.AccessRow(42, null, null, 66));   // the DPO role in 42
        db.rows.put(103, new GrantTableWritePolicy.AccessRow(42, null, 801, 3));     // a role in 42, on 7's event type
        db.eventTypes.put(800, null);   // a type shared by every organization
        db.eventTypes.put(801, 7);      // organization 7's own type
        db.eventTypes.put(802, 42);     // organization 42's own type
        db.events.put(500, 42);
        db.events.put(501, 7);
        db.explicitRoles.add(66);

        String outside = GrantTableWritePolicy.OUTSIDE_SCOPE;
        check("insert an access row in 42",
              refusal(insert(Map.of("organization", 42, "user", 1, "role", 3)), db), null);
        check("insert one in 7",
              refusal(insert(Map.of("organization", 7, "user", 1, "role", 3)), db), outside);
        check("insert one with no organization",
              refusal(insert(Map.of("user", 1, "role", 3)), db), outside);
        check("insert one in 42 naming 42's event",
              refusal(insert(Map.of("organization", 42, "event", 500, "user", 1, "role", 3)), db), null);
        check("insert one in 42 naming 7's event",
              refusal(insert(Map.of("organization", 42, "event", 501, "user", 1, "role", 3)), db), outside);
        check("insert one in 42 on a shared event type",
              refusal(insert(Map.of("organization", 42, "eventType", 800, "user", 1, "role", 3)), db), null);
        check("insert one in 42 on 42's own event type",
              refusal(insert(Map.of("organization", 42, "eventType", 802, "user", 1, "role", 3)), db), null);
        check("insert one in 42 on 7's event type",
              refusal(insert(Map.of("organization", 42, "eventType", 801, "user", 1, "role", 3)), db), outside);
        check("insert one on an event type that does not exist",
              refusal(insert(Map.of("organization", 42, "eventType", 899, "user", 1, "role", 3)), db), outside);
        check("update a row already on 7's event type",
              refusal(update(103, Map.of("role", 4)), db), outside);
        check("insert one giving the DPO role",
              refusal(insert(Map.of("organization", 42, "user", 1, "role", 66)), db), GrantTableWritePolicy.EXPLICIT_ROLE);
        check("update 42's row, same organization",
              refusal(update(100, Map.of("role", 4)), db), null);
        check("move 42's row to 7",
              refusal(update(100, Map.of("organization", 7)), db), outside);
        check("update 7's row",
              refusal(update(101, Map.of("role", 4)), db), outside);
        check("move 7's row into 42",
              refusal(update(101, Map.of("organization", 42)), db), outside);
        check("delete 42's row",
              refusal(delete(100), db), null);
        check("delete 7's row",
              refusal(delete(101), db), outside);
        check("delete the DPO's row, in 42",
              refusal(delete(102), db), GrantTableWritePolicy.EXPLICIT_ROLE);
        check("take the DPO role away by changing it",
              refusal(update(102, Map.of("role", 3)), db), GrantTableWritePolicy.EXPLICIT_ROLE);
        check("a row that does not exist",
              refusal(delete(999), db), outside);
        check("a same-batch reference as the organization",
              refusal(insert(Map.of("organization", new Object(), "user", 1, "role", 3)), db), outside);
        check("a write with no bound on its rows",
              refusal(new WriteRequest("AuthorizationOrganizationUserAccess", WriteVerb.DELETE, new String[0], Map.of(), null, true, null), db), outside);
        check("a role (shared by every organization)",
              refusal(new WriteRequest("AuthorizationRole", WriteVerb.INSERT, new String[] { "name" }, Map.of("name", "x"), null, false, null), db),
              GrantTableWritePolicy.PLATFORM_ONLY);
        check("a super-administrator row",
              refusal(new WriteRequest("AuthorizationSuperAdmin", WriteVerb.INSERT, new String[] { "superAdmin", "organization" },
                  Map.of("superAdmin", 1, "organization", 42), null, false, null), db), GrantTableWritePolicy.PLATFORM_ONLY);
        check("an organization-admin row",
              refusal(new WriteRequest("AuthorizationOrganizationAdmin", WriteVerb.INSERT, new String[] { "admin", "organization" },
                  Map.of("admin", 1, "organization", 42), null, false, null), db), GrantTableWritePolicy.PLATFORM_ONLY);
    }

    static WriteRequest insert(Map<String, Object> values) {
        return new WriteRequest("AuthorizationOrganizationUserAccess", WriteVerb.INSERT, values.keySet().toArray(String[]::new),
            values, null, false, null);
    }

    static WriteRequest update(int id, Map<String, Object> values) {
        return new WriteRequest("AuthorizationOrganizationUserAccess", WriteVerb.UPDATE, values.keySet().toArray(String[]::new),
            values, id, false, null);
    }

    static WriteRequest delete(int id) {
        return new WriteRequest("AuthorizationOrganizationUserAccess", WriteVerb.DELETE, new String[0], Map.of(), id, false, null);
    }

    static String refusal(WriteRequest write, FakeLookup db) {
        Future<String> answer = GrantTableWritePolicy.scopedRefusal(write, OF_42, db);
        if (!answer.isComplete() || answer.failed())
            return "<no answer>";
        return answer.result();
    }

    static final class FakeLookup implements GrantTableWritePolicy.AccessRowLookup {
        final Map<Integer, GrantTableWritePolicy.AccessRow> rows = new HashMap<>();
        final Map<Integer, Integer> events = new HashMap<>();
        final Map<Integer, Integer> eventTypes = new HashMap<>();
        final java.util.Set<Integer> explicitRoles = new java.util.HashSet<>();

        @Override public Future<GrantTableWritePolicy.AccessRow> storedRow(Object id) {
            return Future.succeededFuture(rows.get(((Number) id).intValue()));
        }
        @Override public Future<Object> eventOrganization(Object eventId) {
            return Future.succeededFuture(events.get(((Number) eventId).intValue()));
        }
        @Override public Future<java.util.Optional<Object>> eventTypeOrganization(Object eventTypeId) {
            int id = ((Number) eventTypeId).intValue();
            return Future.succeededFuture(eventTypes.containsKey(id) ? java.util.Optional.ofNullable(eventTypes.get(id)) : null);
        }
        @Override public Future<Boolean> roleGrantsExplicitOnly(Object roleId) {
            return Future.succeededFuture(explicitRoles.contains(((Number) roleId).intValue()));
        }
    }

    static void check(String what, Object actual, Object expected) {
        if (java.util.Objects.equals(actual, expected)) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what + " (got " + actual + ", wanted " + expected + ")"); }
    }
}

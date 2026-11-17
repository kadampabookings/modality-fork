package one.modality.crm.server.services.authz;

import dev.webfx.extras.operation.HasOperationCode;
import dev.webfx.stack.authz.core.InMemoryAuthorizationRuleRegistry;
import dev.webfx.stack.authz.core.operation.OperationAuthorizationRuleParser;

import java.util.Map;

/**
 * Explicit-only operations (V0166) through the grant string the provider composes, parsed as the server parses it.
 *
 * <p>The engine's own check (CoreSemanticsCheck in webfx-stack-authz-core) proves a wildcard no longer covers an
 * explicit-only operation. This one proves the PROVIDER keeps the grant that should still cover it: the push used to
 * replace a super administrator's role sections with the wildcard section, so a super administrator who is also the
 * Data Protection Officer lost DataProtection the day the wildcard stopped covering it.
 *
 * <p>No database, no test framework — runs from main() and exits non-zero, like the checks next door.
 *
 * @author Claude Code
 */
public class ExplicitOnlyGrantsCheck {

    static int pass = 0, fail = 0;

    record Op(String code) implements HasOperationCode {
        public Object getOperationCode() { return code; }
    }

    static final String EXPLICIT = InMemoryAuthorizationRuleRegistry.EXPLICIT_OPERATION_PREFIX + "DataProtection\n";
    static final String LOGGED_IN = "grant operation:GuestOp\n";
    static final String DPO_ROLE = "context:organizationId=5\ngrant operation:DataProtection\n";
    static final String OTHER_ROLE = "context:organizationId=5\ngrant operation:EditBooking\n";
    static final String ORG_ADMIN = "context:organizationId=5\ngrant operation:RouteToAdmin\n";
    static final String SUPER_ADMIN = "context:any\ngrant operation:*\ngrant route:*\n";

    public static void main(String[] args) {
        Map<String, String> org5 = Map.of("organizationId", "5"), org6 = Map.of("organizationId", "6");

        InMemoryAuthorizationRuleRegistry superAdminDpo = registry(
            ModalityAuthorizationServerServiceProvider.composeGrants(EXPLICIT, LOGGED_IN, DPO_ROLE, ORG_ADMIN, SUPER_ADMIN));
        check("a super administrator who is the DPO holds DataProtection in their role's organization",
              superAdminDpo.doesRulesAuthorize(new Op("DataProtection"), org5), true);
        check("...and only there", superAdminDpo.doesRulesAuthorize(new Op("DataProtection"), org6), false);
        check("...and keeps every other operation through the wildcard",
              superAdminDpo.doesRulesAuthorize(new Op("AnythingAtAll"), org6), true);

        InMemoryAuthorizationRuleRegistry superAdmin = registry(
            ModalityAuthorizationServerServiceProvider.composeGrants(EXPLICIT, LOGGED_IN, OTHER_ROLE, ORG_ADMIN, SUPER_ADMIN));
        check("a super administrator without the role does not hold DataProtection",
              superAdmin.doesRulesAuthorize(new Op("DataProtection"), org5), false);
        check("...in no context either", superAdmin.doesRulesAuthorize(new Op("DataProtection"), Map.of()), false);

        InMemoryAuthorizationRuleRegistry dpo = registry(
            ModalityAuthorizationServerServiceProvider.composeGrants(EXPLICIT, LOGGED_IN, DPO_ROLE, "", ""));
        check("a DPO who is not a super administrator holds DataProtection",
              dpo.doesRulesAuthorize(new Op("DataProtection"), org5), true);
        check("...and the operations every signed-in person holds", dpo.doesRulesAuthorize(new Op("GuestOp"), org6), true);

        InMemoryAuthorizationRuleRegistry staff = registry(
            ModalityAuthorizationServerServiceProvider.composeGrants(EXPLICIT, LOGGED_IN, OTHER_ROLE, ORG_ADMIN, ""));
        check("other staff do not hold DataProtection", staff.doesRulesAuthorize(new Op("DataProtection"), org5), false);
        check("...but keep their organization-admin grant", staff.doesRulesAuthorize(new Op("RouteToAdmin"), org5), true);

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /** Parsed exactly as createUserRuleRegistry does: the operation parser only, line by line. */
    static InMemoryAuthorizationRuleRegistry registry(String grants) {
        InMemoryAuthorizationRuleRegistry registry = new InMemoryAuthorizationRuleRegistry();
        registry.setAuthorizationRuleParser(new OperationAuthorizationRuleParser());
        for (String line : grants.split("\n")) {
            line = line.trim();
            if (!line.isEmpty())
                registry.registerAuthorizationRule(line);
        }
        return registry;
    }

    static void check(String what, boolean actual, boolean expected) {
        if (actual == expected) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what + " (got " + actual + ", wanted " + expected + ")"); }
    }
}

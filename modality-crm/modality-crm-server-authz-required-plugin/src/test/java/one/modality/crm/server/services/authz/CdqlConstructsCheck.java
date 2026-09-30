package one.modality.crm.server.services.authz;

import dev.webfx.stack.db.query.ClientReadInspectionRegistry.ReadShape;

import java.util.ArrayList;
import java.util.List;

/**
 * That the dialect this inventory reports against still matches the traffic it was written from.
 *
 * <p>The census below is the term classes observed in production across 2026-09-28 and 2026-09-29, two full
 * working days after a deploy reset the shape map so every distinct shape re-logged once. It is pasted here
 * deliberately rather than derived: the allowlist's only justification is that it came from real traffic, and
 * a check that recomputed it from the same source as the list would agree with itself and prove nothing.
 *
 * <p><b>When this fails, it is telling you the dialect grew</b> — that is information, not a defect to
 * silence. Add the term to the allowlist along with where it came from, or find out why a client started
 * sending it. Do not delete the case.
 */
public class CdqlConstructsCheck {

    /** Observed in production, 2026-09-28 / 2026-09-29. */
    private static final String[] OBSERVED_IN_PRODUCTION = {
        "Alias", "And", "As", "Call", "Constant", "DomainField", "Dot", "Equals", "Exists", "ExpressionArray",
        "GreaterThan", "GreaterThanOrEquals", "IdExpression", "In", "LessThan", "LessThanOrEquals",
        "Minus", "Multiply", "Not", "NotEquals", "Or", "Ordered", "ParameterReference", "Plus", "Select",
        "SelectExpression", "TernaryExpression", "Union"
    };

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        List<String> logged = new ArrayList<>();
        ClientReadInventory inventory = new ClientReadInventory(logged::add);

        // Everything the census saw must pass silently.
        inventory.onRead(shape("Document", "select", OBSERVED_IN_PRODUCTION));
        check("the observed census is entirely inside the dialect",
              logged.stream().noneMatch(l -> l.contains("CDQL-UNEXPECTED")));

        // Like appears only under back-office search, so no weekend or light-traffic census contains it.
        logged.clear();
        inventory.onRead(shape("Person", "select", new String[]{"Like", "And", "DomainField"}));
        check("Like is in the dialect although this census did not see it",
              logged.stream().noneMatch(l -> l.contains("CDQL-UNEXPECTED")));

        // And the reporting has to actually fire, or its silence means nothing.
        logged.clear();
        inventory.onRead(shape("Document", "select", new String[]{"And", "SomeTermNobodyAnticipated"}));
        check("an unanticipated term is named",
              logged.stream().anyMatch(l -> l.contains("CDQL-UNEXPECTED") && l.contains("SomeTermNobodyAnticipated")));
        check("and the line says it was allowed rather than refused",
              logged.stream().anyMatch(l -> l.contains("Allowed, not refused")));

        // Every occurrence, not one in ten: the throttle that hid CAPABILITY-READ must not be reintroduced.
        logged.clear();
        for (int i = 0; i < 5; i++)
            inventory.onRead(shape("Document", "select", new String[]{"SomeTermNobodyAnticipated"}));
        check("every occurrence is logged, not powers of ten",
              logged.stream().filter(l -> l.contains("CDQL-UNEXPECTED")).count() == 5);

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " CDQL construct checks failed");
    }

    private static ReadShape shape(String entity, String kind, String[] constructs) {
        return new ReadShape(entity, kind, new String[0], new String[0], new String[0], new String[0],
                             constructs, false, true);
    }

    private static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else    { fail++; System.out.println("  FAIL " + what); }
    }
}

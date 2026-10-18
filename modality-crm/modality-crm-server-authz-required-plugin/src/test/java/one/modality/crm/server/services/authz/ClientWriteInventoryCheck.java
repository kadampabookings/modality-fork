package one.modality.crm.server.services.authz;

import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Check for the client-write inventory's two pieces of pure logic: what makes two writes the same
 * shape, and when a shape's count is worth another log line.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero
 * on failure, following {@link ProtectedEntityWritesCheck}.
 *
 * <p><b>What is worth pinning here is narrow, and saying so matters.</b> The inventory's real risk is
 * not arithmetic — it is logging a value that turns out to be personal data, and that is a property of
 * what {@code shapeOf} concatenates rather than of what it returns for a given input. So the first
 * check below asserts the ABSENCE of the written values from the shape: it is the assertion that would
 * fail if somebody later reached for {@code writtenValues()} to make a log line more helpful.
 */
public class ClientWriteInventoryCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static ProtectedEntityWriteRegistry.WriteRequest request(String entity,
                                                             ProtectedEntityWriteRegistry.WriteVerb verb,
                                                             Object targetId,
                                                             String... fields) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (String field : fields)
            values.put(field, "Jane Doe <jane@example.com>"); // a value no shape may ever carry
        // These fixtures are about field lists and caller classes; false keeps them on the ordinary
        // path rather than the unbounded one, which targetShapeOf reports separately.
        return new ProtectedEntityWriteRegistry.WriteRequest(entity, verb, fields, values, targetId, false, null);
    }

    public static void main(String[] args) {
        System.out.println("ClientWriteInventoryCheck");

        // --- the shape carries names, never values ---
        String personUpdate = ClientWriteInventory.shapeOf(
            request("Person", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 42, "firstName", "email"),
            "ModalityUserPrincipal");
        check("shape names the entity and verb", personUpdate.contains("Person") && personUpdate.contains("UPDATE"));
        check("shape names the written fields", personUpdate.contains("email") && personUpdate.contains("firstName"));
        check("shape carries NO written value", !personUpdate.contains("jane@example.com") && !personUpdate.contains("Jane"));
        check("shape carries NO target id", !personUpdate.contains("42"));

        // --- field order is not part of the shape ---
        String ab = ClientWriteInventory.shapeOf(
            request("Person", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 1, "firstName", "lastName"), "X");
        String ba = ClientWriteInventory.shapeOf(
            request("Person", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 1, "lastName", "firstName"), "X");
        check("same fields in a different order are one shape", ab.equals(ba));

        // --- the distinctions an allowlist would actually be written against ---
        String insert = ClientWriteInventory.shapeOf(
            request("Person", ProtectedEntityWriteRegistry.WriteVerb.INSERT, 1, "firstName"), "X");
        String update = ClientWriteInventory.shapeOf(
            request("Person", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 1, "firstName"), "X");
        check("verb separates shapes", !insert.equals(update));

        String guest = ClientWriteInventory.shapeOf(
            request("Person", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 1, "firstName"), "ModalityGuestPrincipal");
        check("caller class separates shapes", !update.equals(guest));

        // An insert has no WHERE and so never yields a target id — but that is not the same as a target
        // nobody could work out, and conflating the two would misreport every client insert as
        // unconstrainable.
        check("an insert is target=new, not UNREADABLE", insert.contains("target=new") && !insert.contains("UNREADABLE"));
        String insertNoTarget = ClientWriteInventory.shapeOf(
            request("Person", ProtectedEntityWriteRegistry.WriteVerb.INSERT, null, "firstName"), "X");
        check("an insert reads the same whether or not a target id came through", insert.equals(insertNoTarget));

        String unreadable = ClientWriteInventory.shapeOf(
            request("Person", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, null, "firstName"), "X");
        check("an unreadable target is its own shape", !update.equals(unreadable));
        check("an unreadable target says so", unreadable.contains("UNREADABLE"));

        // --- a delete sets nothing, and must still be a shape rather than a blank ---
        String delete = ClientWriteInventory.shapeOf(
            request("Person", ProtectedEntityWriteRegistry.WriteVerb.DELETE, 7), "X");
        check("a delete is a shape with no fields", delete.contains("DELETE") && delete.contains("[]"));

        // --- submit grouping: what ONE caller action contained ---
        // A submit's writes arrive as a contiguous run between the boundary calls; what is measured is
        // the composition (can this name one operation?) and the number of contexts it named.
        List<String> submitLines = new ArrayList<>();
        ClientWriteInventory submitInventory = new ClientWriteInventory(submitLines::add);

        submitInventory.onSubmitBegin(2);
        submitInventory.onWrite(request("Document", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 1, "confirmed"));
        submitInventory.onWrite(request("DocumentLine", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 2, "cancelled"));
        submitInventory.onSubmitEnd();
        String submitLine = submitLines.stream().filter(line -> line.contains("SUBMIT")).findFirst().orElse("");
        check("a submit is reported as one shape", !submitLine.isEmpty());
        check("the submit names its parts", submitLine.contains("Document UPDATE") && submitLine.contains("DocumentLine UPDATE"));
        check("the submit says how many statements it carried", submitLine.contains("n=2"));
        // The whole point of the grouping: two entities in one flush is the composite case the declared
        // intent either splits or keeps the per-entity map for.
        check("a two-entity submit is distinguishable from two one-entity submits",
            submitLine.contains("{Document UPDATE, DocumentLine UPDATE}"));

        // A write that names no context must say so rather than count as a context of its own: an
        // update by id carries none, and reporting it as "contexts=1" would claim knowledge of where it
        // belonged.
        check("a context nobody named is reported as unnamed", submitLine.contains("contexts=0+unnamed"));

        // A submit whose statements were all uninspected says nothing about composition, and must not
        // bury the ones that do.
        submitLines.clear();
        submitInventory.onSubmitBegin(3);
        submitInventory.onSubmitEnd();
        check("an empty submit is not reported", submitLines.stream().noneMatch(line -> line.contains("SUBMIT")));

        // Seeing part of a submit is not the same as seeing it whole -- an inspector told n=5 that was
        // shown 1 write must not report a one-statement action.
        submitLines.clear();
        submitInventory.onSubmitBegin(5);
        submitInventory.onWrite(request("Document", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 1, "confirmed"));
        submitInventory.onSubmitEnd();
        String partial = submitLines.stream().filter(line -> line.contains("SUBMIT")).findFirst().orElse("");
        check("a partly inspected submit says so", partial.contains("n=5") && partial.contains("inspected 1"));

        // A begin arriving while one is open closes it rather than dropping what it held: losing those
        // writes would under-report composition, which is the one thing this measures.
        submitLines.clear();
        submitInventory.onSubmitBegin(1);
        submitInventory.onWrite(request("Person", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 1, "firstName"));
        submitInventory.onSubmitBegin(1);
        submitInventory.onSubmitEnd();
        // Matched on a line that is BOTH a submit line and names the write: every statement is also
        // logged as its own shape, and "Person UPDATE" appears there too — so a looser matcher passes
        // with the guard deleted, which is exactly what it did.
        check("a submit left open is still reported",
            submitLines.stream().anyMatch(line -> line.contains("SUBMIT") && line.contains("Person UPDATE")));

        // The submit line derives its context by READING the written values, which is the one place in
        // this class where a value is touched. Pinned with a fixture that actually has a context, so
        // contextOf produces something — the existing value assertion below covers only statement
        // lines, from an inventory that is never told about a submit at all.
        submitLines.clear();
        Map<String, Object> contextValues = new java.util.LinkedHashMap<>();
        contextValues.put("organization", 42);
        contextValues.put("event", 7);
        contextValues.put("firstName", "Jane Doe <jane@example.com>");
        submitInventory.onSubmitBegin(1);
        submitInventory.onWrite(new ProtectedEntityWriteRegistry.WriteRequest("Document",
            ProtectedEntityWriteRegistry.WriteVerb.INSERT,
            new String[] {"organization", "event", "firstName"}, contextValues, null, false, null));
        submitInventory.onSubmitEnd();
        String contextLine = submitLines.stream().filter(line -> line.contains("SUBMIT")).findFirst().orElse("");
        check("a submit that names its context counts it", contextLine.contains("contexts=1"));
        check("no submit line carries a written value",
            submitLines.stream().noneMatch(line -> line.contains("jane@example.com")));
        // Not even the context itself: which centre a write belonged to is not needed to answer whether
        // one submit touched two of them, and an id is a reference to somebody's centre.
        check("a submit line names no context id", !contextLine.contains("42") && !contextLine.contains("/7"));

        // --- the log threshold ---
        check("first occurrence logs", ClientWriteInventory.isPowerOfTen(1));
        check("10th logs", ClientWriteInventory.isPowerOfTen(10));
        check("1000th logs", ClientWriteInventory.isPowerOfTen(1000));
        check("2nd does not log", !ClientWriteInventory.isPowerOfTen(2));
        check("11th does not log", !ClientWriteInventory.isPowerOfTen(11));
        check("20th does not log", !ClientWriteInventory.isPowerOfTen(20));
        check("999th does not log", !ClientWriteInventory.isPowerOfTen(999));
        check("a zero count logs nothing", !ClientWriteInventory.isPowerOfTen(0));

        // --- the cap, which is what stands between a hostile client and the heap ---
        // A caller who can send any subset of a table's columns mints a new shape per subset. Recording
        // those without a bound is unbounded memory AND one log line each, both reachable by anyone who
        // can open a socket.
        java.util.List<String> lines = new java.util.ArrayList<>();
        ClientWriteInventory inventory = new ClientWriteInventory(lines::add);
        for (int i = 0; i < 1200; i++)
            inventory.onWrite(request("Person", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 1, "f" + i));
        check("distinct shapes stop being recorded at the cap", inventory.distinctShapeCount() <= 500);
        // The log volume is the half that bites first: an uncapped inventory writes one line per subset,
        // so a flood is a flood in CloudWatch long before it is a problem in the heap.
        check("a 1200-shape flood does not write 1200 lines", lines.size() < 600);
        // And a shape already known keeps counting after the cap: the cap must not blind the inventory
        // to the traffic it was recording before the flood arrived.
        int knownBefore = inventory.distinctShapeCount();
        inventory.onWrite(request("Person", ProtectedEntityWriteRegistry.WriteVerb.UPDATE, 1, "f0"));
        check("a known shape still counts past the cap", inventory.distinctShapeCount() == knownBefore);
        check("no line carries a written value", lines.stream().noneMatch(l -> l.contains("jane@example.com")));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}

package one.modality.crm.server.services.authz;

import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;

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
        return new ProtectedEntityWriteRegistry.WriteRequest(entity, verb, fields, values, targetId, false);
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
